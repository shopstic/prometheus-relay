resolvers += Resolver.bintrayRepo("shopstic", "sbt-plugins")

addSbtPlugin("org.foundweekends" % "sbt-bintray" % "0.5.4")
addSbtPlugin("com.shopstic" % "sbt-symlink-target" % "0.0.28")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.22")
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.4.1")
addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.9.2")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.0.2")
addSbtPlugin("org.wartremover" % "sbt-wartremover" % "2.4.2")
