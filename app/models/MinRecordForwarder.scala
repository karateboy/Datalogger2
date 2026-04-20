package models

import akka.actor.Actor
import com.github.nscala_time.time.Imports._
import com.google.inject.assistedinject.Assisted
import models.ModelHelper.errorHandler
import play.api.Logger
import play.api.libs.json.{JsError, Json}
import play.api.libs.ws.WSClient

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{FiniteDuration, SECONDS}
import scala.util.{Failure, Success}

object MinRecordForwarder {
  trait Factory {
    def apply(@Assisted("server") server: String, @Assisted("monitors") monitors: Seq[String]): Actor
  }

  val logger: Logger = Logger(this.getClass)
}

class MinRecordForwarder @Inject()(ws: WSClient, recordOp: RecordDB)
                                  (@Assisted("server") server: String, @Assisted("monitors") monitors: Seq[String]) extends Actor {
  val logger: Logger = Logger(this.getClass)
  logger.info(s"MinRecordForwarder created with server=$server monitor=${monitors.mkString(",")}")

  import ForwardManager._

  self ! ForwardMin

  def receive: Receive = handler(Map.empty)

  def checkLatest(monitor:String)(monitorLatestMap:Map[String, Long]): Unit = {
    val url = s"http://$server/MinRecordRange/$monitor"
    val f = ws.url(url).get().map {
      response =>
        val result = response.json.validate[LatestRecordTime]
        result.fold(
          error => {
            logger.error(JsError.toJson(error).toString())
          },
          latest => {
            logger.info(s"server latest min: ${new DateTime(latest.time).toString}")
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

    val serverRecordStart = new DateTime(latestRecordTime + 1)
    val recordFuture =
      recordOp.getRecordWithLimitFuture(recordOp.MinCollection)(serverRecordStart, DateTime.now, 60)

    for (records <- recordFuture) {
      val postUrl = s"http://$server/Record/Min/$monitor"
      if (records.nonEmpty) {
        val f =
          ws.url(postUrl).withRequestTimeout(FiniteDuration(10, SECONDS)).post(Json.toJson(records.filter(_.mtDataList.nonEmpty)))


        f onComplete {
          case Success(response) =>
            if (response.status == 200) {
              if (records.last._id.time.getTime > latestRecordTime)
                context become handler(monitorLatestMap + (monitor->records.last._id.time.getTime))
            } else {
              logger.error(s"${response.status}:${response.statusText}")
              context become handler(monitorLatestMap - monitor)
            }
          case Failure(exception) =>
            context become handler(monitorLatestMap - monitor)
            errorHandler(exception)
        }
      }
    }
  }

  def uploadRecord(monitor:String, start: DateTime, end: DateTime): Unit = {
    val recordFuture = recordOp.getRecordListFuture(recordOp.MinCollection)(start, end)
    for (record <- recordFuture if record.nonEmpty) {

      logger.info(s"upload min ${start.toString()} => ${end.toString}")
      logger.info(s"Total ${record.length} records")
      val postUrl = s"http://$server/Record/Min/$monitor"
      for (chunk <- record.grouped(60)) {
        val f = ws.url(postUrl).post(Json.toJson(chunk.filter(_.mtDataList.nonEmpty)))
        f.foreach(response => {
          logger.info(s"${response.status} : ${response.statusText}")
          logger.info("Success upload")
        })
        f.failed.foreach(errorHandler)
      }
    }
  }

  def handler(monitorLatestMap:Map[String, Long]): Receive = {
    case ForwardMin =>
      monitors.foreach(monitor=>{
        if (monitorLatestMap.contains(monitor))
          uploadRecord(monitor, monitorLatestMap(monitor))(monitorLatestMap)
        else
          checkLatest(monitor)(monitorLatestMap)
      })

    case ForwardMinRecord(start, end) =>
      monitors.foreach(monitor=>{
        uploadRecord(monitor, start, end)
      })

  }

}