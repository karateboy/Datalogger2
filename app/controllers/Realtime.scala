package controllers

import com.github.nscala_time.time.Imports._
import models._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._

import java.util.Date
import javax.inject._
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class Realtime @Inject()
(monitorTypeOp: MonitorTypeDB,
 dataCollectManagerOp: DataCollectManagerOp,
 instrumentOp: InstrumentDB,
 monitorStatusOp: MonitorStatusDB,
 monitorDB: MonitorDB,
 recordDB: RecordDB,
 sysConfigDB: SysConfigDB,
 security: Security,
 cc: ControllerComponents) extends AbstractController(cc) {
  val logger: Logger = Logger(this.getClass)
  private val overTimeLimit = 6

  case class MonitorTypeStatus(_id: String,
                               desp: String,
                               value: String,
                               unit: String,
                               instrument: String,
                               status: String,
                               classStr: Seq[String],
                               order: Int)

  def MonitorTypeStatusList(): Action[AnyContent] = security.Authenticated.async {
    implicit request =>

      implicit val mtsWrite: OWrites[MonitorTypeStatus] = Json.writes[MonitorTypeStatus]

      val result =
        for {
          instrumentMap <- instrumentOp.getInstrumentMapFuture()
          dataMap <- dataCollectManagerOp.getLatestData
        } yield {
          val list = {
            for {
              mt <- monitorTypeOp.realtimeMtvList.sortBy(monitorTypeOp.map(_).order)
              recordOpt = dataMap.get(mt)
            } yield {
              val mCase = monitorTypeOp.map(mt)
              val measuringByStr = mCase.measuringBy.map {
                instrumentList =>
                  instrumentList.mkString(",")
              }.getOrElse("??")

              def instrumentStatus() = {
                val ret =
                  for (measuringBy <- mCase.measuringBy) yield {
                    val instruments: List[Instrument] =
                      measuringBy.flatMap(instrumentMap.get)

                    if (instruments.exists(inst => !inst.active))
                      "停用"
                    else
                      "斷線"
                  }
                ret.getOrElse("設備啟動中")
              }

              if (recordOpt.isDefined) {
                val record = recordOpt.get
                val duration = new Duration(record.time, DateTime.now())
                val (overInternal, overLaw) = monitorTypeOp.overStd(mt, record.value)
                val status = if (duration.getStandardSeconds <= overTimeLimit)
                  monitorStatusOp.map(record.status).desp
                else
                  instrumentStatus()

                MonitorTypeStatus(_id = mCase._id, desp = mCase.desp, monitorTypeOp.format(mt, record.value),
                  mCase.unit, measuringByStr,
                  status,
                  MonitorStatus.getCssClassStr(record.status, overInternal, overLaw), mCase.order)
              } else {
                if (instrumentStatus == "停用")
                  MonitorTypeStatus(_id = mCase._id, mCase.desp, monitorTypeOp.format(mt, None),
                    mCase.unit, measuringByStr,
                    instrumentStatus(),
                    Seq("stop_status"), mCase.order)
                else
                  MonitorTypeStatus(_id = mCase._id, mCase.desp, monitorTypeOp.format(mt, None),
                    mCase.unit, measuringByStr,
                    instrumentStatus(),
                    Seq("disconnect_status"), mCase.order)
              }
            }
          }

          Ok(Json.toJson(list))
        }

      result
  }

  case class RealtimeAQI(date: Date, aqi: AqiExplainReport)

  def getRealtimeAQI: Action[AnyContent] = security.Authenticated.async {
    val lastHour = DateTime.now().minusHours(1).withMinuteOfHour(0)
      .withSecondOfMinute(0).withMillisOfSecond(0)
    for (ret <- AQI.getMonitorRealtimeAQI(Monitor.activeId, lastHour)(recordDB)) yield {
      val aqiExplainReport = AQI.getAqiExplain(ret)(monitorTypeOp)
      implicit val w3: OWrites[AqiExplain] = Json.writes[AqiExplain]
      implicit val w2: OWrites[AqiSubExplain] = Json.writes[AqiSubExplain]
      implicit val w1: OWrites[AqiExplainReport] = Json.writes[AqiExplainReport]
      implicit val w0: OWrites[RealtimeAQI] = Json.writes[RealtimeAQI]
      Ok(Json.toJson(RealtimeAQI(lastHour.toDate, aqiExplainReport)))
    }
  }

  def getAqiMonitorTypeMapping: Action[AnyContent] = security.Authenticated.async(
    for (monitorTypes <- sysConfigDB.getAqiMonitorTypes) yield {
      Ok(Json.toJson(monitorTypes))
    }
  )

  def postAqiMonitorTypeMapping(): Action[JsValue] = security.Authenticated.async(parse.json) {
    implicit request =>
      val ret = request.body.validate[Seq[String]]
      ret.fold(err => {
        logger.error(JsError.toJson(err).toString())
        Future {
          BadRequest(JsError.toJson(err).toString())
        }
      },
        monitorTypes => {
          for (ret <- sysConfigDB.setAqiMonitorTypes(monitorTypes)) yield {
            //insert case
            monitorTypes.foreach(monitorTypeOp.ensure)
            AQI.updateAqiTypeMapping(monitorTypes)
            Ok(Json.obj("ok" -> ret.wasAcknowledged()))
          }
        })
  }

  case class LatestMonitorData(monitorTypes: Seq[String], monitorData: Seq[RecordList])

  def getLatestMonitorData: Action[AnyContent] = security.Authenticated.async {
    implicit val writes: OWrites[LatestMonitorData] = Json.writes[LatestMonitorData]
    val (start, end) = (DateTime.now().minusYears(2), DateTime.now())

    val monitorDailyRecordListsF = Future.sequence(for (monitor <- monitorDB.mvList) yield
      recordDB.getRecordWithLimitFuture(recordDB.MinCollection)(start, end, 1, monitor))


    for (monitorDailyRecordLists <- monitorDailyRecordListsF) yield {
      val monitorTypes = Seq(MonitorType.RAIN, MonitorType.TEMP, MonitorType.WIN_SPEED, MonitorType.WIN_DIRECTION)
      val monitorRecordLists = monitorDailyRecordLists flatMap {
        dailyRecordList => {
          if (dailyRecordList.isEmpty)
            None
          else {
            val monitorMtData = ListBuffer.empty[MtRecord]
            val realtimeRecordList = dailyRecordList.last
            val _id = realtimeRecordList._id
            realtimeRecordList.mtDataList.foreach(monitorMtData.append(_))
            Some(RecordList(monitorMtData, _id))
          }
        }
      }
      Ok(Json.toJson(LatestMonitorData(monitorTypes, monitorRecordLists)))
    }
  }

}