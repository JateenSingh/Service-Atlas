package services

import javax.inject.{Inject, Singleton}
import play.api.libs.ws.WSClient
import scala.concurrent.{ExecutionContext, Future}

/** Fire-and-forget notification dispatch. */
@Singleton
class NotificationClient @Inject() (ws: WSClient)(implicit ec: ExecutionContext) {

  // FR-3.3: literal host in an HTTP client call
  def notifyCustomer(orderId: String): Future[Unit] =
    ws.url(s"http://log-notification-svc:9000/notifications/$orderId")
      .withRequestTimeout(scala.concurrent.duration.Duration(5, "seconds"))
      .post("{}")
      .map(_ => ())

  // Commented-out call: must NOT produce an edge
  // ws.url("http://log-deprecated-svc:9000/legacy").get()
}
