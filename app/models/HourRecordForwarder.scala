package models

import akka.actor.Actor
import com.google.inject.assistedinject.Assisted
import models.ModelHelper.errorHandler
import play.api.Logger
import play.api.libs.json.{JsError, Json, OWrites}
import play.api.libs.ws.WSClient

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

object HourRecordForwarder {
  trait Factory {
    def apply(@Assisted("server") server: String, @Assisted("monitors") monitors: Seq[String]): Actor
  }

  case class TsmcMtRecord(mtName: String, value: Double, status: String)
  case class TsmcRecordList(time: Long, mtDataList: Seq[TsmcMtRecord])

  implicit val write1: OWrites[TsmcMtRecord] = Json.writes[TsmcMtRecord]
  implicit val write: OWrites[TsmcRecordList] = Json.writes[TsmcRecordList]
}

class HourRecordForwarder @Inject()(ws: WSClient, recordOp: RecordDB)
                                   (@Assisted("server") server: String, @Assisted("monitors") monitors: Seq[String]) extends Actor {
  val logger: Logger = Logger(this.getClass)
  logger.info(s"HourRecordForwarder created with server=$server monitor=${monitors.mkString(",")}")

  import ForwardManager._

  self ! ForwardHour

  def receive: Receive = handler(Map.empty)

  private def delayForward = {
    val currentMin = {
      import com.github.nscala_time.time.Imports._
      val now = DateTime.now()
      now.getMinuteOfHour
    }
    import scala.concurrent.duration._

    if (currentMin < 58)
      context.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ForwardHour)
  }

  import com.github.nscala_time.time.Imports._

  def checkLatest(monitor:String)(monitorLatestMap:Map[String, Long]): Unit = {
    import com.github.nscala_time.time.Imports._
    val url = s"http://$server/HourRecordRange/$monitor"
    val f = ws.url(url).get().map {
      response =>
        val result = response.json.validate[LatestRecordTime]
        result.fold(
          error => {
            logger.error(JsError.toJson(error).toString())
          },
          latest => {
            logger.info(s"server $monitor latest hour: ${new DateTime(latest.time).toString}")
            val serverLatest =
              if (latest.time == 0) {
                DateTime.now() - 1.day
              } else {
                new DateTime(latest.time)
              }

            context become handler(monitorLatestMap + (monitor->serverLatest.getMillis))
            uploadRecord(monitor, serverLatest.getMillis)(monitorLatestMap:Map[String, Long])
          })
    }
    f.failed.foreach(errorHandler)
  }

  def uploadRecord(monitor:String, latestRecordTime: Long)(monitorLatestMap:Map[String, Long]): Unit = {
    import com.github.nscala_time.time.Imports._
    val recordFuture =
      recordOp.getRecordWithLimitFuture(recordOp.HourCollection)(new DateTime(latestRecordTime + 1), DateTime.now, 144)

    for (records <- recordFuture) {
      val nonEmptyRecords = records.filter(_.mtDataList.nonEmpty)
      if (nonEmptyRecords.nonEmpty) {
        logger.info(s"uploadRecord from ${new DateTime(latestRecordTime + 1)} => ${DateTime.now}")
        logger.info(s"total ${records.size} hour records")
        val url = s"http://$server/HourRecord/$monitor"

        val f = ws.url(url).put(Json.toJson(nonEmptyRecords))
        f.foreach(response =>
          if (response.status == 200) {
            context become handler(monitorLatestMap + (monitor->nonEmptyRecords.last._id.time.getTime))
            // This shall stop when there is no more records...
            self ! ForwardHour
          } else {
            logger.error(s"${response.status}:${response.statusText}")
            context become handler(monitorLatestMap - monitor)
            delayForward
          })
        f.failed.foreach(ex => {
          context become handler(monitorLatestMap - monitor)
          ModelHelper.logException(ex)
          delayForward
        })
      }
    }
  }


  def uploadRecord(monitor:String, start: DateTime, end: DateTime): Unit = {
    logger.info(s"upload hour ${start.toString()} => ${end.toString}")

    val recordFuture = recordOp.getRecordListFuture(recordOp.HourCollection)(start, end)

    for (record <- recordFuture if record.nonEmpty) {
      for (chunk <- record.grouped(24)) {
        val url = s"http://$server/HourRecord/$monitor"
        val f = ws.url(url).put(Json.toJson(chunk.filter(_.mtDataList.nonEmpty)))
        f.failed.foreach(errorHandler)
      }
    }
  }

  def handler(monitorLatestMap:Map[String, Long]): Receive = {
    case ForwardHour =>
      monitors.foreach(monitor=>{
        if (monitorLatestMap.contains(monitor))
          uploadRecord(monitor, monitorLatestMap(monitor))(monitorLatestMap)
        else
          checkLatest(monitor)(monitorLatestMap)
      })


    case ForwardHourRecord(start, end) =>
      monitors.foreach(monitor=>{
        uploadRecord(monitor, start, end)
      })
  }
}