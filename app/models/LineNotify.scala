package models

import models.ModelHelper.errorHandler
import play.api.Logger
import play.api.libs.json.Json
import play.api.libs.ws.WSClient

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class LineNotify @Inject()(WSClient: WSClient) {
  val logger: Logger = play.api.Logger(getClass)

  @deprecated("Use broadcast instead", "2.0")
  def notify(token: String, msg: String): Future[Boolean] = {
    val f = WSClient.url("https://notify-api.line.me/api/notify")
      .addHttpHeaders("Authorization" -> s"Bearer $token",
        "Content-Type" -> "application/x-www-form-urlencoded")
      .post(Map("message" -> Seq(msg)))

    for (ret <- f) yield {
      if (ret.status != 200)
        logger.error(ret.body)

      ret.status == 200
    }
  }

  def broadcast(token: String, msg: String): Future[Boolean] = {
    val f = WSClient.url("https://api.line.me/v2/bot/message/broadcast")
      .addHttpHeaders("Authorization" -> s"Bearer $token",
        "Content-Type" -> "application/json")
      .post(Json.obj("messages" -> Json.arr(Json.obj("type" -> "text", "text" -> msg))))

    f.failed.foreach(errorHandler)

    for (ret <- f) yield {
      if (ret.status != 200)
        logger.error(ret.body)

      ret.status == 200
    }
  }
}
