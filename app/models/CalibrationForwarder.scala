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
    def apply(@Assisted("server") server: String, @Assisted("monitor") monitor: String): Actor
  }
}

class CalibrationForwarder @Inject()
(ws: WSClient, calibrationOp: CalibrationDB)
(@Assisted("server") server: String, @Assisted("monitor") monitor: String) extends Actor {
  val logger: Logger = Logger(this.getClass)

  import ForwardManager._
  import calibrationOp._

  logger.info(s"CalibrationForwarder started $server/$monitor")

  def receive = handler(None)

  def checkLatest = {
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

            context become handler(Some(serverLatest.getMillis))
            uploadCalibration(serverLatest.getMillis)
          })
    }
    FiniteDuration(1, SECONDS)
  }

  def uploadCalibration(latestCalibration: Long) = {
    val recordFuture = calibrationOp.calibrationReportFuture(new DateTime(latestCalibration + 1))
    for (records <- recordFuture) {
      if (records.nonEmpty) {
        val url = s"http://$server/CalibrationRecord/$monitor"
        val f = ws.url(url).put(Json.toJson(records))
        f.foreach(_=> context become handler(Some(records.last.startTime.getTime)))
        f.failed.foreach(ex => {
          context become handler(None)
          ModelHelper.logException(ex)
        })
      }
    }
  }

  def handler(latestCalibrationOpt: Option[Long]): Receive = {
    case ForwardCalibration =>
      try {
        if (latestCalibrationOpt.isEmpty)
          checkLatest
        else
          uploadCalibration(latestCalibrationOpt.get)
      } catch {
        case ex: Throwable =>
          ModelHelper.logException(ex)
          context become handler(None)
      }
  }
}