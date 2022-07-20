package controllers

import com.github.nscala_time.time.Imports._
import models._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

class Realtime @Inject()
(monitorTypeOp: MonitorTypeDB, dataCollectManagerOp: DataCollectManagerOp, instrumentOp: InstrumentDB,
 monitorStatusOp: MonitorStatusDB, recordDB: RecordDB) extends Controller {
  val overTimeLimit = 6

  def MonitorTypeStatusList() = Security.Authenticated.async {
    implicit request =>

      implicit val mtsWrite = Json.writes[MonitorTypeStatus]

      val result =
        for {
          instrumentMap <- instrumentOp.getInstrumentMap()
          dataMap <- dataCollectManagerOp.getLatestData()
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
                      measuringBy.map(instrumentMap.get(_)).flatten

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
                  instrumentStatus

                MonitorTypeStatus(_id = mCase._id, desp = mCase.desp, monitorTypeOp.format(mt, record.value),
                  mCase.unit, measuringByStr,
                  status,
                  MonitorStatus.getCssClassStr(record.status, overInternal, overLaw), mCase.order)
              } else {
                if (instrumentStatus == "停用")
                  MonitorTypeStatus(_id = mCase._id, mCase.desp, monitorTypeOp.format(mt, None),
                    mCase.unit, measuringByStr,
                    instrumentStatus,
                    Seq("stop_status"), mCase.order)
                else
                  MonitorTypeStatus(_id = mCase._id, mCase.desp, monitorTypeOp.format(mt, None),
                    mCase.unit, measuringByStr,
                    instrumentStatus,
                    Seq("disconnect_status"), mCase.order)
              }
            }
          }

          Ok(Json.toJson(list))
        }

      result
  }

  def getWeatherSummary() = Security.Authenticated.async {
    val winSpeedMax = "WINSPEED_MAX"
    val mtList = Seq(MonitorType.WIN_SPEED, MonitorType.WIN_DIRECTION, MonitorType.RAIN, MonitorType.HUMID,
      MonitorType.TEMP, winSpeedMax)

    val realtimeMapFuture =
      recordDB.getRecordMapFuture(recordDB.MinCollection)(Monitor.SELF_ID, mtList, DateTime.now.minusDays(2), DateTime.now)

    val hourListFuture =
      recordDB.getRecordListFuture(recordDB.HourCollection)(DateTime.now.minusDays(2), DateTime.now)

    implicit val write = Json.writes[WeatherSummary]

    for (realtimeMap <- realtimeMapFuture; hourList <- hourListFuture) yield {
      val windir = if (realtimeMap(MonitorType.WIN_DIRECTION).nonEmpty)
        realtimeMap(MonitorType.WIN_DIRECTION).last.value
      else
        None

      val winmax =
        if(realtimeMap(winSpeedMax).nonEmpty)
          realtimeMap(winSpeedMax).last.value
        else
          None

      val temp =
        if(realtimeMap(MonitorType.TEMP).nonEmpty)
        realtimeMap(MonitorType.TEMP).last.value
        else
          None
      val winspeed =
        if(realtimeMap(MonitorType.WIN_SPEED).nonEmpty)
        realtimeMap(MonitorType.WIN_SPEED).last.value
        else
          None
      val humid =
        if(realtimeMap(MonitorType.HUMID).nonEmpty)
        realtimeMap(MonitorType.HUMID).last.value
        else
          None
      val rainList = realtimeMap(MonitorType.RAIN).reverse
      val rain10 = if(rainList.size > 11)
          for (rain0 <- rainList(0).value; rain10 <- rainList(10).value) yield rain10 - rain0
      else
        None
      val rain60 =  if(rainList.size > 61)
        for (rain0 <- rainList(0).value; rain60 <- rainList(60).value) yield rain60 - rain0
      else
        None
      val rainDay = if(rainList.nonEmpty)
        for (rain0 <- rainList(0).value; rainDay <- rainList.last.value) yield rainDay - rain0
      else
        None

      val rainHourList = hourList.filter(rec=>rec.mtDataList.exists(_.mtName == MonitorType.RAIN)).reverse
      val (hourStart, rainHour1) = if(rainHourList.nonEmpty)
        (rainHourList(0)._id.time.getTime, rainHourList(0).mtMap(MonitorType.RAIN).value)
      else
        (DateTime.now.getMillis, None)

      val rainHour2 = if(rainHourList.size>=2)
        rainHourList(1).mtMap(MonitorType.RAIN).value
      else
        None

      val rainHour3 = if(rainHourList.size>=3)
        rainHourList(2).mtMap(MonitorType.RAIN).value
      else
        None

      Ok(Json.toJson(WeatherSummary(windir, winmax,
        temp, winspeed, humid,
        Seq(rain10, rain60, rainDay), hourStart, Seq(rainHour1, rainHour2, rainHour3))))
    }
  }

  case class MonitorTypeStatus(_id: String, desp: String, value: String, unit: String, instrument: String, status: String, classStr: Seq[String], order: Int)

  case class WeatherSummary(windir: Option[Double], winmax: Option[Double],
                            temp: Option[Double], winspeed: Option[Double], humid: Option[Double],
                            rain: Seq[Option[Double]], hourStart: Long, hourRain: Seq[Option[Double]])
}