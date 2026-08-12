package com.acme.logistics.notification

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest

object Main extends App {
  implicit val system: ActorSystem = ActorSystem("notification")

  // FR-3.3: Akka HTTP client call
  Http().singleRequest(HttpRequest(uri = "http://log-template-svc:9000/templates/order-confirmed"))
}
