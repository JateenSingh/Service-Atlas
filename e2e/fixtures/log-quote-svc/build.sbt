name := "log-quote-svc"
organization := "com.acme.logistics"
scalaVersion := "2.13.14"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

libraryDependencies ++= Seq(
  "com.typesafe.play"  %% "play-ahc-ws"            % "2.9.5",
  "com.acme.logistics" %% "log-pricing-svc-client" % "0.9.3",
  "org.scalatest"      %% "scalatest"              % "3.2.19" % Test
)
