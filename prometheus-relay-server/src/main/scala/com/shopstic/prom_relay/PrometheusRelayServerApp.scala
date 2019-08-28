package com.shopstic.prom_relay

import java.time.Instant

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.stream.scaladsl.{Flow, MergeHub, Sink, Source}
import akka.util.{ByteString, Timeout}
import akka.{Done, NotUsed}
import com.github.pawelj_pl.prometheus_metrics_parser.parser.{ParseException, Parser}
import com.github.pawelj_pl.prometheus_metrics_parser.{Metric, MetricValue}
import com.typesafe.config.Config
import dev.chopsticks.fp.{AkkaApp, AkkaEnv, ConfigEnv, ZLogger}
import dev.chopsticks.util.config.PureconfigLoader
import eu.timepit.refined.types.net.PortNumber
import pureconfig.ConfigConvert
import zio.{Task, ZIO, ZManaged, ZSchedule}

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.duration._

object PrometheusRelayServerApp extends AkkaApp {

  final case class AppConfig(
    originLabel: String,
    bindingInterface: String,
    bindingPort: PortNumber,
    scrapeTimeout: Timeout,
    scrapeInterval: FiniteDuration,
    pruneExpiry: FiniteDuration,
    pruneInterval: FiniteDuration
  )

  object AppConfig {
    //noinspection TypeAnnotation
    implicit val configConvert = {
      import dev.chopsticks.util.config.PureconfigConverters._
      import eu.timepit.refined.pureconfig._
      ConfigConvert[AppConfig]
    }
  }

  type Cfg = ConfigEnv[AppConfig]

  final case class ScrapeSnapshot(origin: String, metrics: List[Metric])

  trait MetricSnapshotService {
    def render(): String

    def prune(expiry: FiniteDuration): List[String]

    def update(origin: String, metrics: List[Metric]): Unit
  }

  object MetricSnapshotService {
    final case class MetricSnapshotState(lastUpdated: Instant, metrics: List[Metric])

    trait Live extends MetricSnapshotService {
      private val state = TrieMap.empty[String, MetricSnapshotState]

      def render(): String = {
        val output = state.values
          .flatMap {
            case MetricSnapshotState(_, metrics) =>
              metrics.map(_.render)
          }
          .mkString("\n\n")
        output
      }

      def prune(expiry: FiniteDuration): List[String] = {
        val now = Instant.now
        val expiryNanos = expiry.toNanos
        val toPrune = state.collect {
          case (k, s) if java.time.Duration.between(s.lastUpdated, now).toNanos > expiryNanos =>
            k
        }.toList
        val _ = state --= toPrune
        toPrune
      }

      def update(origin: String, metrics: List[Metric]): Unit = {
        state.update(origin, MetricSnapshotState(Instant.now, metrics))
      }
    }

  }

  type Env = AkkaApp.Env with MetricSnapshotService with Cfg

  protected def createEnv(untypedConfig: Config) = {
    import dev.chopsticks.util.config.PureconfigConverters._

    ZManaged.environment[AkkaApp.Env].map { env =>
      new AkkaApp.LiveEnv with MetricSnapshotService.Live with Cfg {
        implicit val actorSystem: ActorSystem = env.actorSystem
        val config: AppConfig = PureconfigLoader.unsafeLoad[AppConfig](untypedConfig, "app")
      }
    }
  }

