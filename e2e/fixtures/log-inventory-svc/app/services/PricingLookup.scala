package com.acme.logistics.inventory

import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.libs.ws.WSClient
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PricingLookup @Inject() (ws: WSClient, config: Configuration)(
    implicit ec: ExecutionContext
) {
  def price(sku: String): Future[String] =
    ws.url(config.getString("services.pricing.url") + s"/prices/$sku").get().map(_.body)
}
