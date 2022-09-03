package controllers

import com.github.nscala_time.time.Imports._
import models._
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
      recordDB.getRecordMapFuture(recordDB.MinCollection)(Monitor.SELF_ID, mtList, DateTime.now.minusDays(1), DateTime.now)

    val hourListFuture =
      recordDB.getRecordListFuture(recordDB.HourCollection)(DateTime.now.minusDays(1), DateTime.now)

    implicit val write = Json.writes[WeatherSummary]

    for (realtimeMap <- realtimeMapFuture; hourList <- hourListFuture) yield {
      val windir = if (realtimeMap(MonitorType.WIN_DIRECTION).nonEmpty)
        realtimeMap(MonitorType.WIN_DIRECTION).last.value
      else
        None

      val startOfToday = DateTime.now().withHourOfDay(0).withMinuteOfHour(0)
        .withSecondOfMinute(0).withMillisOfSecond(0)

      def todayRecord(records:Seq[Record]) =
        records.filter(rec=>rec.time>=startOfToday && rec.time < DateTime.now())

      val winmax =
        if (realtimeMap(winSpeedMax).nonEmpty)
          realtimeMap(winSpeedMax).last.value
        else
          None

      val winMaxTodayRecords = todayRecord(realtimeMap(winSpeedMax))
      val winMaxToday =
        if (winMaxTodayRecords.nonEmpty)
          winMaxTodayRecords.map(_.value).max
        else
          None

      val temp =
        if (realtimeMap(MonitorType.TEMP).nonEmpty)
          realtimeMap(MonitorType.TEMP).last.value
        else
          None


      val winspeed =
        if (realtimeMap(MonitorType.WIN_SPEED).nonEmpty) {
          val data = realtimeMap(MonitorType.WIN_SPEED).reverse.take(60).flatMap(_.value)
          Some(data.sum/data.length)
        } else
          None
      
      val winSpeedTodayRecords = hourList.filter(rec => new DateTime(rec._id.time) >=startOfToday && new DateTime(rec._id.time) < DateTime.now())
        .flatMap(_.mtMap.get(MonitorType.WIN_SPEED))
      
      val winSpeedTodayMax =
        if (winSpeedTodayRecords.nonEmpty)
          winSpeedTodayRecords.map(_.value).max
        else
          None
      
      val humid =
        if (realtimeMap(MonitorType.HUMID).nonEmpty)
          realtimeMap(MonitorType.HUMID).last.value
        else
          None

      val rainList = realtimeMap(MonitorType.RAIN).reverse
      val rain10 = if (rainList.size >= 10)
        Some(rainList.take(10).flatMap(_.value).sum)
      else
        None
      val rain60 = if (rainList.nonEmpty)
        Some(rainList.take(60).flatMap(_.value).sum)
      else
        None
      val rainDay = if (rainList.nonEmpty)
        Some(rainList.take(60 * 24).flatMap(_.value).sum)
      else
        None

      val rainHourList = hourList.filter(rec => rec.mtDataList.exists(_.mtName == MonitorType.RAIN)).reverse
      val hourStart = if (rainHourList.nonEmpty)
        rainHourList.head._id.time.getTime
      else
        DateTime.now.getMillis

      val rainHourData = rainHourList.take(12).map(_.mtMap(MonitorType.RAIN).value)

      Ok(Json.toJson(WeatherSummary(windir, winmax, winMaxToday,
        temp, winspeed, winSpeedTodayMax,
        humid, Seq(rain10, rain60, rainDay), hourStart, rainHourData)))
    }
  }

  def getRealtimeWeather = Security.Authenticated {
    implicit val writes = Json.writes[MtRecord]
    Ok(Json.toJson(WeatherReader.latestRecord))
  }

  case class MonitorTypeStatus(_id: String, desp: String, value: String, unit: String, instrument: String, status: String, classStr: Seq[String], order: Int)

  case class WeatherSummary(windir: Option[Double], winMax: Option[Double], winMaxToday: Option[Double],
                            temp: Option[Double], winSpeed: Option[Double], winSpeedMaxToday: Option[Double],
                            humid: Option[Double],
                            rain: Seq[Option[Double]], hourStart: Long, hourRain: Seq[Option[Double]])
}