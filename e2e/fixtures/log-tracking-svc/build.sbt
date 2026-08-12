name := "log-tracking-svc"
organization := "com.acme.logistics"
scalaVersion := "2.13.14"

libraryDependencies ++= Seq(
  "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % "0.11.17",
  "io.grpc"               % "grpc-netty-shaded"    % "1.65.1"
)
