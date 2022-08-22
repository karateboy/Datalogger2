package models

import akka.actor.{Actor, ActorSystem, Props}
import models.EasexDataGetter.GetData
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.WSClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

object EasexDataGetter {
  def start(actorSystem: ActorSystem,
            sysConfig: SysConfigDB, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB, dataCollectManagerOp: DataCollectManagerOp,
            WSClient: WSClient) =
    actorSystem.actorOf(props(sysConfig, monitorTypeOp, recordOp, dataCollectManagerOp, WSClient), "EasexDataGetter")

  def props(sysConfig: SysConfigDB, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB, dataCollectManagerOp: DataCollectManagerOp,
            WSClient: WSClient) =
    Props(classOf[EasexDataGetter], sysConfig, monitorTypeOp, recordOp, dataCollectManagerOp, WSClient)

  case object GetData
}

case class Tag(key: String, value: String)

case class EaseXParam(project_uuid: String,
                      resource_uuid: String,
                      fields: Seq[String],
                      limit: Int,
                      tags: Seq[Tag],
                      start: String,
                      stop: String
                     )

case class EasxResult(_time: Map[Int, Long], _value: Map[Int, Double])

case class EasxResponse(result: String, data: String)

class EasexDataGetter(sysConfig: SysConfigDB, monitorTypeOp: MonitorTypeDB,
                      recordOp: RecordDB, dataCollectManagerOp: DataCollectManagerOp,
                      WSClient: WSClient) extends Actor {

  Logger.info(s"Ease-X Data Getter started")
  val project_uuid = "f3d26306-1766-4ce6-9081-8ca2843c8f99"
  val resource_uuid = "f2aea35d-0399-4011-a14e-3fcb8672e3b2"

  self ! GetData

  implicit val mapIntDoubleReads: Reads[Map[Int, Double]] = new Reads[Map[Int, Double]] {
    def reads(jv: JsValue): JsResult[Map[Int, Double]] =
      JsSuccess(jv.as[Map[String, Double]].map { case (k, v) =>
        Integer.parseInt(k) -> v
      })
  }

  implicit val mapIntLongReads: Reads[Map[Int, Long]] = new Reads[Map[Int, Long]] {
    def reads(jv: JsValue): JsResult[Map[Int, Long]] =
      JsSuccess(jv.as[Map[String, Long]].map { case (k, v) =>
        Integer.parseInt(k) -> v
      })
  }

  override def receive: Receive = {
    case GetData =>
      implicit val w1 = Json.writes[Tag]
      implicit val write = Json.writes[EaseXParam]
      val param = EaseXParam(project_uuid, resource_uuid = resource_uuid,
        fields = Seq("temperature"), limit = 1, tags = Seq(Tag("WellID", "EV-20-NE-1")),
        start = "2022-05-07T00:00:00Z", stop = "2022-08-01T00:00:00Z")
      val request = WSClient.url("https://app-backend.ease-x.com/api/influx/data")
        .withHeaders(("Authorization",
          "Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJ1c2VybmFtZSI6InNmZGZmMzI3QGdtYWlsLmNvbSIsInVuZXhwaXJlZF90b2tlbiI6dHJ1ZSwiY3JlYXRlZF90aW1lIjoxNjQxODAzNDgyLjI1NTg2NH0.T6zJD8BlcG_QC3jffDo1_gUyjM51j2e1DrWyWZ6WcEM"))
        .withBody(Json.toJson(param)).get()

      def handleData(data: String): Unit = {
        def handleResult(result:EasxResult): Unit ={

        }
        implicit val reads = Json.reads[EasxResult]
        val ret = Json.parse(data).validate[EasxResult]
        ret.fold(invalid => Logger.error(JsError(invalid).toString),
          result =>{

          })
      }

      request.onComplete({
        case Success(response) =>
          implicit val reads = Json.reads[EasxResponse]
          val ret = response.json.validate[EasxResponse]
          ret.fold(invalid => Logger.error(JsError(invalid).toString),
            result => handleData(result.data))

        case Failure(exception) =>
          Logger.error("failed to get data", exception)
      })
  }
}
