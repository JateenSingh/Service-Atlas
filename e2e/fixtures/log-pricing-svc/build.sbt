name := "log-pricing-svc"
organization := "com.acme.logistics"
scalaVersion := "2.13.14"

libraryDependencies ++= Seq(
  "org.http4s" %% "http4s-ember-server" % "0.23.27",
  "org.http4s" %% "http4s-ember-client" % "0.23.27",
  "org.http4s" %% "http4s-circe"        % "0.23.27"
)
