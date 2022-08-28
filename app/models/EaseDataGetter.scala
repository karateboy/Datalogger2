package models

import akka.actor.{Actor, ActorSystem, Props}
import com.github.nscala_time.time.Imports.{DateTime, Duration}
import models.EaseDataGetter.{GetDataEnd, GetHistoryData, RecalculateHour}
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

object EaseDataGetter {
  def start(actorSystem: ActorSystem,
            sysConfig: SysConfigDB, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB, dataCollectManagerOp: DataCollectManagerOp,
            WSClient: WSClient) =
    actorSystem.actorOf(props(sysConfig, monitorTypeOp, recordOp, dataCollectManagerOp, WSClient), "EasexDataGetter")

  def props(sysConfig: SysConfigDB, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB, dataCollectManagerOp: DataCollectManagerOp,
            WSClient: WSClient) =
    Props(classOf[EaseDataGetter], sysConfig, monitorTypeOp, recordOp, dataCollectManagerOp, WSClient)

  case object GetLatestData

  case class GetHistoryData(mt:String, adjustOutstanding:Boolean)

  case class GetDataEnd(mt:String, startTime:DateTime)

  case object RecalculateHour
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

case class EaseResult(_time: Map[Int, Long], _value: Map[Int, Double])

case class EaseResponse(result: String, data: String)

class EaseDataGetter(sysConfig: SysConfigDB, monitorTypeOp: MonitorTypeDB,
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

  val timer = context.system.scheduler.schedule(FiniteDuration(10, SECONDS), FiniteDuration(3, MINUTES), self, GetLatestData)

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
  for(ret<-sysConfig.getEaseHistoryData()){
    if(!ret)
      monitorTypes.foreach(self ! GetHistoryData(_, true))
  }

  override def receive = handler(0, firstDataTime, DateTime.now())

  def handler(outstanding:Int, start:DateTime, end:DateTime): Receive = {
    case GetLatestData =>

      if(outstanding == 0){
        context become handler(monitorTypes.size, DateTime.now().minusHours(2), DateTime.now())
        monitorTypes.foreach(getMonitorTypeData(_, DateTime.now().minusHours(2), DateTime.now(), 1, false))
      }

    case GetHistoryData(mt, adjustOutstanding) =>
      Logger.info(s"GetHistoryData $outstanding")
      val endTime = new DateTime(monitorTypeOp.map(mt).oldestRecordTime.getOrElse(DateTime.now().getMillis))
      if(adjustOutstanding){
        if(outstanding == 0)
          context become handler(1, firstDataTime, endTime)
        else
          context become handler(outstanding + 1, start, end)
      }

      getMonitorTypeData(mt, firstDataTime, endTime, 100, true)

    case GetDataEnd(_, startTime) =>
      val newOutstanding = outstanding -1
      if(start == firstDataTime)
        context become handler(newOutstanding, startTime, end)
      else{
        if(startTime.isBefore(start))
          context become handler(newOutstanding, startTime, end)
        else
          context become handler(newOutstanding, start, end)
      }
      if(newOutstanding == 0)
        self ! RecalculateHour

    case RecalculateHour=>
      Logger.info(s"Recalculate from $start to $end")
      for (current <- getHourBetween(start, end))
        dataCollectManagerOp.recalculateHourData(Monitor.SELF_ID, current)(monitorTypeOp.activeMtvList, monitorTypeOp)
  }

  def getMonitorTypeData(mt: String, start: DateTime, end:DateTime, limit:Int, recursive:Boolean = false)  {
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

    def handleResult(result: EaseResult) {
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

          if(recursive && docList.size == 100) {
            Logger.info(s"$mt still have more history data fire GetHistoryData again")
            self ! GetHistoryData(mt, false)
          }else
            self ! GetDataEnd(mt, end)
        }
      } else
        self ! GetDataEnd(mt, new DateTime(docList.head._id.time.getTime))
    }

    def handleData(data: String) {
      implicit val reads = Json.reads[EaseResult]
      val ret = Json.parse(data).validate[EaseResult]
      ret.fold(invalid => Future.successful({
        if(recursive)
          Logger.info(s"$mt has invalid Data terminated recursive getData")
        else
          Logger.error(s"$mt get invalid Data ${JsError.toJson(invalid)}")
        self ! GetDataEnd(mt, end)
      }),
        result => handleResult(result))
    }

    request onFailure errorHandler()

    for (response <- request) yield {
      implicit val reads = Json.reads[EaseResponse]
      val ret = response.json.validate[EaseResponse]
      ret.fold(invalid =>
        Future.successful{
          if(recursive)
            Logger.info(s"$mt has invalid Data terminated recursive getData")
          else
            Logger.error(s"$mt get invalid Data ${JsError.toJson(invalid)}")

          self ! GetDataEnd(mt, end)
        }
       ,
        result => {
          if(result.result == "success")
            handleData(result.data)
          else
            self ! GetDataEnd(mt, end)
        })
    }
  }

  override def postStop(): Unit = {
    timer.cancel()
    super.postStop()
  }
}
