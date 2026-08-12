package controllers

import com.acme.logistics.quote.QuoteClient
import com.acme.logistics.user.UserClient
import javax.inject.{Inject, Singleton}
import play.api.mvc.{AbstractController, ControllerComponents}

@Singleton
class OrderController @Inject() (
    cc: ControllerComponents,
    quotes: QuoteClient,
    users: UserClient
) extends AbstractController(cc) {

  def list(page: Int) = Action { Ok("[]") }
  def get(id: String) = Action { Ok("{}") }
  def create() = Action { Created("{}") }
  def updateStatus(id: String) = Action { Ok("{}") }
  def cancel(id: String) = Action { NoContent }
}
