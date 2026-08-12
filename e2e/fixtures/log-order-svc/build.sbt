name := "log-order-svc"
organization := "com.acme.logistics"
version := "3.2.1"
scalaVersion := "2.13.14"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala)

libraryDependencies ++= Seq(
  guice,
  "com.typesafe.play"    %% "play-ahc-ws"           % "2.9.5",
  "com.typesafe.play"    %% "play-json"             % "2.10.6",

  // Internal service clients (FR-3.1 signals)
  "com.acme.logistics"   %% "log-quote-svc-client"  % "1.4.0",
  "com.acme.logistics"   %% "log-user-svc-client"   % "2.1.0",
  "com.acme.logistics"   %% "log-pricing-svc-client" % "0.9.3",

  // Third-party: must NOT become nodes
  "org.typelevel"        %% "cats-core"             % "2.12.0",
  "ch.qos.logback"        % "logback-classic"       % "1.5.6",
  "org.apache.kafka"      % "kafka-clients"         % "3.7.1",
  "org.scalatest"        %% "scalatest"             % "3.2.19" % Test
)
