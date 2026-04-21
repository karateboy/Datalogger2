package models

import akka.actor.Actor
import com.github.nscala_time.time.Imports._
import com.google.inject.assistedinject.Assisted
import play.api.Logger
import play.api.libs.json.{JsError, Json}
import play.api.libs.ws.WSClient

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{FiniteDuration, SECONDS}

object CalibrationForwarder {
  trait Factory {
    def apply(@Assisted("server") server: String, @Assisted("monitors") monitors: Seq[String]): Actor
  }
}

class CalibrationForwarder @Inject()
(ws: WSClient, calibrationOp: CalibrationDB)
(@Assisted("server") server: String, @Assisted("monitors") monitors: Seq[String]) extends Actor {
  val logger: Logger = Logger(this.getClass)

  import ForwardManager._
  import calibrationOp._

  logger.info(s"CalibrationForwarder started $server monitor=${monitors.mkString(",")}")

  def receive: Receive = handler(Map.empty)

  def checkLatest(monitor:String)(monitorLatestMap:Map[String, Long]): Unit = {
    val url = s"http://$server/CalibrationRecordRange/$monitor"
    val f = ws.url(url).get().map {
      response =>
        val result = response.json.validate[LatestRecordTime]
        result.fold(
          error => {
            logger.error(JsError.toJson(error).toString())
          },
          latest => {
            logger.info(s"server latest calibration: ${new DateTime(latest.time).toString}")
            val serverLatest =
              if (latest.time == 0) {
                DateTime.now() - 1.day
              } else {
                new DateTime(latest.time)
              }

            val newMap = monitorLatestMap + (monitor->serverLatest.getMillis)
            context become handler(newMap)
            uploadCalibration(monitor, serverLatest.getMillis)(newMap)
          })
    }
  }

  private def uploadCalibration(monitor:String, latestCalibration: Long)(monitorLatestMap:Map[String, Long]): Unit = {
    val recordFuture = calibrationOp.calibrationReportFuture(new DateTime(latestCalibration + 1))
    for (records <- recordFuture) {
      if (records.nonEmpty) {
        val url = s"http://$server/CalibrationRecord/$monitor"
        val f = ws.url(url).put(Json.toJson(records))
        f.foreach(_=> context become handler(monitorLatestMap + (monitor->records.last.startTime.getTime)))
        f.failed.foreach(ex => {
          context become handler(monitorLatestMap - monitor)
          ModelHelper.logException(ex)
        })
      }
    }
  }

  def handler(monitorLatestMap:Map[String, Long]): Receive = {
    case ForwardCalibration =>

        monitors.foreach(monitor=>{
          try{
            if (monitorLatestMap.contains(monitor))
              uploadCalibration(monitor, monitorLatestMap(monitor))(monitorLatestMap)
            else
              checkLatest(monitor)(monitorLatestMap)
          }catch {
            case ex: Throwable =>
              ModelHelper.logException(ex)
              context become handler(monitorLatestMap - monitor)
          }
        })

  }
}