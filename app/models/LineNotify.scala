package models

import models.ModelHelper.errorHandler
import play.api.Logger
import play.api.libs.json.Json
import play.api.libs.ws.WSClient

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class LineNotify @Inject()(WSClient: WSClient, sysConfigDB: SysConfigDB) {
  val logger: Logger = play.api.Logger(getClass)

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

  def pushMessage(token: String, groupId: String, msg: String): Future[Boolean] = {
    val f = WSClient.url("https://api.line.me/v2/bot/message/push")
      .addHttpHeaders("Authorization" -> s"Bearer $token",
        "Content-Type" -> "application/json")
      .post(Json.obj("to" -> groupId,
        "messages" -> Json.arr(Json.obj("type" -> "text", "text" -> msg))))

    f.failed.foreach(errorHandler)

    for (ret <- f) yield {
      if (ret.status != 200)
        logger.error(ret.body)

      ret.status == 200
    }
  }

  def notify(token: String, msg: String): Future[Boolean] = {
    val f1 = broadcast(token, msg)
    f1.failed.foreach(errorHandler)

    val f2 =
      for (groupIds <- sysConfigDB.getLineChannelGroupId) yield
        Future.sequence(groupIds.map { groupId =>
          pushMessage(token, groupId, msg)
        })
    val f3 = f2.flatten
    Future.sequence(Seq(f1, f3)).map { results =>
      results.forall(_ == true)
    }.recover {
      case e: Exception =>
        logger.error("Error in Line Notify", e)
        false
    }
  }
}
