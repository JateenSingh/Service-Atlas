package services

import javax.inject.{Inject, Singleton}
import play.api.libs.ws.WSClient
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AuditClient @Inject() (ws: WSClient)(implicit ec: ExecutionContext) {

  private val baseUrl = "http://log-audit-svc:9000"

  def record(event: String): Future[Unit] =
    ws.url(s"$baseUrl/audit/events").post(event).map(_ => ())
}
