ThisBuild / organization := "com.acme.logistics"
ThisBuild / scalaVersion := "2.13.14"

lazy val root = (project in file("."))
  .aggregate(api, domain)
  .settings(name := "log-shipment-svc")

lazy val api = (project in file("modules/api"))
  .enablePlugins(PlayScala)
  .dependsOn(domain)
  .settings(
    name := "log-shipment-api",
    libraryDependencies ++= Seq(
      "com.typesafe.play"  %% "play-ahc-ws"          % "2.9.5",
      "com.acme.logistics" %% "log-quote-svc-client" % "1.4.0"
    )
  )

lazy val domain = (project in file("modules/domain"))
  .settings(
    name := "log-shipment-domain",
    libraryDependencies += "org.typelevel" %% "cats-core" % "2.12.0"
  )
