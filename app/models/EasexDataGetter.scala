package models

import akka.actor.{Actor, ActorSystem, Props}
import com.github.nscala_time.time.Imports.{DateTime, Duration}
import models.EasexDataGetter.GetHistoryData
import models.ModelHelper.{errorHandler, getHourBetween}
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.WSClient

import java.time.Instant
import java.util.Date
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, MINUTES, SECONDS}

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

  case object GetLatestData

  case class GetHistoryData(mt:String)
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
  val monitorTypes = Seq("temperature", "pressure", "depth", "specific_conductivity", "density_of_water", "barometric_pressure",
    "ph", "ORP", "RDO", "turbidity", "oxygen_partial_pressure", "total_suspended_solids")

  monitorTypes.foreach(mt => {
    monitorTypeOp.ensureMeasuring(mt)
    recordOp.ensureMonitorType(mt)
  })

  val timer = context.system.scheduler.schedule(FiniteDuration(5, SECONDS), FiniteDuration(10, MINUTES), self, GetLatestData)

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

  implicit val w1 = Json.writes[Tag]
  implicit val write = Json.writes[EaseXParam]

  val firstDataTime = new DateTime(2022, 5, 1, 0, 0)

  // Try to get all history data
  //monitorTypes.foreach(self ! GetHistoryData(_))

  override def receive: Receive = {
    case GetLatestData =>
      monitorTypes.foreach(getMonitorTypeData(_, DateTime.now().minusHours(2), DateTime.now(), 1, false))

    case GetHistoryData(mt) =>
      val endTime = new DateTime(monitorTypeOp.map(mt).oldestRecordTime.getOrElse(DateTime.now().getMillis))
      getMonitorTypeData(mt, firstDataTime, endTime, 100, true)
  }

  def getMonitorTypeData(mt: String, start: DateTime, end:DateTime, limit:Int, recursive:Boolean = false): Future[Any] = {
    val mtCase = monitorTypeOp.map(mt)
    if(recursive)
      Logger.info(s"$mt GetHistoryData start=$start end=$end")
    else
      Logger.info(s"$mt GetLatestData start=$start end=$end")

    val param = EaseXParam(project_uuid, resource_uuid = resource_uuid,
      fields = Seq(mt), limit = limit, tags = Seq(Tag("WellID", "EV-20-NE-1")),
      start = start.toString, stop = end.toString())

    val request = WSClient.url("https://app-backend.ease-x.com/api/influx/data")
      .withHeaders(("Authorization",
        "Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJ1c2VybmFtZSI6InNmZGZmMzI3QGdtYWlsLmNvbSIsInVuZXhwaXJlZF90b2tlbiI6dHJ1ZSwiY3JlYXRlZF90aW1lIjoxNjQxODAzNDgyLjI1NTg2NH0.T6zJD8BlcG_QC3jffDo1_gUyjM51j2e1DrWyWZ6WcEM"))
      .withBody(Json.toJson(param)).get()

    def handleResult(result: EasxResult): Future[Any] = {
      val count = result._value.size
      val docList = ListBuffer.empty[RecordList]

      for (i <- 0 to count - 1) {
        val dt = Instant.ofEpochMilli(result._time(i))
        val mtRecord = MtRecord(mt, Some(result._value(i)), MonitorStatus.NormalStat)
        docList.prepend(RecordList(time = Date.from(dt), monitor = Monitor.SELF_ID, mtDataList = Seq(mtRecord)))
      }

      if (docList.nonEmpty) {
        Logger.debug(s"$mt has ${docList.size} records from ${docList.head._id.time} to ${docList.last._id.time}")
        val f = recordOp.upsertManyRecords(recordOp.MinCollection)(docList)

        f onFailure errorHandler()
        for (_ <- f) yield {
          mtCase.oldestRecordTime = Some(docList.head._id.time.getTime - 1000)
          monitorTypeOp.upsertMonitorType(mtCase)
          for (current <- getHourBetween(new DateTime(docList.head._id.time.getTime), end))
            dataCollectManagerOp.recalculateHourData(Monitor.SELF_ID, current)(monitorTypeOp.activeMtvList, monitorTypeOp)

          if(recursive && docList.size == 100) {
            Logger.info(s"$mt still have more history data fire GetHistoryData again")
            self ! GetHistoryData(mt)
          }
        }
      } else
        Future.successful({ end })
    }

    def handleData(data: String): Future[Any] = {
      implicit val reads = Json.reads[EasxResult]
      val ret = Json.parse(data).validate[EasxResult]
      ret.fold(invalid => Future.successful({
        if(recursive)
          Logger.info(s"$mt has invalid Data terminated recursive getData")
        else
          Logger.error(s"$mt get invalid Data ${JsError.toJson(invalid)}")
        end
      }),
        result => handleResult(result))
    }

    request onFailure errorHandler()

    for (response <- request) yield {
      implicit val reads = Json.reads[EasxResponse]
      val ret = response.json.validate[EasxResponse]
      ret.fold(invalid =>
        Future.successful{
          if(recursive)
            Logger.info(s"$mt has invalid Data terminated recursive getData")
          else
            Logger.error(s"$mt get invalid Data ${JsError.toJson(invalid)}")

          end
        }
       ,
        result => {
          if(result.result == "success")
            handleData(result.data)
          else
            Future.successful{ end }
        })
    }
  }

  override def postStop(): Unit = {
    timer.cancel()
    super.postStop()
  }
}
