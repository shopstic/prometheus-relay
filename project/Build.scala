import com.shopstic.sbt.SymlinkTargetPlugin
import com.timushev.sbt.updates.UpdatesPlugin.autoImport._
import org.scalafmt.sbt.ScalafmtPlugin.autoImport._
import wartremover.WartRemover.autoImport._
import sbt._
import sbt.Keys._

//noinspection TypeAnnotation
object Build {
  val rootProjectName = "prometheus-relay"
  val buildVersion = "0.2.0"

  val symlinkTargetRoot = file(sys.env("HOME")) / ".sbt-targets" / "shopstic" / "prometheus-relay"

  val scalacLintingOptions = Seq(
    "-deprecation", // Emit warning and location for usages of deprecated APIs.
    "-explaintypes", // Explain type errors in more detail.
    "-Xcheckinit", // Wrap field accessors to throw an exception on uninitialized access.
    "-Xlint:_",
    "-Yno-adapted-args", // Do not adapt an argument list (either by inserting () or creating a tuple) to match the receiver.
    "-Ypartial-unification", // Enable partial unification in type constructor inference
    "-Ywarn-dead-code", // Warn when dead code is identified.
    "-Ywarn-extra-implicit", // Warn when more than one implicit parameter section is defined.
    "-Ywarn-inaccessible", // Warn about inaccessible types in method signatures.
    "-Ywarn-infer-any", // Warn when a type argument is inferred to be `Any`.
    "-Ywarn-nullary-override", // Warn when non-nullary `def f()' overrides nullary `def f'.
    "-Ywarn-nullary-unit", // Warn when nullary methods return Unit.
    "-Ywarn-numeric-widen", // Warn when numerics are widened.
    "-Ywarn-unused:_",
    "-Ywarn-value-discard", // Warn when non-Unit expression results are unused.
    "-Ywarn-macros:after"
  ) ++ scala.sys.env.get("FATAL_WARNINGS").map(_ => Seq("-Xfatal-warnings")).getOrElse(Seq.empty[String])

  val javacOptions = Seq("-encoding", "UTF-8")
  val scalacOptions = Seq(
    "-unchecked",
    "-feature",
    "-encoding",
    "utf-8",
    "-Xfuture",
    "-Ycache-plugin-class-loader:last-modified",
    "-Ycache-macro-class-loader:last-modified",
    "-Ybackend-parallelism",
    Math.min(16, java.lang.Runtime.getRuntime.availableProcessors()).toString
  ) ++ scala.sys.env
    .get("SCALAC_OPTIMIZE")
    .map(_.split(" ").toVector)
    .getOrElse(Vector.empty[String]) ++ scalacLintingOptions

  lazy val cq = taskKey[Unit]("Code quality")
  lazy val fmt = taskKey[Unit]("Code formatting")

  def defineProject(projectName: String) = {
    Project(projectName, file(s"$rootProjectName-$projectName"))
      .enablePlugins(SymlinkTargetPlugin)
      .settings(
        name := s"$rootProjectName-$projectName",
        version := buildVersion,
        Build.cq := {
          (Compile / scalafmtCheck).value
          (Test / scalafmtCheck).value
          (Compile / scalafmtSbtCheck).value
        },
        Build.fmt := {
          (Compile / scalafmt).value
          (Test / scalafmt).value
          (Compile / scalafmtSbt).value
        },
        Compile / compile / wartremoverErrors ++= Seq(
          Wart.AnyVal,
          Wart.JavaSerializable,
          Wart.FinalCaseClass,
          Wart.FinalVal,
          Wart.JavaConversions,
          Wart.LeakingSealed,
          Wart.NonUnitStatements,
          Wart.Product
        ),
        wartremoverExcluded += sourceManaged.value,
        dependencyUpdatesFilter -= moduleFilter(organization = "org.scala-lang"),
        Compile / doc / sources := Seq.empty,
        Compile / packageDoc / publishArtifact := false
      )
  }
}
