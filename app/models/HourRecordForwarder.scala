package models
import akka.actor.{Actor, actorRef2Scala}
import com.google.inject.assistedinject.Assisted
import play.api.Logger
import play.api.libs.json.{JsError, Json}
import play.api.libs.ws.WSClient

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

object HourRecordForwarder {
  trait Factory {
    def apply(@Assisted("server") server: String, @Assisted("monitor") monitor: String): Actor
  }
}

class HourRecordForwarder @Inject()(ws:WSClient, recordOp: RecordDB)
                                   (@Assisted("server")server: String, @Assisted("monitor")monitor: String) extends Actor {
  val logger: Logger = Logger(this.getClass)
  logger.info(s"HourRecordForwarder created with server=$server monitor=$monitor")
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
            logger.error(JsError.toJson(error).toString())
          },
          latest => {
            logger.info(s"server latest hour: ${new DateTime(latest.time).toString}")
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

  def uploadRecord(latestRecordTime: Long): Unit = {
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
        f onSuccess {
          case response =>
            if (response.status == 200) {
              context become handler(Some(nonEmptyRecords.last._id.time.getTime))

              // This shall stop when there is no more records...
              self ! ForwardHour
            } else {
              logger.error(s"${response.status}:${response.statusText}")
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
  def uploadRecord(start: DateTime, end: DateTime): Unit = {
    logger.info(s"upload hour ${start.toString()} => ${end.toString}")

    val recordFuture = recordOp.getRecordListFuture(recordOp.HourCollection)(start, end)

    for (record <- recordFuture) {
      if (record.nonEmpty) {
        for (chunk <- record.grouped(24)) {
          val url = s"http://$server/HourRecord/$monitor"
          val f = ws.url(url).put(Json.toJson(chunk.filter(_.mtDataList.nonEmpty)))
          f onSuccess {
            case response =>
              if (response.status == 200)
                logger.info("Success upload!")
              else
                logger.error(s"${response.status}:${response.statusText}")
          }
          f onFailure {
            case ex: Throwable =>
              ModelHelper.logException(ex)
          }
        }
      } else
        logger.info(s"No hour record from $start => $end")
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