import Dependencies._

ThisBuild / organization := "com.shopstic"
ThisBuild / scalaVersion := SCALA_VERSION
ThisBuild / javacOptions ++= Build.javacOptions
ThisBuild / scalacOptions ++= Build.scalacOptions
ThisBuild / symlinkTargetRoot := Build.symlinkTargetRoot
ThisBuild / licenses += ("Apache-2.0", url("http://www.apache.org/licenses/"))
ThisBuild / resolvers += Resolver.bintrayRepo("shopstic", "maven")

lazy val client = Build
  .defineProject("client")
  .settings(
    libraryDependencies ++= chopsticksFpDeps ++ akkaHttpDeps
  )

lazy val server = Build
  .defineProject("server")
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    libraryDependencies ++= chopsticksFpDeps ++ akkaHttpDeps ++ prometheusMetricParserDeps ++ refinedDeps,
//    dockerBaseImage := "oracle/graalvm-ce:19.1.0",
//    dockerBaseImage := "adoptopenjdk:11.0.4_11-jre-hotspot",
    dockerBaseImage := "openjdk:11-jre-slim",
    dockerUsername := Some("shopstic"),
    dockerExposedPorts := Seq(8080),
    daemonUser in Docker := "app",
    dockerEntrypoint := Seq("/opt/docker/bin/prometheus-relay-server")
  )

lazy val sample = Build
  .defineProject("sample")
  .dependsOn(client)

lazy val root = (project in file("."))
  .enablePlugins(SymlinkTargetPlugin)
  .settings(
    name := Build.rootProjectName,
    dependencyUpdates := {},
    publish / skip := true
  )
  .aggregate(server, client)
