package com.shopstic.prom_relay

import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws._
import akka.stream.KillSwitches
import akka.stream.scaladsl.{Flow, Keep, RestartFlow, Sink}
import dev.chopsticks.fp.{AkkaEnv, LogEnv, LoggingContext, ZIOExt}
import pureconfig.ConfigConvert
import pureconfig.ConfigConvert.viaNonEmptyStringTry
import pureconfig.generic.FieldCoproductHint
import zio.{RIO, ZIO}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

object PrometheusRelayClients extends LoggingContext {
  sealed trait PrometheusRelayConfig
  final case class EnabledPrometheusRelayConfig(
    originId: String,
    serverUri: Uri,
    clientMetricsUri: Uri,
    retryMinBackoff: FiniteDuration,
    retryMaxBackoff: FiniteDuration,
    retryRandomFactor: Double,
    idleTimeout: FiniteDuration
  ) extends PrometheusRelayConfig
  object DisabledPrometheusRelayConfig extends PrometheusRelayConfig

  object PrometheusRelayConfig {
    import dev.chopsticks.util.config.PureconfigConverters._

    implicit val uriConfigConverter: ConfigConvert[Uri] = viaNonEmptyStringTry[Uri](s => Try(Uri(s)), _.toString)

    implicit val hint: FieldCoproductHint[PrometheusRelayConfig] =
      new FieldCoproductHint[PrometheusRelayConfig]("state") {
        override def fieldValue(name: String): String = name.dropRight("PrometheusRelayConfig".length).toLowerCase()
      }

    //noinspection TypeAnnotation
    implicit val configConverter = ConfigConvert[PrometheusRelayConfig]
  }

  def createClient(config: EnabledPrometheusRelayConfig): RIO[AkkaEnv with LogEnv, Unit] = {
    ZIOExt.interruptableGraph(
      ZIO.access[AkkaEnv with LogEnv] { env =>
        import env._

        val ks = KillSwitches.shared("prometheus-relay-client-killswitch")

        val webSocketFlow =
          RestartFlow.withBackoff(config.retryMinBackoff, config.retryMaxBackoff, config.retryRandomFactor) { () =>
            Http()
              .webSocketClientFlow(
                WebSocketRequest(
                  uri = config.serverUri.withPath(Uri.Path / "join" / config.originId)
                )
              )
              .idleTimeout(config.idleTimeout)
          }

        val scrapeFlow = Flow[Message]
          .map(_.asTextMessage.getStrictText)
          .wireTap(
            time => env.logger.debug(s"[$time] Scraping metrics from local client: ${config.clientMetricsUri.toString}")
          )
          .mapAsync(1) { _ =>
            Http()
              .singleRequest(HttpRequest(uri = config.clientMetricsUri))
              .flatMap { res =>
                if (res.status.isSuccess()) {
                  Future.successful(BinaryMessage(res.entity.dataBytes.via(ks.flow)))
                }
                else {
                  res.entity.dataBytes
                    .via(ks.flow)
                    .runWith(Sink.ignore)
                    .flatMap(
                      _ =>
                        Future.failed(
                          new IllegalStateException(s"Local client metrics scraping failed with status: ${res.status}")
                        )
                    )
                }
              }
          }
          .watchTermination() { case (_, f) => f }

        Flow[Message]
          .via(ks.flow)
          .via(webSocketFlow)
          .joinMat(scrapeFlow)(Keep.right)
          .mapMaterializedValue(f => (ks, f.map(_ => ())))
      },
      graceful = true
    )
  }
}
