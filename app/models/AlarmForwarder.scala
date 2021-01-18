package models

import akka.actor.Actor
import com.github.nscala_time.time.Imports._
import play.api.Logger
import play.api.libs.json.{JsError, Json}
import play.api.libs.ws.WSClient

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

class AlarmForwarder @Inject()(alarmOp: AlarmOp, ws: WSClient)(server: String, monitor: String) extends Actor {
  import ForwardManager._
  def receive = handler(None)
  def checkLatest = {
    val url = s"http://$server/AlarmRecordRange/$monitor"
    val f = ws.url(url).get().map {
      response =>
        val result = response.json.validate[LatestRecordTime]
        result.fold(
          error => {
            Logger.error(JsError.toJson(error).toString())
          },
          latest => {
            Logger.info(s"server latest alarm: ${new DateTime(latest.time).toString}")
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
    val recordFuture = alarmOp.getAlarmsFuture(new DateTime(latestAlarm + 1), DateTime.now)
    for (records <- recordFuture) {
      if (!records.isEmpty) {
        val recordJSON = records.map { _.toJson }
        val url = s"http://$server/AlarmRecord/$monitor"
        import alarmOp.jsonWrite
        val f = ws.url(url).put(Json.toJson(recordJSON))
        f onSuccess {
          case response =>
            context become handler(Some(records.last.time.getMillis))
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