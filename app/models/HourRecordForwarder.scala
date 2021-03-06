package models
import akka.actor.ActorSystem
import play.api.libs.concurrent.Akka
import play.api.libs.ws.WSClient

import scala.concurrent.ExecutionContext.Implicits.global
//
import akka.actor.Actor
import akka.actor.actorRef2Scala
import play.api.Logger

import play.api.libs.json.JsError
import play.api.libs.json.Json

import javax.inject._

class HourRecordForwarder @Inject()(ws:WSClient, recordOp: RecordOp, system: ActorSystem)(server: String, monitor: String) extends Actor {
  import ForwardManager._
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

    import recordOp.recordListWrite
    for (record <- recordFuture) {
      if (!record.isEmpty) {
        val url = s"http://$server/HourRecord/$monitor"
        val f = ws.url(url).put(Json.toJson(record))
        f onSuccess {
          case response =>
            if (response.status == 200) {
              context become handler(Some(record.last.time.getTime))

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
      system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ForwardHour)
  }

  import com.github.nscala_time.time.Imports._
  def uploadRecord(start: DateTime, end: DateTime) = {
    Logger.info(s"upload hour ${start.toString()} => ${end.toString}")

    val recordFuture = recordOp.getRecordListFuture(recordOp.HourCollection)(start, end)
    import recordOp.recordListWrite
    for (record <- recordFuture) {
      if (!record.isEmpty) {
        for (chunk <- record.grouped(24)) {
          val url = s"http://$server/HourRecord/$monitor"
          val f = ws.url(url).put(Json.toJson(chunk))
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