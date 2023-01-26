package controllers

import com.github.nscala_time.time.Imports._
import controllers.Highchart.{AxisTitle, CategorySeqData, HighchartCategoryData, XAxis, YAxis}
import models._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._

import javax.inject._
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.math.BigDecimal.RoundingMode

class Realtime @Inject()
(monitorTypeOp: MonitorTypeDB, dataCollectManagerOp: DataCollectManagerOp, instrumentOp: InstrumentDB,
 monitorStatusOp: MonitorStatusDB, groupDB: GroupDB, recordDB: RecordDB, monitorDB: MonitorDB) extends Controller {
  val overTimeLimit = 6

  case class MonitorTypeStatus(_id: String, desp: String, value: String, unit: String, instrument: String, status: String, classStr: Seq[String], order: Int)

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

  case class PowerUsage(name: String, averageUsageLastWeek: Double, usageToday: Double)

  private def getUsageMap(recordLists: Seq[RecordList]): mutable.Map[String, Double] = {
    val usageMap = mutable.Map.empty[String, Double]
    recordLists.foreach(recordList => {
      for {mtRecord <- recordList.mtMap.get(MonitorType.POWER)
           usage <- mtRecord.value
           lastUsage = usageMap.getOrElseUpdate(recordList._id.monitor, 0d)
           }
        usageMap.update(recordList._id.monitor, lastUsage + usage)
    })
    usageMap
  }

  def getPowerUsageList: Action[AnyContent] = Security.Authenticated.async {
    implicit request =>
      val userInfo = request.user

      val group = groupDB.getGroupByID(userInfo.group).get

      val now = DateTime.now()
      val weekEnd = now.withTimeAtStartOfDay().withTimeAtStartOfDay()
      val weekStart = weekEnd.minusDays(7)
      val weekUsageMapFuture =
        for (monthRecords <- recordDB.getRecordListFuture(recordDB.HourCollection)(weekStart, weekEnd, monitorDB.mvList)) yield
          getUsageMap(monthRecords)

      val dayUsageMapFuture =
        for (dayRecords <- recordDB.getRecordListFuture(recordDB.HourCollection)(now.withTimeAtStartOfDay(), DateTime.now, monitorDB.mvList)) yield
          getUsageMap(dayRecords)
      val retFuture =
        for {
          weekUsageMap <- weekUsageMapFuture
          dayUsageMap <- dayUsageMapFuture} yield {
          val mine = monitorDB.mvList.filter(group.monitors.contains(_) || userInfo.isAdmin)
            .map(m => PowerUsage(monitorDB.map(m).desc, weekUsageMap.getOrElse(m, 0d) / 7, dayUsageMap.getOrElse(m, 0d)))

          val other = monitorDB.mvList.filter(!group.monitors.contains(_) && !userInfo.isAdmin)
            .map(m => PowerUsage("N/A", weekUsageMap.getOrElse(m, 0d) / 7, dayUsageMap.getOrElse(m, 0d)))
          mine ++ other
        }

      implicit val writes: OWrites[PowerUsage] = Json.writes[PowerUsage]
      for (ret <- retFuture) yield
        Ok(Json.toJson(ret))
  }

  case class PowerUsageData(month: Int, usage: Option[Double])

  case class PowerUsageParam(monitor: String, year: Int, data: Seq[PowerUsageData])

  def upsertPowerUsage(): Action[JsValue] = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      implicit val read1: Reads[PowerUsageData] = Json.reads[PowerUsageData]
      implicit val read2: Reads[PowerUsageParam] = Json.reads[PowerUsageParam]
      val result = request.body.validate[PowerUsageParam]
      result.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          Future.successful(BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString())))
        },
        param => {
          val recordLists: Seq[RecordList] = param.data.map(powerUsageData =>
            RecordList(mtDataList = Seq(MtRecord(MonitorType.POWER, powerUsageData.usage, MonitorStatus.NormalStat)),
              _id = RecordListID(new DateTime(param.year, powerUsageData.month, 1, 0, 0).toDate, param.monitor))
          )
          for (_ <- recordDB.upsertManyRecords(recordDB.HourCollection)(recordLists)) yield
            Ok(Json.obj("ok" -> true))
        })

  }

  def loadMonitorPowerUsage(monitor: String, year: Int): Action[AnyContent] = Security.Authenticated.async {
    val start = new DateTime(year, 1, 1, 0, 0)
    val end = start.plusYears(1)
    for (records <- recordDB.getRecordListFuture(recordDB.HourCollection)(start, end, Seq(monitor))) yield {
      val monthUsageMap = records.groupBy(record => {
        val dt = new DateTime(record._id.time)
        dt.getMonthOfYear
      }).map(pair => pair._1 -> pair._2.filter(record => {
        val dt = new DateTime(record._id.time)
        dt.getDayOfMonth == 1 && dt.getHourOfDay == 0
      })).map(pair => pair._1 -> pair._2.flatMap(_.mtMap.get(MonitorType.POWER)).flatMap(_.value).sum)
      val usageList = for (month <- 1 to 12) yield
        PowerUsageData(month, monthUsageMap.get(month))
      implicit val write: OWrites[PowerUsageData] = Json.writes[PowerUsageData]
      Ok(Json.toJson(usageList))
    }
  }

  def getYearCompareChart(monitor: String): Action[AnyContent] = Security.Authenticated.async {
    val end = DateTime.now().withTimeAtStartOfDay()
    val start = end.withDayOfMonth(1).withTimeAtStartOfDay().minusYears(2)
    for (records <- recordDB.getRecordListFuture(recordDB.HourCollection)(start, end, Seq(monitor))) yield {
      val monthUsageMap = records.groupBy(record => {
        val dt = new DateTime(record._id.time)
        dt.withDayOfMonth(1).withTimeAtStartOfDay()
      }).map(pair => pair._1 -> pair._2.flatMap(_.mtMap.get(MonitorType.POWER)).flatMap(_.value).sum)

      val monthTempMap = records.groupBy(record => {
        val dt = new DateTime(record._id.time)
        dt.withDayOfMonth(1).withTimeAtStartOfDay()
      }).flatMap(pair => {
        val values = pair._2
          .flatMap(_.mtMap.get(MonitorType.TEMP))
          .flatMap(_.value)
        if (values.nonEmpty)
          Some(pair._1 -> values.sum / values.size)
        else
          None
      })


      val offsetList = (0 until 12).reverse.toList
      val categories =
        for (offset <- offsetList) yield {
          val dt = end.minusMonths(offset)
          s"${dt.toString("yyyy年M月")}"
        }
      val powerMtCase = monitorTypeOp.map(MonitorType.POWER)
      val tempMtCase = monitorTypeOp.map(MonitorType.TEMP)

      val yAxisList = Seq(
        YAxis(labels = None, title = AxisTitle(Some(Some(s"${powerMtCase.desp} (${powerMtCase.unit})"))), plotLines = None, min = Some(0)),
        YAxis(labels = None, title = AxisTitle(Some(Some(s"${tempMtCase.desp} (${tempMtCase.unit})"))), plotLines = None, opposite = true)
      )


      def getYearData(yearOffset: Int, monthMap: Map[DateTime, Double], mtCase: MonitorType) = {
        for (offset <- offsetList) yield {
          val dt = end.withDayOfMonth(1).minusMonths(offset).minusYears(yearOffset)
          monthMap.get(dt).map(BigDecimal(_).setScale(mtCase.prec, RoundingMode.HALF_EVEN).doubleValue())
        }
      }

      val thisYear = getYearData(0, monthUsageMap, powerMtCase)
      val thisYearTemp = getYearData(0, monthTempMap, tempMtCase)
      val lastYear = getYearData(1, monthUsageMap, powerMtCase)
      val lastYearTemp = getYearData(1, monthTempMap, tempMtCase)

      val series = Seq(
        CategorySeqData(name = s"去年度用電量", data = lastYear, chartType = Some("column")),
        CategorySeqData(name = s"本年度用電量", data = thisYear, chartType = Some("column")),
        CategorySeqData(name = s"去年度氣溫", data = lastYearTemp, chartType = Some("line"), yAxis = 1),
        CategorySeqData(name = s"本年度氣溫", data = thisYearTemp, chartType = Some("line"), yAxis = 1)
      )


      val chart = HighchartCategoryData(
        Map.empty[String, String],
        Map("text" -> s"${monitorDB.map(monitor).desc}同期用電比較"),
        XAxis(categories = Some(categories)),
        yAxisList,
        series)

      implicit val w1 = Json.writes[HighchartCategoryData]
      Ok(Json.toJson(chart))
    }
  }
}