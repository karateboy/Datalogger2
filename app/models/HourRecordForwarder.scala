package models
import com.google.inject.assistedinject.Assisted
import play.api.libs.ws.WSClient

import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor.{Actor, actorRef2Scala}
import models.mongodb.RecordOp
import play.api.Logger
import play.api.libs.json.{JsError, Json}

import javax.inject._

object HourRecordForwarder {
  trait Factory {
    def apply(@Assisted("server") server: String, @Assisted("monitor") monitor: String): Actor
  }
}

class HourRecordForwarder @Inject()(ws:WSClient, recordOp: RecordDB)
                                   (@Assisted("server")server: String, @Assisted("monitor")monitor: String) extends Actor {
  Logger.info(s"HourRecordForwarder created with server=$server monitor=$monitor")
  import ForwardManager._
  self ! ForwardHour
  def receive = handler(None)
  def checkLatest = {
    import com.github.nscala_time.time.Imports._
    val url = s"http://$server/HourRecordRange/$monitor"
    val f = ws.url(url).get().map {
      response =>
        val result = response.json.validate[LatestRecordTime]
        result.fold(
          error => {
            Logger.error(JsError.toJson(error).toString())
          },
          latest => {
            Logger.info(s"server latest hour: ${new DateTime(latest.time).toString}")
            val serverLatest =
              if (latest.time == 0) {
                DateTime.now() - 1.day
              } else {
                new DateTime(latest.time)
              }

            context become handler(Some(serverLatest.getMillis))
            uploadRecord(serverLatest.getMillis)
          })
    }
    f onFailure {
      case ex: Throwable =>
        ModelHelper.logException(ex)
    }
  }

  def uploadRecord(latestRecordTime: Long) = {
    import com.github.nscala_time.time.Imports._
    val recordFuture =
      recordOp.getRecordWithLimitFuture(recordOp.HourCollection)(new DateTime(latestRecordTime + 1), DateTime.now, 60)

    Logger.info(s"uploadRecord from ${new DateTime(latestRecordTime + 1)} to ${DateTime.now}")
    for (records <- recordFuture) {
      Logger.info(s"total ${records.size} hour records")
      val nonEmptyRecords = records.filter(_.mtDataList.nonEmpty)
      if (nonEmptyRecords.nonEmpty) {
        val url = s"http://$server/HourRecord/$monitor"
        val f = ws.url(url).put(Json.toJson(nonEmptyRecords))
        f onSuccess {
          case response =>
            if (response.status == 200) {
              context become handler(Some(nonEmptyRecords.last._id.time.getTime))

              // This shall stop when there is no more records...
              self ! ForwardHour
            } else {
              Logger.error(s"${response.status}:${response.statusText}")
              context become handler(None)
              delayForward
            }
        }
        f onFailure {
          case ex: Throwable =>
            context become handler(None)
            ModelHelper.logException(ex)
            delayForward
        }
      }
    }
  }

  def delayForward = {
    val currentMin = {
      import com.github.nscala_time.time.Imports._
      val now = DateTime.now()
      now.getMinuteOfHour
    }
    import scala.concurrent.duration._
    import play.api.libs.concurrent.Akka

    if (currentMin < 58)
      context.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ForwardHour)
  }

  import com.github.nscala_time.time.Imports._
  def uploadRecord(start: DateTime, end: DateTime) = {
    Logger.info(s"upload hour ${start.toString()} => ${end.toString}")

    val recordFuture = recordOp.getRecordListFuture(recordOp.HourCollection)(start, end)

    for (record <- recordFuture) {
      if (record.nonEmpty) {
        for (chunk <- record.grouped(24)) {
          val url = s"http://$server/HourRecord/$monitor"
          val f = ws.url(url).put(Json.toJson(chunk.filter(_.mtDataList.nonEmpty)))
          f onSuccess {
            case response =>
              if (response.status == 200)
                Logger.info("Success upload!")
              else
                Logger.error(s"${response.status}:${response.statusText}")
          }
          f onFailure {
            case ex: Throwable =>
              ModelHelper.logException(ex)
          }
        }
      } else
        Logger.info("No more hour record")
    }
  }
  def handler(latestRecordTimeOpt: Option[Long]): Receive = {
    case ForwardHour =>
      if (latestRecordTimeOpt.isEmpty)
        checkLatest
      else
        uploadRecord(latestRecordTimeOpt.get)

    case ForwardHourRecord(start, end) =>
      uploadRecord(start, end)

  }
}