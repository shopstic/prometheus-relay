package com.shopstic.prom_relay

import akka.Done
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws._
import akka.stream.KillSwitches
import akka.stream.scaladsl.{Flow, Keep, Sink}
import dev.chopsticks.fp.{AkkaEnv, LogEnv, LoggingContext, ZIOExt}
import zio.{TaskR, ZIO}

import scala.concurrent.Future

object PrometheusRelayClients extends LoggingContext {
  def createClient(
    originId: String,
    serverUri: Uri,
    clientMetricsUri: Uri
  ): TaskR[AkkaEnv with LogEnv, (WebSocketUpgradeResponse, Done)] = {
    ZIOExt.interruptableGraph(
      ZIO.access[AkkaEnv with LogEnv] { env =>
        import env._

        val ks = KillSwitches.shared("prometheus-relay-client-killswitch")

        val webSocketFlow = Http().webSocketClientFlow(
          WebSocketRequest(
            uri = serverUri.withPath(Uri.Path / "join" / originId)
          )
        )

        val scrapeFlow = Flow[Message]
          .map(_.asTextMessage.getStrictText)
          .wireTap(
            time => env.logger.debug(s"[$time] Scraping metrics from local client: ${clientMetricsUri.toString}")
          )
          .mapAsync(1) { _ =>
            Http()
              .singleRequest(HttpRequest(uri = clientMetricsUri))
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
          .viaMat(webSocketFlow)(Keep.right)
          .joinMat(scrapeFlow)(Keep.both)
          .mapMaterializedValue {
            case (upgradeFuture, streamFuture) =>
              (
                ks,
                upgradeFuture
                  .flatMap {
                    case upgrade @ (_: ValidUpgrade) => Future.successful(upgrade)
                    case InvalidUpgradeResponse(_, cause) => Future.failed(new IllegalStateException(cause))
                  }
                  .zip(streamFuture)
              )
          }
      },
      graceful = true
    )
  }
}
