package models

import akka.actor.Actor
import com.google.inject.assistedinject.Assisted
import models.ModelHelper.errorHandler
import play.api.Logger
import play.api.libs.json.{JsError, Json, OWrites}
import play.api.libs.ws.WSClient

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

case class InstrumentStatusTypeMap(instrumentId: String, statusTypeSeq: Seq[InstrumentStatusType])

object InstrumentStatusTypeForwarder {
  trait Factory {
    def apply(@Assisted("server") server: String, @Assisted("monitors") monitors: Seq[String]): Actor
  }
}

class InstrumentStatusTypeForwarder @Inject()(instrumentOp: InstrumentDB, ws: WSClient)
  (@Assisted("server") server: String, @Assisted("monitors") monitors: Seq[String]) extends Actor {
  val logger: Logger = Logger(this.getClass)
  import ForwardManager._

  logger.info(s"InstrumentStatusTypeForwarder started server=$server monitor=${monitors.mkString(",")}")

  def receive: Receive = handler(Map.empty)

  private def fetchId(monitor:String)(monitorInstrumentStatusTypeIdMap:Map[String, String]): Unit ={
    try{
      val url = s"http://$server/InstrumentStatusTypeIds/$monitor"
      val f = ws.url(url).get().map {
        response =>
          val result = response.json.validate[String]
          result.fold(
            error => {
              logger.error(JsError.toJson(error).toString())
            },
            ids => {
              context become handler(monitorInstrumentStatusTypeIdMap + (monitor->ids))
              self ! UpdateInstrumentStatusType
            })
      }
      f.failed.foreach(errorHandler)
    }catch{
      case ex: Throwable =>
        ModelHelper.logException(ex)
    }
  }
  private def fetchStatusType(monitor:String)(monitorInstrumentStatusTypeIdMap:Map[String, String]): Unit = {
    try{
      val recordFuture = instrumentOp.getAllInstrumentFuture
      for (records <- recordFuture) {
        val withStatusType = records.filter {
          _.statusType.isDefined
        }
        if (withStatusType.nonEmpty) {
          val myIds = withStatusType.map { inst =>
            inst._id + inst.statusType.get.mkString("")
          }.mkString("")

          if (myIds != monitorInstrumentStatusTypeIdMap(monitor)) {
            logger.info("statusTypeId is not equal. updating...")
            val istMaps = withStatusType.map { inst =>
              InstrumentStatusTypeMap(inst._id, inst.statusType.get)
            }
            val url = s"http://$server/InstrumentStatusTypeMap/$monitor"
            implicit val write1: OWrites[InstrumentStatusType] = Json.writes[InstrumentStatusType]
            implicit val writer: OWrites[InstrumentStatusTypeMap] = Json.writes[InstrumentStatusTypeMap]
            val f = ws.url(url).put(Json.toJson(istMaps))
            f.foreach(_ => context become handler(monitorInstrumentStatusTypeIdMap + (monitor->myIds)))
            f.failed.foreach(errorHandler)
          }
        }
      }
    }catch{
      case ex: Throwable =>
        ModelHelper.logException(ex)
    }
  }
  def handler(monitorInstrumentStatusTypeIdMap:Map[String, String]): Receive = {
    case UpdateInstrumentStatusType =>
      try {
        monitors.foreach(monitor=>{
          if (monitorInstrumentStatusTypeIdMap.contains(monitor))
            fetchStatusType(monitor)(monitorInstrumentStatusTypeIdMap)
          else
            fetchId(monitor)(monitorInstrumentStatusTypeIdMap)
        })
      } catch {
        case ex: Throwable =>
          ModelHelper.logException(ex)
      }
  }
}