package com.shopstic.prom_relay

import akka.http.scaladsl.model.Uri
import com.typesafe.config.Config
import dev.chopsticks.fp.AkkaApp
import zio.ZManaged

object SampleClientApp extends AkkaApp {
  type Env = AkkaApp.Env

  protected def createEnv(untypedConfig: Config) = ZManaged.environment[AkkaApp.Env]

  protected def run = {
    PrometheusRelayClients
      .createClient("foobar", Uri("ws://localhost:8080"), Uri("http://localhost:9095"))
      .unit
  }
}
