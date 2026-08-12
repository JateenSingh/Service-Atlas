package com.acme.logistics.quote

import javax.inject.{Inject, Singleton}
import play.api.libs.ws.WSClient
import play.api.mvc.{AbstractController, ControllerComponents}
import scala.concurrent.ExecutionContext

@Singleton
class QuoteController @Inject() (cc: ControllerComponents, ws: WSClient)(
    implicit ec: ExecutionContext
) extends AbstractController(cc) {

  def get(id: String) = Action.async {
    ws.url(s"http://log-pricing-svc:8080/prices/$id").get().map(r => Ok(r.body))
  }

  def create() = Action { Created("{}") }
}
