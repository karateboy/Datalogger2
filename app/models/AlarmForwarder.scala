package models

import akka.actor.Actor
import com.github.nscala_time.time.Imports._
import com.google.inject.assistedinject.Assisted
import play.api.Logger
import play.api.libs.json.{JsError, Json}
import play.api.libs.ws.WSClient

import java.time.Instant
import java.util.Date
import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

object AlarmForwarder {
  trait Factory {
    def apply(@Assisted("server") server: String, @Assisted("monitor") monitor: String): Actor
  }
}

class AlarmForwarder @Inject()(alarmOp: AlarmDB, ws: WSClient)
  (@Assisted("server") server: String, @Assisted("monitor") monitor: String) extends Actor {
  val logger: Logger = Logger(this.getClass)
  import ForwardManager._

  logger.info(s"AlarmForwarder started $server/$monitor")

  def receive = handler(None)

  def checkLatest = {
    val url = s"http://$server/AlarmRecordRange/$monitor"
    val f = ws.url(url).get().map {
      response =>
        val result = response.json.validate[LatestRecordTime]
        result.fold(
          error => {
            logger.error(JsError.toJson(error).toString())
          },
          latest => {
            logger.info(s"server latest alarm: ${new DateTime(latest.time).toString}")
            val serverLatest =
              if (latest.time == 0) {
                DateTime.now() - 1.day
              } else {
                new DateTime(latest.time)
              }

            context become handler(Some(serverLatest.getMillis))
            uploadAlarm(serverLatest.getMillis)
          })
    }
    f onFailure {
      case ex: Throwable =>
        ModelHelper.logException(ex)
    }
  }

  def uploadAlarm(latestAlarm: Long) = {
    import alarmOp.write
    val recordFuture = alarmOp.getAlarmsFuture(Date.from(Instant.ofEpochMilli(latestAlarm + 1)), new Date())
    for (alarms <- recordFuture) {
      if (alarms.nonEmpty) {
        val url = s"http://$server/AlarmRecord/$monitor"
        val f = ws.url(url).put(Json.toJson(alarms))
        f onSuccess {
          case _ =>
            context become handler(Some(alarms.last.time.getTime))
        }
        f onFailure {
          case ex: Throwable =>
            context become handler(None)
            ModelHelper.logException(ex)
        }
      }
    }
  }

  def handler(latestAlarmOpt: Option[Long]): Receive = {
    case ForwardAlarm =>
      if (latestAlarmOpt.isEmpty)
        checkLatest
      else
        uploadAlarm(latestAlarmOpt.get)
  }
}