  protected def createServer(
    handler: Flow[ScrapeSnapshot, Instant, NotUsed],
    bindingInterface: String,
    bindingPort: PortNumber,
    transformMetric: (Metric, String) => Metric
  ): ZManaged[AkkaEnv with MetricSnapshotService, Throwable, Http.ServerBinding] = {

    ZManaged.make(ZIO.accessM[AkkaEnv with MetricSnapshotService] { env =>
      import akka.http.scaladsl.server.Directives._
      import env._

      Task.fromFuture {
        _ =>
          val baseFlow = Flow[Message]
            .map(_.asBinaryMessage)
            .mapAsync(1) {
              case BinaryMessage.Streamed(s) =>
                s.runFold(ByteString.empty)(_ ++ _)
              case BinaryMessage.Strict(s) =>
                Future.successful(s)
            }

          def createWsHandler(origin: String) = {
            baseFlow
              .flatMapConcat { s =>
                Parser().parseE(s.utf8String) match {
                  case Left(error) =>
                    Source.failed(ParseException(error))

                  case Right(parsed) =>
                    Source.single(ScrapeSnapshot(origin, parsed.map(transformMetric(_, origin))))
                }
              }
              .via(handler)
              .map(r => TextMessage(r.toString))
          }

          val route = concat(
            path("join" / ".+".r) { origin =>
              get {
                handleWebSocketMessages(createWsHandler(origin))
              }
            },
            path("metrics") {
              get {
                complete(render())
              }
            }
          )

          Http().bindAndHandle(route, bindingInterface, bindingPort.value)
      }
    }) { binding =>
      Task.fromFuture(_ => binding.unbind()).orDie
    }
  }

  protected def createMergeHub[Mat](sink: Sink[ScrapeSnapshot, Mat]) = {
    ZIO.access[AkkaEnv] { env =>
      import env._
      MergeHub.source[ScrapeSnapshot](perProducerBufferSize = 16).to(sink).run()
    }
  }

  protected def transformMetricValues(metric: Metric, transform: List[MetricValue] => List[MetricValue]): Metric = {
    metric match {
      case m @ Metric.Counter(_, _, values) => m.copy(values = transform(values))
      case m @ Metric.Gauge(_, _, values) => m.copy(values = transform(values))
      case m @ Metric.Histogram(_, _, values, _, _) => m.copy(values = transform(values))
      case m @ Metric.Summary(_, _, values, _, _) => m.copy(values = transform(values))
      case m @ Metric.Untyped(_, _, values) => m.copy(values = transform(values))
    }
  }

  protected def createHandler(
    sink: Sink[ScrapeSnapshot, NotUsed],
    scrapeTimeout: Timeout,
    scrapeInterval: FiniteDuration
  ): Flow[ScrapeSnapshot, Instant, NotUsed] = {
    Flow[ScrapeSnapshot]
      .idleTimeout(scrapeTimeout.duration)
      .alsoTo(sink)
      .map { _ =>
        Instant.now
      }
      .throttle(1, scrapeInterval)
      .merge(Source.single(Instant.now))
  }

  protected def createSink: ZIO[MetricSnapshotService, Nothing, Sink[ScrapeSnapshot, Future[Done]]] = {
    ZIO.access[MetricSnapshotService] { env =>
      Sink.foreach[ScrapeSnapshot] {
        case ScrapeSnapshot(origin, metrics) =>
          env.update(origin, metrics)
      }
    }
  }

  protected def run =
    for {
      appConfig <- ZIO.access[Cfg](_.config)
      transformer = (metric: Metric, origin: String) => {
        transformMetricValues(metric, values => {
          values.map(v => v.copy(labels = v.labels.updated(appConfig.originLabel, origin)))
        })
      }
      sink <- createSink
      mergeHub <- createMergeHub(sink)
      handler = createHandler(mergeHub, appConfig.scrapeTimeout, appConfig.scrapeInterval)
      pruneFib <- ZIO
        .access[MetricSnapshotService](_.prune(appConfig.pruneExpiry))
        .repeat(ZSchedule.fixed(zio.duration.Duration.fromScala(appConfig.pruneInterval)).logInput {
          pruned: List[String] =>
            if (pruned.nonEmpty) {
              ZLogger.info(s"Pruned: ${pruned.size} keys: ${pruned.mkString(", ")}")
            }
            else ZIO.unit
        })
        .fork
      _ <- createServer(handler, appConfig.bindingInterface, appConfig.bindingPort, transformer).use { binding =>
        ZLogger.info(s"Server is up: ${binding.localAddress}") *> ZIO.never.unit
      }
      _ <- pruneFib.join
    } yield ()
}
