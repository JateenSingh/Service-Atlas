name := "log-inventory-svc"
organization := "com.acme.logistics"
scalaVersion := "2.13.14"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-ahc-ws" % "2.9.5"
)
