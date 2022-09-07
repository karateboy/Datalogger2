package models

import akka.actor.{Actor, actorRef2Scala}
import com.google.inject.assistedinject.Assisted
import play.api.Logger
import play.api.libs.json.{JsError, Json}
import play.api.libs.ws.WSClient

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global


object InstrumentStatusTypeForwarder {
  trait Factory {
    def apply(@Assisted("server") server: String, @Assisted("monitor") monitor: String): Actor
  }
}

class InstrumentStatusTypeForwarder @Inject()(instrumentOp: InstrumentDB, ws: WSClient)
  (@Assisted("server") server: String, @Assisted("monitor") monitor: String) extends Actor {

  import ForwardManager._

  Logger.info(s"InstrumentStatusTypeForwarder started $server/$monitor")

  def receive = handler(None)

  def handler(instrumentStatusTypeIdOpt: Option[String]): Receive = {
    case UpdateInstrumentStatusType =>
      try {
        if (instrumentStatusTypeIdOpt.isEmpty) {
          val url = s"http://$server/InstrumentStatusTypeIds/$monitor"
          val f = ws.url(url).get().map {
            response =>
              val result = response.json.validate[String]
              result.fold(
                error => {
                  Logger.error(JsError.toJson(error).toString())
                },
                ids => {
                  context become handler(Some(ids))
                  self ! UpdateInstrumentStatusType
                })
          }
          f onFailure {
            case ex: Throwable =>
              ModelHelper.logException(ex)
          }
        } else {
          val recordFuture = instrumentOp.getAllInstrumentFuture
          for (records <- recordFuture) {
            val withStatusType = records.filter {
              _.statusType.isDefined
            }
            if (!withStatusType.isEmpty) {
              val myIds = withStatusType.map { inst =>
                inst._id + inst.statusType.get.mkString("")
              }.mkString("")

              if (myIds != instrumentStatusTypeIdOpt.get) {
                Logger.info("statusTypeId is not equal. updating...")
                val istMaps = withStatusType.map { inst =>
                  InstrumentStatusTypeMap(inst._id, inst.statusType.get)
                }
                val url = s"http://$server/InstrumentStatusTypeMap/$monitor"
                implicit val write1 = Json.writes[InstrumentStatusType]
                implicit val writer = Json.writes[InstrumentStatusTypeMap]
                val f = ws.url(url).put(Json.toJson(istMaps))
                f onSuccess {
                  case _ =>
                    context become handler(Some(myIds))
                }
                f onFailure {
                  case ex: Throwable =>
                    ModelHelper.logException(ex)
                }
              }
            }
          }

        }
      } catch {
        case ex: Throwable =>
          ModelHelper.logException(ex)
      }
  }
}