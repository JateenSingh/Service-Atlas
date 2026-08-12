package com.acme.logistics.pricing

import cats.effect.{IO, IOApp}
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.Uri

object Main extends IOApp.Simple {

  // FR-3.3: http4s client call against another service
  private val tariffUri = Uri.unsafeFromString("http://log-tariff-svc:8080/tariffs")

  def run: IO[Unit] = EmberClientBuilder.default[IO].build.use { client =>
    client.expect[String](tariffUri).void
  }
}
