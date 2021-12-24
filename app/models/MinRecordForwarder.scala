package models

import akka.actor.Actor
import com.github.nscala_time.time.Imports._
import com.google.inject.assistedinject.Assisted
import play.api.Logger
import play.api.libs.json.{JsError, Json}
import play.api.libs.ws.WSClient

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

object MinRecordForwarder {
  trait Factory {
    def apply(@Assisted("server") server: String, @Assisted("monitor") monitor: String): Actor
  }
}

class MinRecordForwarder @Inject()(ws: WSClient, recordOp: RecordOp)
                                  (@Assisted("server") server: String, @Assisted("monitor") monitor: String) extends Actor {
  Logger.info(s"MinRecordForwarder created with server=$server monitor=$monitor")

  import ForwardManager._

  self ! ForwardMin

  def receive = handler(None)

  def checkLatest = {
    val url = s"http://$server/MinRecordRange/$monitor"
    val f = ws.url(url).get().map {
      response =>
        val result = response.json.validate[LatestRecordTime]
        result.fold(
          error => {
            Logger.error(JsError.toJson(error).toString())
          },
          latest => {
            Logger.info(s"server latest min: ${new DateTime(latest.time).toString}")
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

  def uploadRecord(latestRecordTime: Long) {
    val serverRecordStart = new DateTime(latestRecordTime + 1)
    val recordFuture =
      recordOp.getRecordWithLimitFuture(recordOp.MinCollection)(serverRecordStart, DateTime.now, 60)

    for (record <- recordFuture) {
      import recordOp.recordListWrite
      if (!record.isEmpty) {
        val url = s"http://$server/MinRecord/$monitor"
        val f = ws.url(url).put(Json.toJson(record))

        f onSuccess {
          case response =>
            if (response.status == 200) {
              if (record.last.time.getTime > latestRecordTime) {
                context become handler(Some(record.last.time.getTime))
              }
            } else {
              Logger.error(s"${response.status}:${response.statusText}")
              context become handler(None)
            }
        }
        f onFailure {
          case ex: Throwable =>
            context become handler(None)
            ModelHelper.logException(ex)
        }
      }
    }
  }

  def uploadRecord(start: DateTime, end: DateTime) = {
    Logger.info(s"upload min ${start.toString()} => ${end.toString}")

    val recordFuture = recordOp.getRecordListFuture(recordOp.MinCollection)(start, end)
    for (record <- recordFuture) {
      if (!record.isEmpty) {
        Logger.info(s"Total ${record.length} records")
        import recordOp.recordListWrite
        for (chunk <- record.grouped(60)) {
          val url = s"http://$server/MinRecord/$monitor"
          val f = ws.url(url).put(Json.toJson(chunk))

          f onSuccess {
            case response =>
              Logger.info(s"${response.status} : ${response.statusText}")
              Logger.info("Success upload")
          }
          f onFailure {
            case ex: Throwable =>
              ModelHelper.logException(ex)
          }
        }
      } else
        Logger.error("No min record!")

    }
  }

  def handler(latestRecordTimeOpt: Option[Long]): Receive = {
    case ForwardMin =>
      if (latestRecordTimeOpt.isEmpty)
        checkLatest
      else
        uploadRecord(latestRecordTimeOpt.get)

    case ForwardMinRecord(start, end) =>
      uploadRecord(start, end)

  }

}