package models
import akka.actor.Actor
import com.github.nscala_time.time.Imports.DateTime
import com.google.inject.assistedinject.Assisted
import play.api.Logger
import play.api.libs.json.{JsError, Json}
import play.api.libs.ws.WSClient

import java.util.Date
import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

object InstrumentStatusForwarder{
  trait Factory {
    def apply(@Assisted("server") server: String, @Assisted("monitor") monitor: String): Actor
  }
}

class InstrumentStatusForwarder @Inject()(ws:WSClient, instrumentStatusOp: InstrumentStatusDB)
                                         (@Assisted("server") server: String, @Assisted("monitor") monitor: String) extends Actor {
  import ForwardManager._
  Logger.info(s"InstrumentStatusForwarder started $server/$monitor")

  def receive: Receive = handler(None)
  def checkLatest(): Unit = {
    val url = s"http://$server/InstrumentStatusRange/$monitor"
    val f = ws.url(url).get().map {
      response =>
        val result = response.json.validate[LatestRecordTime]
        result.fold(
          error => {
            Logger.error(JsError.toJson(error).toString())
          },
          latest => {
            Logger.info(s"server latest instrument status: ${new DateTime(latest.time).toString}")
            context become handler(Some(new Date(latest.time)))
            uploadRecord(new Date(latest.time))
          })
    }
    f onFailure {
      case ex: Throwable =>
        ModelHelper.logException(ex)
    }
  }

  def uploadRecord(latestRecordTime: Date): Unit = {
    val recordFuture = instrumentStatusOp.queryFuture(new DateTime(latestRecordTime.getTime + 1), DateTime.now)
    for (records <- recordFuture) {
      if (records.nonEmpty) {
        val url = s"http://$server/InstrumentStatusRecord/$monitor"
        val f = ws.url(url).put(Json.toJson(records))
        f onSuccess {
          case response =>
            context become handler(Some(records.last.time))
        }
        f onFailure {
          case ex: Throwable =>
            context become handler(None)
            ModelHelper.logException(ex)
        }
      }
    }
  }
  
  
  def handler(latestRecordTimeOpt: Option[Date]): Receive = {
    case ForwardInstrumentStatus =>
      if (latestRecordTimeOpt.isEmpty)
        checkLatest()
      else
        uploadRecord(latestRecordTimeOpt.get)
  }
}