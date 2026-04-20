package models

import akka.actor.Actor
import com.github.nscala_time.time.Imports._
import com.google.inject.assistedinject.Assisted
import models.ModelHelper.errorHandler
import play.api.Logger
import play.api.libs.json.{JsError, Json}
import play.api.libs.ws.WSClient

import java.time.Instant
import java.util.Date
import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

object AlarmForwarder {
  trait Factory {
    def apply(@Assisted("server") server: String, @Assisted("monitors") monitors: Seq[String]): Actor
  }
}

class AlarmForwarder @Inject()(alarmOp: AlarmDB, ws: WSClient)
                              (@Assisted("server") server: String, @Assisted("monitors") monitors: Seq[String]) extends Actor {
  val logger: Logger = Logger(this.getClass)

  import ForwardManager._

  logger.info(s"AlarmForwarder started $server/monitor=${monitors.mkString(",")}")

  def receive: Receive = handler(Map.empty)

  def checkLatest(monitor:String)(monitorLatestMap:Map[String, Long]): Unit = {
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

            context become handler(monitorLatestMap + (monitor->serverLatest.getMillis))
            uploadAlarm(monitor, serverLatest.getMillis)(monitorLatestMap:Map[String, Long])
          })
    }
    f.failed.foreach(errorHandler)
  }

  private def uploadAlarm(monitor:String, latestAlarm: Long)(monitorLatestMap:Map[String, Long]): Unit = {
    import alarmOp.write
    val recordFuture = alarmOp.getAlarmsFuture(Date.from(Instant.ofEpochMilli(latestAlarm + 1)), new Date())
    for (alarms <- recordFuture) {
      if (alarms.nonEmpty) {
        val url = s"http://$server/AlarmRecord/$monitor"
        val f = ws.url(url).put(Json.toJson(alarms))
        f.foreach(_ => context become handler(monitorLatestMap + (monitor->alarms.last.time.getTime)))
        f.failed.foreach(ex => {
          context become handler(monitorLatestMap - monitor)
          ModelHelper.logException(ex)
        })
      }
    }
  }

  def handler(monitorLatestMap:Map[String, Long]): Receive = {
    case ForwardAlarm =>
      monitors.foreach(monitor=>{
        if (monitorLatestMap.contains(monitor))
          uploadAlarm(monitor, monitorLatestMap(monitor))(monitorLatestMap)
        else
          checkLatest(monitor)(monitorLatestMap)
      })
  }
}