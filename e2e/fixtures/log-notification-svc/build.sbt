name := "log-notification-svc"
organization := "com.acme.logistics"
scalaVersion := "2.13.14"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-http"          % "10.5.3",
  "com.typesafe.akka" %% "akka-stream"        % "2.8.5",
  "com.acme.logistics" %% "log-user-svc-client" % "2.1.0"
)
