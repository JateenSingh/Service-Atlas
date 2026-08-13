name := "log-user-svc"
organization := "com.acme.logistics"
scalaVersion := "2.13.14"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-slick" % "5.3.1",
  "org.postgresql"     % "postgresql" % "42.7.3"
)

