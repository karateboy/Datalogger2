package models

import akka.actor.Actor
import com.github.nscala_time.time.Imports.DateTime
import com.google.inject.assistedinject.Assisted
import models.ModelHelper.errorHandler
import play.api.Logger
import play.api.libs.json.{JsError, Json}
import play.api.libs.ws.WSClient

import java.util.Date
import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

object InstrumentStatusForwarder {
  trait Factory {
    def apply(@Assisted("server") server: String, @Assisted("monitors") monitors: Seq[String]): Actor
  }
}

class InstrumentStatusForwarder @Inject()(ws: WSClient, instrumentStatusOp: InstrumentStatusDB)
                                         (@Assisted("server") server: String, @Assisted("monitors") monitors: Seq[String]) extends Actor {

  import ForwardManager._

  val logger: Logger = Logger(this.getClass)
  logger.info(s"InstrumentStatusForwarder started server=$server monitor=${monitors.mkString(",")}")

  def receive: Receive = handler(Map.empty)

  def checkLatest(monitor:String)(monitorLatestMap:Map[String, Long]): Unit = {
    val url = s"http://$server/InstrumentStatusRange/$monitor"
    val f = ws.url(url).get().map {
      response =>
        val result = response.json.validate[LatestRecordTime]
        result.fold(
          error => {
            logger.error(JsError.toJson(error).toString())
          },
          latest => {
            logger.info(s"server latest instrument status: ${new DateTime(latest.time).toString}")
            context become handler(monitorLatestMap + (monitor->latest.time))
            uploadRecord(monitor, latest.time)(monitorLatestMap)
          })
    }
    f.failed.foreach(errorHandler)
  }

  def uploadRecord(monitor:String, latestRecordTime: Long)(monitorLatestMap:Map[String, Long]): Unit = {
    val recordFuture = instrumentStatusOp.queryFuture(new DateTime(latestRecordTime + 1), DateTime.now)
    for (records <- recordFuture) {
      if (records.nonEmpty) {
        val url = s"http://$server/InstrumentStatusRecord/$monitor"
        val f = ws.url(url).put(Json.toJson(records))
        f.foreach(_ => context become handler(monitorLatestMap + (monitor->records.last.time.getTime)))
        f.failed.foreach(ex => {
          ModelHelper.logException(ex)
          context become handler(monitorLatestMap - monitor)
        })
      }
    }
  }


  def handler(monitorLatestMap:Map[String, Long]): Receive = {
    case ForwardInstrumentStatus =>
      monitors.foreach(monitor=>{
        if (monitorLatestMap.contains(monitor))
          uploadRecord(monitor, monitorLatestMap(monitor))(monitorLatestMap)
        else
          checkLatest(monitor)(monitorLatestMap)
      })
  }
}