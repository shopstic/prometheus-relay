import sbt._

//noinspection TypeAnnotation
object Dependencies {
  val SCALA_VERSION = "2.12.8"

  val chopsticksFpDeps = Seq(
    "dev.chopsticks" %% "chopsticks-fp" % "0.5.0"
  )

  val prometheusMetricParserDeps = Seq(
    "com.github.pawelj-pl" %% "prometheus_metrics_parser" % "0.2.0"
  )

  val akkaHttpDeps = Seq("akka-http-core", "akka-http").map(p => "com.typesafe.akka" %% p % "10.1.9")

  val refinedDeps = Seq(
    "eu.timepit" %% "refined" % "0.9.8",
    "eu.timepit" %% "refined-pureconfig" % "0.9.8"
  )

  val circeDeps = Seq(
    "circe-core",
    "circe-generic",
    "circe-parser"
  ).map(p => "io.circe" %% p % "0.11.1")
}
