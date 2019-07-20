package com.shopstic.prom_relay

import akka.http.scaladsl.model.Uri
import com.shopstic.prom_relay.PrometheusRelayClients.PrometheusRelayClientConfig
import com.typesafe.config.Config
import dev.chopsticks.fp.AkkaApp
import zio.ZManaged
import scala.concurrent.duration._
import dev.chopsticks.fp.ZIOExt.Implicits._

object SampleClientApp extends AkkaApp {
  type Env = AkkaApp.Env

  protected def createEnv(untypedConfig: Config) = ZManaged.environment[AkkaApp.Env]

  protected def run = {
    for {
      f <- PrometheusRelayClients
        .createClient(
          PrometheusRelayClientConfig(
            originId = "foobar",
            serverUri = Uri("wss://prom-relay.gega3.com"),
            clientMetricsUri = Uri("http://localhost:9095"),
            retryMinBackoff = 500.millis,
            retryMaxBackoff = 10.seconds,
            retryRandomFactor = 0.2,
            idleTimeout = 5.seconds
          )
        )
        .fork

      _ <- f.interrupt.delay(5.seconds)
    } yield ()
  }
}
