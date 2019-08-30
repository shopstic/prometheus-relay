package com.shopstic.prom_relay

import java.util.concurrent.TimeUnit

import akka.http.scaladsl.model.Uri
import com.shopstic.prom_relay.PrometheusRelayClients.EnabledPrometheusRelayConfig
import com.typesafe.config.Config
import dev.chopsticks.fp.AkkaApp
import zio.ZManaged

import scala.concurrent.duration._

object SampleClientApp extends AkkaApp {
  type Env = AkkaApp.Env

  protected def createEnv(untypedConfig: Config) = ZManaged.environment[AkkaApp.Env]

  protected def run = {
    for {
      f <- PrometheusRelayClients
        .createClient(
          EnabledPrometheusRelayConfig(
            originId = "foobar",
            serverUri = Uri("ws://localhost:8080"),
            clientMetricsUris = List(Uri("http://localhost:9095")),
            retryMinBackoff = 500.millis,
            retryMaxBackoff = 10.seconds,
            retryRandomFactor = 0.2,
            idleTimeout = 5.seconds
          )
        )
        .fork

      _ <- f.interrupt.delay(zio.duration.Duration(5, TimeUnit.SECONDS))
    } yield ()
  }
}
