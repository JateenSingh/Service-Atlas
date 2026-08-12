package com.acme.logistics.user

import javax.inject.{Inject, Singleton}
import play.api.mvc.{AbstractController, ControllerComponents}

@Singleton
class UserController @Inject() (cc: ControllerComponents) extends AbstractController(cc) {
  def get(id: String) = Action { Ok("{}") }
  def search(email: Option[String]) = Action { Ok("[]") }
}
