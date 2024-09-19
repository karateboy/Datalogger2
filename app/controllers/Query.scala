package controllers

import com.github.nscala_time.time.Imports
import com.github.nscala_time.time.Imports._
import controllers.Highchart._
import models.ModelHelper.directionAvg
import models._
import play.api._
import play.api.libs.json._
import play.api.mvc._

import java.nio.file.Files
import java.util.Date
import javax.inject._
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class Stat(avg: Option[Double],
                min: Option[Double],
                max: Option[Double],
                count: Int,
                total: Int,
                overCount: Int,
                valid: Boolean) {
  val effectPercent: Option[Double] = {
    if (total > 0)
      Some(count.toDouble * 100 / total)
    else
      None
  }

  val isEffective: Boolean = valid

  val overPercent: Option[Double] = {
    if (count > 0)
      Some(overCount.toDouble * 100 / total)
    else
      None
  }
}

case class CellData(v: String, cellClassName: Seq[String], status: Option[String] = None)

case class RowData(date: Long, cellData: Seq[CellData])

case class DataTab(columnNames: Seq[String], rows: Seq[RowData])

case class ManualAuditParam(reason: String, updateList: Seq[UpdateRecordParam])

case class UpdateRecordParam(time: Long, mt: String, status: String)

@Singleton
class Query @Inject()(recordOp: RecordDB, monitorTypeOp: MonitorTypeDB, monitorOp: MonitorDB,
                      instrumentStatusOp: InstrumentStatusDB, instrumentOp: InstrumentDB,
                      alarmOp: AlarmDB, calibrationOp: CalibrationDB,
                      manualAuditLogOp: ManualAuditLogDB, excelUtility: ExcelUtility) extends Controller {

  implicit val cdWrite: OWrites[CellData] = Json.writes[CellData]
  implicit val rdWrite: OWrites[RowData] = Json.writes[RowData]
  implicit val dtWrite: OWrites[DataTab] = Json.writes[DataTab]

  def getPeriodCount(start: DateTime, endTime: DateTime, p: Period) = {
    var count = 0
    var current = start
    while (current < endTime) {
      count += 1
      current += p
    }

    count
  }

  def getPeriodStatReportMap(recordListMap: Map[String, Seq[Record]], period: Period,
                             statusFilter: MonitorStatusFilter.Value = MonitorStatusFilter.ValidData)
                            (start: DateTime, end: DateTime):
  Map[String, Map[Imports.DateTime, Stat]] = {
    val mTypes = recordListMap.keys.toList
    if (mTypes.contains(MonitorType.WIN_DIRECTION)) {
      if (!mTypes.contains(MonitorType.WIN_SPEED))
        throw new Exception("風速和風向必須同時查詢")
    }

    if (period.getHours == 1) {
      throw new Exception("小時區間無Stat報表")
    }

    def periodSlice(recordList: Seq[Record], period_start: DateTime, period_end: DateTime) = {
      recordList.dropWhile {
        _.time < period_start
      }.takeWhile {
        _.time < period_end
      }
    }

    def getPeriodStat(records: Seq[Record], mt: String, period_start: DateTime, minimumValidCount: Int): Stat = {
      val values = records.filter(rec => MonitorStatusFilter.isMatched(statusFilter, rec.status))
        .flatMap(x => x.value)
      if (values.isEmpty)
        Stat(None, None, None, 0, 0, 0, valid = false)
      else {
        val min = values.min
        val max = values.max
        val sum = values.sum
        val count = values.length
        val total = new Duration(period_start, period_start + period).getStandardHours.toInt
        val overCount = if (monitorTypeOp.map(mt).std_law.isDefined) {
          values.count {
            _ > monitorTypeOp.map(mt).std_law.get
          }
        } else
          0

        val avg = if (mt == MonitorType.WIN_DIRECTION) {
          val windDir = records
          val windSpeed = periodSlice(recordListMap(MonitorType.WIN_SPEED), period_start, period_start + period)
          directionAvg(windSpeed.flatMap(_.value), windDir.flatMap(_.value))
        } else {
          if (count != 0)
            Some(sum / count)
          else
            None
        }
        Stat(
          avg = avg,
          min = Some(min),
          max = Some(max),
          total = total,
          count = count,
          overCount = overCount,
          valid = count >= minimumValidCount)
      }
    }

    val validMinimumCount = if (period == 1.day)
      16
    else if (period == 1.month)
      20
    else
      throw new Exception(s"unknown minimumValidCount for ${period}")

    val pairs = {
      for {
        mt <- mTypes
      } yield {
        val timePairs =
          for {
            period_start <- getPeriods(start, end, period)
            records = periodSlice(recordListMap(mt), period_start, period_start + period)
          } yield {
            period_start -> getPeriodStat(records, mt, period_start, validMinimumCount)
          }
        mt -> Map(timePairs: _*)
      }
    }

    Map(pairs: _*)
  }

  import models.ModelHelper._

  def scatterChart(monitorStr: String, monitorTypeStr: String, tabTypeStr: String, statusFilterStr: String,
                   startNum: Long, endNum: Long): Action[AnyContent] = Security.Authenticated.async {
    implicit request =>
      val monitors = monitorStr.split(':')
      val monitorTypeStrArray = monitorTypeStr.split(':')
      val monitorTypes = monitorTypeStrArray
      val statusFilter = MonitorStatusFilter.withName(statusFilterStr)
      val tabType = TableType.withName(tabTypeStr)
      val (start, end) =
        if (tabType == TableType.hour)
          (new DateTime(startNum).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0),
            new DateTime(endNum).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0))
        else
          (new DateTime(startNum).withSecondOfMinute(0).withMillisOfSecond(0),
            new DateTime(endNum).withSecondOfMinute(0).withMillisOfSecond(0))

      assert(monitorTypes.length == 2)

      for (chart <- compareChartHelper(monitors, monitorTypes, tabType, start, end)(statusFilter)) yield
        Results.Ok(Json.toJson(chart))
  }


  def historyTrendChart(monitorStr: String, monitorTypeStr: String, includeRaw: Boolean, reportUnitStr: String, statusFilterStr: String,
                        startNum: Long, endNum: Long, outputTypeStr: String): Action[AnyContent] = Security.Authenticated {
    val monitors = monitorStr.split(':')
    val monitorTypeStrArray = monitorTypeStr.split(':')
    val monitorTypes = monitorTypeStrArray
    val reportUnit = ReportUnit.withName(reportUnitStr)
    val statusFilter = MonitorStatusFilter.withName(statusFilterStr)
    val (tabType, start, end) =
      if (reportUnit.id <= ReportUnit.Hour.id) {
        if (reportUnit == ReportUnit.Hour)
          (TableType.hour, new DateTime(startNum).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0),
            new DateTime(endNum).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0))
        else if (reportUnit == ReportUnit.Sec)
          (TableType.second, new DateTime(startNum).withMillisOfSecond(0), new DateTime(endNum).withMillisOfSecond(0))
        else
          (TableType.min, new DateTime(startNum).withSecondOfMinute(0).withMillisOfSecond(0),
            new DateTime(endNum).withSecondOfMinute(0).withMillisOfSecond(0))
      } else
        (TableType.hour, new DateTime(startNum).withMillisOfDay(0), new DateTime(endNum).withMillisOfDay(0))


    val outputType = OutputType.withName(outputTypeStr)
    val chart = trendHelper(monitors, monitorTypes, includeRaw, tabType,
      reportUnit, start, end, LoggerConfig.config.trendShowActual)(statusFilter)

    if (outputType == OutputType.excel) {
      import java.nio.file.Files
      val actualMonitorTypes = if (includeRaw)
        monitorTypes flatMap (mt => Seq(mt, MonitorType.getRawType(mt)))
      else
        monitorTypes

      val excelFile = excelUtility.exportChartData(chart, actualMonitorTypes, true)
      val downloadFileName =
        if (chart.downloadFileName.isDefined)
          chart.downloadFileName.get
        else
          chart.title("text")

      Ok.sendFile(excelFile, fileName = _ =>
        s"${downloadFileName}.xlsx",
        onClose = () => {
          Files.deleteIfExists(excelFile.toPath)
        })
    } else {
      Results.Ok(Json.toJson(chart))
    }
  }

  private def trendHelper(monitors: Seq[String], monitorTypes: Seq[String], includeRaw: Boolean, tabType: TableType.Value,
                          reportUnit: ReportUnit.Value, start: DateTime, end: DateTime, showActual: Boolean)(statusFilter: MonitorStatusFilter.Value): HighchartData = {
    val period: Period =
      reportUnit match {
        case ReportUnit.Sec =>
          1.second
        case ReportUnit.Min =>
          1.minute
        case ReportUnit.SixMin =>
          6.minute
        case ReportUnit.TenMin =>
          10.minute
        case ReportUnit.FifteenMin =>
          15.minute
        case ReportUnit.Hour =>
          1.hour
        case ReportUnit.Day =>
          1.day
        case ReportUnit.Month =>
          1.month
        case ReportUnit.Quarter =>
          3.month
        case ReportUnit.Year =>
          1.year
      }

    val timeSeq = getPeriods(start, end, period)

    val downloadFileName = {
      val startName = start.toString("YYMMdd")
      val mtNames = monitorTypes.map {
        monitorTypeOp.map(_).desp
      }
      startName + mtNames.mkString
    }

    val title =
      reportUnit match {
        case ReportUnit.Sec =>
          s"趨勢圖 (${start.toString("YYYY年MM月dd日 HH:mm")}~${end.toString("YYYY年MM月dd日 HH:mm")})"
        case ReportUnit.Min =>
          s"趨勢圖 (${start.toString("YYYY年MM月dd日 HH:mm")}~${end.toString("YYYY年MM月dd日 HH:mm")})"
        case ReportUnit.SixMin =>
          s"趨勢圖 (${start.toString("YYYY年MM月dd日 HH:mm")}~${end.toString("YYYY年MM月dd日 HH:mm")})"
        case ReportUnit.TenMin =>
          s"趨勢圖 (${start.toString("YYYY年MM月dd日 HH:mm")}~${end.toString("YYYY年MM月dd日 HH:mm")})"
        case ReportUnit.FifteenMin =>
          s"趨勢圖 (${start.toString("YYYY年MM月dd日 HH:mm")}~${end.toString("YYYY年MM月dd日 HH:mm")})"
        case ReportUnit.Hour =>
          s"趨勢圖 (${start.toString("YYYY年MM月dd日 HH:mm")}~${end.toString("YYYY年MM月dd日 HH:mm")})"
        case ReportUnit.Day =>
          s"趨勢圖 (${start.toString("YYYY年MM月dd日")}~${end.toString("YYYY年MM月dd日")})"
        case ReportUnit.Month =>
          s"趨勢圖 (${start.toString("YYYY年MM月")}~${end.toString("YYYY年MM月dd日")})"
        case ReportUnit.Quarter =>
          s"趨勢圖 (${start.toString("YYYY年MM月")}~${end.toString("YYYY年MM月dd日")})"
        case ReportUnit.Year =>
          s"趨勢圖 (${start.toString("YYYY年")}~${end.toString("YYYY年")})"
      }

    def getAxisLines(mt: String) = {
      val mtCase = monitorTypeOp.map(mt)
      val std_law_line =
        if (mtCase.std_law.isEmpty)
          None
        else
          Some(AxisLine("#FF0000", 2, mtCase.std_law.get, Some(AxisLineLabel("right", "法規值"))))

      val lines = Seq(std_law_line, None).filter {
        _.isDefined
      }.map {
        _.get
      }
      if (lines.nonEmpty)
        Some(lines)
      else
        None
    }

    val yAxisGroup: Map[String, Seq[(String, Option[Seq[AxisLine]])]] = monitorTypes.map(mt => {
      (monitorTypeOp.map(mt).unit, getAxisLines(mt))
    }).groupBy(_._1)
    val yAxisGroupMap = yAxisGroup map {
      kv =>
        val lines: Seq[AxisLine] = kv._2.flatMap(_._2).flatten
        if (lines.nonEmpty)
          kv._1 -> YAxis(None, AxisTitle(Some(Some(s"${kv._1}"))), Some(lines))
        else
          kv._1 -> YAxis(None, AxisTitle(Some(Some(s"${kv._1}"))), None)
    }
    val yAxisIndexList = yAxisGroupMap.toList.zipWithIndex
    val yAxisUnitMap = yAxisIndexList.map(kv => kv._1._1 -> kv._2).toMap
    val yAxisList = yAxisIndexList.map(_._1._2)

    def getSeries() = {

      val monitorReportPairs =
        for {
          monitor <- monitors
        } yield {
          monitor -> getPeriodReportMap(monitor, monitorTypes, tabType, period, includeRaw, statusFilter)(start, end)
        }

      val monitorReportMap = monitorReportPairs.toMap

      val actualMonitorTypes = if (includeRaw)
        monitorTypes flatMap (mt => Seq(mt, MonitorType.getRawType(mt)))
      else
        monitorTypes

      for {
        m <- monitors
        mt <- actualMonitorTypes
        valueMap = monitorReportMap(m)(mt)
      } yield {
        val timeData =
          if (showActual) {
            timeSeq.map { time =>
              if (valueMap.contains(time))
                (time.getMillis, Some(valueMap(time)))
              else
                (time.getMillis, None)
            }
          } else {
            for (time <- valueMap.keys.toList.sorted) yield {
              (time.getMillis, Some(valueMap(time)))
            }
          }
        val timeValues = timeData.map {
          t =>
            val time = t._1
            val valueOpt = for (x <- t._2) yield x._1
            (time, valueOpt.flatten)
        }
        val timeStatus = timeData.map {
          t =>
            val statusOpt = for (x <- t._2) yield x._2
            statusOpt.flatten
        }
        if (monitorTypeOp.map.contains(mt))
          seqData(name = s"${monitorOp.map(m).desc}_${monitorTypeOp.map(mt).desp}",
            data = timeValues, yAxis = yAxisUnitMap(monitorTypeOp.map(mt).unit),
            tooltip = Tooltip(monitorTypeOp.map(mt).prec), statusList = timeStatus)
        else {
          val realMt = MonitorType.getRealType(mt)
          seqData(name = s"${monitorOp.map(m).desc}_${monitorTypeOp.map(realMt).desp}原始值",
            data = timeValues, yAxis = yAxisUnitMap(monitorTypeOp.map(realMt).unit),
            tooltip = Tooltip(monitorTypeOp.map(realMt).prec), statusList = timeStatus)
        }
      }
    }

    val series = getSeries()

    val xAxis = {
      val duration = new Duration(start, end)
      if (duration.getStandardDays > 2)
        XAxis(None, gridLineWidth = Some(1), None)
      else
        XAxis(None)
    }

    val chart =
      if (monitorTypes.length == 1) {
        val mt = monitorTypes.head
        val mtCase = monitorTypeOp.map(monitorTypes.head)

        HighchartData(
          Map("type" -> "line"),
          Map("text" -> title),
          xAxis,

          Seq(YAxis(None, AxisTitle(Some(Some(s"${mtCase.desp} (${mtCase.unit})"))), getAxisLines(mt))),
          series,
          Some(downloadFileName))
      } else {
        HighchartData(
          Map("type" -> "line"),
          Map("text" -> title),
          xAxis,
          yAxisList,
          series,
          Some(downloadFileName))
      }

    chart
  }

  private def getPeriodReportMap(monitor: String, mtList: Seq[String],
                                 tabType: TableType.Value, period: Period,
                                 includeRaw: Boolean = false,
                                 statusFilter: MonitorStatusFilter.Value = MonitorStatusFilter.ValidData)
                                (start: DateTime, end: DateTime): Map[String, Map[DateTime, (Option[Double], Option[String])]] = {
    val mtRecordListMap = recordOp.getRecordMap(TableType.mapCollection(tabType))(monitor, mtList, start, end, includeRaw)
    val actualMonitorTypes = if (includeRaw)
      mtList flatMap { mt => Seq(mt, MonitorType.getRawType(mt)) }
    else
      mtList

    val mtRecordPairs =
      for (mt <- actualMonitorTypes) yield {
        val recordList = mtRecordListMap(mt)

        def periodSlice(period_start: DateTime, period_end: DateTime) = {
          recordList.dropWhile {
            _.time < period_start
          }.takeWhile {
            _.time < period_end
          }
        }

        val pairs =
          if ((tabType == TableType.hour && period.getHours == 1) || (tabType == TableType.min && period.getMinutes == 1)) {
            recordList.filter { r => MonitorStatusFilter.isMatched(statusFilter, r.status) }.map { r => r.time -> (r.value, Some(r.status)) }
          } else {
            for {
              period_start <- getPeriods(start, end, period)
              records = periodSlice(period_start, period_start + period) if records.length > 0
            } yield {
              if (mt == MonitorType.WIN_DIRECTION || mt == MonitorType.getRawType(MonitorType.WIN_DIRECTION)) {
                val windDir = records
                val windSpeed = recordOp.getRecordMap(TableType.mapCollection(tabType))(monitor, List(MonitorType.WIN_SPEED), period_start, period_start + period)(MonitorType.WIN_SPEED)
                period_start -> (directionAvg(windSpeed.flatMap(_.value), windDir.flatMap(_.value)), None)
              } else {
                val values = records.flatMap { r => r.value }
                if (values.nonEmpty)
                  period_start -> (Some(values.sum / values.length), None)
                else
                  period_start -> (None, None)
              }
            }
          }
        mt -> Map(pairs: _*)
      }
    mtRecordPairs.toMap
  }

  def getPeriods(start: DateTime, endTime: DateTime, d: Period): List[DateTime] = {
    import scala.collection.mutable.ListBuffer

    val buf = ListBuffer[DateTime]()
    var current = start
    while (current < endTime) {
      buf.append(current)
      current += d
    }

    buf.toList
  }

  private def compareChartHelper(monitors: Seq[String], monitorTypes: Seq[String], tabType: TableType.Value,
                                 start: DateTime, end: DateTime)(statusFilter: MonitorStatusFilter.Value): Future[ScatterChart] = {

    val downloadFileName = {
      val startName = start.toString("YYMMdd")
      val mtNames = monitorTypes.map {
        monitorTypeOp.map(_).desp
      }
      startName + mtNames.mkString
    }

    val mtName = monitorTypes.map(monitorTypeOp.map(_).desp).mkString(" vs ")
    val title =
      tabType match {
        case TableType.min =>
          s"$mtName 對比圖 (${start.toString("YYYY年MM月dd日 HH:mm")}~${end.toString("YYYY年MM月dd日 HH:mm")})"
        case TableType.hour =>
          s"$mtName 對比圖 (${start.toString("YYYY年MM月dd日 HH:mm")}~${end.toString("YYYY年MM月dd日 HH:mm")})"
      }

    def getAxisLines(mt: String) = {
      val mtCase = monitorTypeOp.map(mt)
      val std_law_line =
        if (mtCase.std_law.isEmpty)
          None
        else
          Some(AxisLine("#FF0000", 2, mtCase.std_law.get, Some(AxisLineLabel("right", "法規值"))))

      val lines = Seq(std_law_line, None).filter {
        _.isDefined
      }.map {
        _.get
      }
      if (lines.nonEmpty)
        Some(lines)
      else
        None
    }

    def getSeriesFuture: Future[Seq[ScatterSeries]] = {
      val seqFuture = monitors.map(m => {
        for (records <- recordOp.getRecordListFuture(TableType.mapCollection(tabType))(start, end, Seq(m))) yield {
          val data = records.flatMap(rec => {
            for {mt1 <- rec.mtMap.get(monitorTypes(0)) if MonitorStatusFilter.isMatched(statusFilter, mt1.status)
                 mt2 <- rec.mtMap.get(monitorTypes(1)) if MonitorStatusFilter.isMatched(statusFilter, mt2.status)
                 mt1Value <- mt1.value
                 mt2Value <- mt2.value} yield Seq(mt1Value, mt2Value)
          })
          ScatterSeries(name =
            s"${monitorOp.map(m).desc}(${monitorTypeOp.map(monitorTypes.head).desp} ${monitorTypeOp.map(monitorTypes(1)).desp})", data = data)
        }
      })
      for (ret <- Future.sequence(seqFuture)) yield {
        val combinedData = ret.flatMap(series => series.data)
        if (ret.size > 1)
          ret :+ ScatterSeries(name = monitors.map(m => s"${monitorOp.map(m).desc}").mkString("+"), data = combinedData)
        else
          ret
      }
    }

    val mt1 = monitorTypeOp.map(monitorTypes.head)
    val mt2 = monitorTypeOp.map(monitorTypes(1))

    for (series <- getSeriesFuture) yield
      ScatterChart(Map("type" -> "scatter", "zoomType" -> "xy"),
        Map("text" -> title),
        ScatterAxis(Title(enabled = true, s"${mt1.desp}(${mt1.unit})"), getAxisLines(monitorTypes.head)),
        ScatterAxis(Title(enabled = true, s"${mt2.desp}(${mt2.unit})"), getAxisLines(monitorTypes(1))),
        series, Some(downloadFileName))
  }

  def historyData(monitorStr: String, monitorTypeStr: String, tabTypeStr: String, includeRaw: Boolean,
                  startNum: Long, endNum: Long): Action[AnyContent] = Security.Authenticated.async {
    implicit request =>
      val monitors = monitorStr.split(":")
      val monitorTypes = monitorTypeStr.split(':')
      val tabType = TableType.withName(tabTypeStr)
      val (start, end) =
        if (tabType == TableType.hour) {
          val orignal_start = new DateTime(startNum)
          val orignal_end = new DateTime(endNum)
          (orignal_start.withMinuteOfHour(0), orignal_end.withMinute(0) + 1.hour)
        } else {
          (new DateTime(startNum), new DateTime(endNum))
        }

      val resultFuture = recordOp.getRecordListFuture(TableType.mapCollection(tabType))(start, end, monitors)
      val emptyCell = CellData("-", Seq.empty[String])
      for (recordList <- resultFuture) yield {
        val timeMtMonitorMap = mutable.Map.empty[DateTime, mutable.Map[String, mutable.Map[String, Seq[CellData]]]]
        recordList foreach {
          r =>
            val stripedTime = new DateTime(r._id.time).withSecondOfMinute(0).withMillisOfSecond(0)
            val mtMonitorMap = timeMtMonitorMap.getOrElseUpdate(stripedTime, mutable.Map.empty[String, mutable.Map[String, Seq[CellData]]])
            for (mt <- monitorTypes.toSeq) {
              val monitorMap = mtMonitorMap.getOrElseUpdate(mt, mutable.Map.empty[String, Seq[CellData]])
              val cellData = if (r.mtMap.contains(mt)) {
                val mtRecord = r.mtMap(mt)
                val valueCell = CellData(monitorTypeOp.format(mt, mtRecord.value),
                  monitorTypeOp.getCssClassStr(mtRecord), Some(mtRecord.status))
                val rawCell = CellData(monitorTypeOp.format(mt, mtRecord.rawValue),
                  monitorTypeOp.getCssClassStr(mtRecord), Some(mtRecord.status))
                if (includeRaw)
                  Seq(valueCell, rawCell)
                else
                  Seq(valueCell)
              } else {
                if (includeRaw)
                  Seq(emptyCell, emptyCell)
                else
                  Seq(emptyCell)
              }

              monitorMap.update(r._id.monitor, cellData)
            }
        }
        val timeList = timeMtMonitorMap.keys.toList.sorted
        val timeRows: Seq[RowData] = for (time <- timeList) yield {
          val mtMonitorMap = timeMtMonitorMap(time)
          var cellDataList = Seq.empty[CellData]
          for {
            mt <- monitorTypes
            m <- monitors
          } {
            val monitorMap = mtMonitorMap(mt)
            if (monitorMap.contains(m))
              cellDataList = cellDataList ++ mtMonitorMap(mt)(m)
            else {
              if (includeRaw)
                cellDataList = cellDataList ++ Seq(emptyCell, emptyCell)
              else
                cellDataList = cellDataList ++ Seq(emptyCell)
            }
          }
          RowData(time.getMillis, cellDataList)
        }

        val columnNames = monitorTypes.toSeq flatMap { mt => {
          val mtCase: MonitorType = monitorTypeOp.map(mt)
          if (includeRaw)
            Seq(mtCase.desp, s"${mtCase.desp} 原始值")
          else
            Seq(mtCase.desp)
        }
        }
        Ok(Json.toJson(DataTab(columnNames, timeRows)))
      }
  }

  def historyReport(monitorTypeStr: String, tabTypeStr: String,
                    startNum: Long, endNum: Long) = Security.Authenticated.async {
    implicit request =>

      val monitorTypes = monitorTypeStr.split(':')
      val tabType = TableType.withName(tabTypeStr)
      val (start, end) =
        if (tabType == TableType.hour) {
          val orignal_start = new DateTime(startNum)
          val orignal_end = new DateTime(endNum)

          (orignal_start.withMinuteOfHour(0), orignal_end.withMinute(0) + 1.hour)
        } else {
          val timeStart = new DateTime(startNum)
          val timeEnd = new DateTime(endNum)
          val timeDuration = new Duration(timeStart, timeEnd)
          tabType match {
            case TableType.min =>
              if (timeDuration.getStandardMinutes > 60 * 12)
                (timeStart, timeStart + 12.hour)
              else
                (timeStart, timeEnd)
            case TableType.second =>
              if (timeDuration.getStandardSeconds > 60 * 60)
                (timeStart, timeStart + 1.hour)
              else
                (timeStart, timeEnd)
          }
        }
      val timeList = tabType match {
        case TableType.hour =>
          getPeriods(start, end, 1.hour)
        case TableType.min =>
          getPeriods(start, end, 1.minute)
        case TableType.second =>
          getPeriods(start, end, 1.second)
      }

      val f = recordOp.getRecordListFuture(TableType.mapCollection(tabType))(start, end)

      for (recordList <- f) yield
        Ok(Json.toJson(recordList))
  }

  def calibrationReport(startNum: Long, endNum: Long): Action[AnyContent] = Security.Authenticated {
    import calibrationOp.writes
    val (start, end) = (new DateTime(startNum), new DateTime(endNum))
    val report: Seq[Calibration] = calibrationOp.calibrationReport(start, end)
    Ok(Json.toJson(report))
  }

  implicit val alarmWrite: OWrites[Alarm] = alarmOp.write

  def alarmReport(level: Int, startNum: Long, endNum: Long): Action[AnyContent] = Security.Authenticated.async {
    val (start, end) = (new DateTime(startNum), new DateTime(endNum))
    for (alarms <- alarmOp.getAlarmsFuture(level, start, end)) yield
      Ok(Json.toJson(alarms))
  }

  def getAlarms(src: String, level: Int, startNum: Long, endNum: Long): Action[AnyContent] = Security.Authenticated.async {
    val (start, end) = (new DateTime(startNum), new DateTime(endNum))
    for (report <- alarmOp.getAlarmsFuture(src, level, start, end)) yield
      Ok(Json.toJson(report))
  }

  def instrumentStatusReport(id: String, startNum: Long, endNum: Long): Action[AnyContent] = Security.Authenticated {
    val (start, end) = (new DateTime(startNum).withMillisOfDay(0),
      new DateTime(endNum).withMillisOfDay(0))

    val report = instrumentStatusOp.query(id, start, end + 1.day)
    val keyList: Seq[String] = if (report.isEmpty)
      List.empty[String]
    else
      report.map {
        _.statusList
      }.maxBy {
        _.length
      }.map {
        _.key
      }

    val reportMap = for {
      record <- report
      time = record.time
    } yield {
      (time, record.statusList.map { s => (s.key -> s.value) }.toMap)
    }

    val statusTypeMap = instrumentOp.getStatusTypeMap(id)

    val columnNames: Seq[String] = keyList.map(statusTypeMap).map(_.desc)
    val rows = for (report <- reportMap) yield {
      val cellData = for (key <- keyList) yield {
        val instrumentStatusType = statusTypeMap(key)
        if (report._2.contains(key))
          CellData(instrumentStatusOp.formatValue(report._2(key), instrumentStatusType.prec.getOrElse(2)), Seq.empty[String])
        else
          CellData("-", Seq.empty[String])
      }
      RowData(report._1.getMillis, cellData)
    }

    Ok(Json.toJson(InstrumentReport(columnNames, rows)))
  }

  def recordList(mtStr: String, startLong: Long, endLong: Long): Action[AnyContent] = Security.Authenticated {
    val monitorType = mtStr
    implicit val w = Json.writes[Record]
    val (start, end) = (new DateTime(startLong), new DateTime(endLong))

    val recordMap = recordOp.getRecordMap(recordOp.HourCollection)(Monitor.activeId, List(monitorType), start, end)
    Ok(Json.toJson(recordMap(monitorType)))
  }

  def updateRecord(tabTypeStr: String) = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      val user = request.user
      implicit val read = Json.reads[UpdateRecordParam]
      implicit val maParamRead = Json.reads[ManualAuditParam]
      val result = request.body.validate[ManualAuditParam]
      val tabType = TableType.withName(tabTypeStr)
      result.fold(
        err => {
          Logger.error(JsError.toJson(err).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(err).toString()))
        },
        maParam => {
          for (param <- maParam.updateList) {
            recordOp.updateRecordStatus(TableType.mapCollection(tabType))(param.time, param.mt, param.status)
            val log = ManualAuditLog(new DateTime(param.time), mt = param.mt, modifiedTime = DateTime.now(),
              operator = user.name, changedStatus = param.status, reason = maParam.reason)
            manualAuditLogOp.upsertLog(log)
          }
        })
      Ok(Json.obj("ok" -> true))
  }

  def manualAuditHistoryReport(start: Long, end: Long) = Security.Authenticated.async {
    val startTime = new DateTime(start)
    val endTime = new DateTime(end)
    implicit val w = Json.writes[ManualAuditLog2]
    val logFuture = manualAuditLogOp.queryLog2(startTime, endTime)
    val resultF =
      for {
        logList <- logFuture
      } yield {
        Ok(Json.toJson(logList))
      }

    resultF
  }

  // FIXME Bypass security check
  def hourRecordList(start: Long, end: Long) = Action.async {
    implicit request =>

      val startTime = new DateTime(start)
      val endTime = new DateTime(end)
      val recordListF = recordOp.getRecordListFuture(recordOp.HourCollection)(startTime, endTime)
      for (recordList <- recordListF) yield {
        Ok(Json.toJson(recordList))
      }
  }

  // FIXME Bypass security check
  def minRecordList(start: Long, end: Long) = Action.async {
    implicit request =>
      val startTime = new DateTime(start)
      val endTime = new DateTime(end)
      val recordListF = recordOp.getRecordListFuture(recordOp.MinCollection)(startTime, endTime)

      for (recordList <- recordListF) yield {
        Ok(Json.toJson(recordList))
      }
  }

  case class CalibrationQueryResult(calibrations: Seq[Calibration], zeroChart: HighchartData, spanChart: HighchartData)

  def calibrationRecordList(start: Long, end: Long, outputTypeStr: String): Action[AnyContent] = Action.async {
    val startTime = new DateTime(start)
    val endTime = new DateTime(end)
    val outputType = OutputType.withName(outputTypeStr)
    val recordListF = calibrationOp.calibrationReportFuture(startTime, endTime)
    implicit val w = Json.writes[Calibration]
    for (records <- recordListF) yield {
      outputType match {
        case OutputType.html =>
          implicit val write2: OWrites[CalibrationQueryResult] = Json.writes[CalibrationQueryResult]

          val result = CalibrationQueryResult(records,
            calibrationTrendChart(records, zero = true, startTime, endTime),
            calibrationTrendChart(records, zero = false, startTime, endTime)
          )
          Ok(Json.toJson(result))
        case OutputType.excel =>
          val excelFile = excelUtility.calibrationReport(startTime, endTime, records)
          Ok.sendFile(excelFile, fileName = _ =>
            s"校正紀錄.xlsx",
            onClose = () => {
              Files.deleteIfExists(excelFile.toPath)
            })
        case OutputType.excel2 =>
          val excelFile = excelUtility.multiCalibrationReport(startTime, endTime, records)
          Ok.sendFile(excelFile, fileName = _ =>
            s"多點校正紀錄.xlsx",
            onClose = () => {
              Files.deleteIfExists(excelFile.toPath)
            })
      }
    }
  }

  private def calibrationTrendChart(calibrationList: Seq[Calibration],
                                    zero: Boolean,
                                    start: DateTime,
                                    end: DateTime): HighchartData = {

    val monitorTypes = calibrationList.map(_.monitorType).toSet.toList
    val downloadFileName = "校正趨勢圖"

    val calibrationMap = mutable.Map.empty[String, mutable.Map[Date, Option[Double]]]
    for {
      calibration <- calibrationList
    } {
      val mtMap = calibrationMap.getOrElseUpdate(calibration.monitorType, mutable.Map.empty[Date, Option[Double]])
      if (zero)
        mtMap += calibration.startTime -> calibration.zero_val
      else
        mtMap += calibration.startTime -> calibration.span_val
    }

    val calibrationName = if (zero)
      "零點校正"
    else
      "全幅校正"

    val title =
      s"${calibrationName}趨勢圖 (${start.toString("YYYY年MM月dd日")}~${end.toString("YYYY年MM月dd日")})"

    def getAxisLines(mt: String): Option[Seq[AxisLine]] = {
      val mtCase = monitorTypeOp.map(mt)

      val standardOpt =
        if (zero) {
          for (zd <- mtCase.zd_law) yield
            (-zd, zd)
        } else {
          for (span <- mtCase.span; dev <- mtCase.span_dev_law) yield
            (span * (1 - dev / 100), span * (1 + dev / 100))
        }


      for (standard <- standardOpt) yield
        Seq(AxisLine("#FF0000", 2, standard._1, Some(AxisLineLabel("right", s"${calibrationName}下限值"))),
          AxisLine("#FF0000", 2, standard._2, Some(AxisLineLabel("right", s"${calibrationName}上限值"))))
    }

    val yAxisGroup: Map[String, Seq[(String, Option[Seq[AxisLine]])]] = monitorTypes.map(mt => {
      (monitorTypeOp.map(mt).desp, getAxisLines(mt))
    }).groupBy(_._1)

    val yAxisGroupMap = yAxisGroup map {
      kv =>
        val lines: Seq[AxisLine] = kv._2.flatMap(_._2).flatten
        if (lines.nonEmpty) {
          val softMax = lines.map(_.value).max
          val softMin = lines.map(_.value).min
          kv._1 -> YAxis(None, title = AxisTitle(Some(Some(s"${kv._1}"))), plotLines = Some(lines), softMax = Some(softMax), softMin = Some(softMin))
        } else
          kv._1 -> YAxis(None, title = AxisTitle(Some(Some(s"${kv._1}"))), plotLines = None)
    }
    val yAxisIndexList = yAxisGroupMap.toList.zipWithIndex
    val yAxisUnitMap = yAxisIndexList.map(kv => kv._1._1 -> kv._2).toMap
    val yAxisList = yAxisIndexList.map(_._1._2)

    def getSeries: Seq[seqData] = {
      for {
        mt <- monitorTypes
        valueMap = calibrationMap.getOrElseUpdate(mt, mutable.Map.empty[Date, Option[Double]])
      } yield {
        val timeData =
          for (time <- valueMap.keys.toList.sorted) yield {
            (time.getTime, Some(valueMap(time)))
          }

        val timeValues = timeData.map {
          t =>
            val time = t._1
            val valueOpt = for (x <- t._2) yield x
            (time, valueOpt.flatten)
        }

        val timeStatus = timeData.map {
          _ =>
            if (zero)
              Some(MonitorStatus.ZeroCalibrationStat)
            else
              Some(MonitorStatus.SpanCalibrationStat)
        }
        seqData(name = s"${monitorTypeOp.map(mt).desp}",
          data = timeValues, yAxis = yAxisUnitMap(monitorTypeOp.map(mt).desp),
          tooltip = Tooltip(monitorTypeOp.map(mt).prec), statusList = timeStatus)
      }
    }

    val series = getSeries

    val xAxis: XAxis = {
      val duration = new Duration(start, end)
      if (duration.getStandardDays > 2)
        XAxis(None, gridLineWidth = Some(1), None)
      else
        XAxis(None)
    }

    val chart =
      if (monitorTypes.length == 1) {
        val mt = monitorTypes.head
        val mtCase = monitorTypeOp.map(monitorTypes.head)

        HighchartData(
          Map("type" -> "line"),
          Map("text" -> title),
          xAxis,
          Seq(YAxis(None, AxisTitle(Some(Some(s"${mtCase.desp} (${mtCase.unit})"))), getAxisLines(mt))),
          series,
          Some(downloadFileName))
      } else {
        HighchartData(
          Map("type" -> "line"),
          Map("text" -> title),
          xAxis,
          yAxisList,
          series,
          Some(downloadFileName))
      }

    chart
  }

  def windRoseReport(monitor: String, monitorType: String, tabTypeStr: String, nWay: Int, start: Long, end: Long): Action[AnyContent] = Security.Authenticated.async {
    implicit request =>
      assert(nWay == 8 || nWay == 16 || nWay == 32)
      try {
        val mtCase = monitorTypeOp.map(monitorType)
        val levels = monitorTypeOp.map(monitorType).levels.getOrElse(Seq(1.0, 2.0, 3.0))
        val tableType = TableType.withName(tabTypeStr)
        val colName: String = tableType match {
          case TableType.hour =>
            recordOp.HourCollection
          case TableType.min =>
            recordOp.MinCollection
        }
        val f = recordOp.getWindRose(colName)(monitor, monitorType, new DateTime(start), new DateTime(end), levels.toList, nWay)
        f onFailure (errorHandler)
        for (windMap <- f) yield {
          assert(windMap.nonEmpty)

          val dirMap =
            Map(
              (0 -> "北"), (1 -> "北北東"), (2 -> "東北"), (3 -> "東北東"), (4 -> "東"),
              (5 -> "東南東"), (6 -> "東南"), (7 -> "南南東"), (8 -> "南"),
              (9 -> "南南西"), (10 -> "西南"), (11 -> "西西南"), (12 -> "西"),
              (13 -> "西北西"), (14 -> "西北"), (15 -> "北北西"))
          val dirStrSeq =
            for {
              dir <- 0 until nWay
              dirKey = if (nWay == 8)
                dir * 2
              else if (nWay == 32) {
                if (dir % 2 == 0) {
                  dir / 2
                } else
                  dir + 16
              } else
                dir
            } yield dirMap.getOrElse(dirKey, "")

          var previous = 0d
          val concLevels = levels.flatMap { l =>
            if (l == levels.head && l == levels.last) {
              previous = l
              val s1 = "< %s%s".format(monitorTypeOp.format(monitorType, Some(l)), mtCase.unit)
              val s2 = "> %s%s".format(monitorTypeOp.format(monitorType, Some(l)), mtCase.unit)
              List(s1, s2)
            } else if (l == levels.head) {
              previous = l
              List("< %s%s".format(monitorTypeOp.format(monitorType, Some(l)), mtCase.unit))
            } else if (l == levels.last) {
              val s1 = "%s~%s%s".format(monitorTypeOp.format(monitorType, Some(previous)),
                monitorTypeOp.format(monitorType, Some(l)), mtCase.unit)
              val s2 = "> %s%s".format(monitorTypeOp.format(monitorType, Some(l)), mtCase.unit)
              List(s1, s2)
            } else {
              val s1 = "%s~%s%s".format(monitorTypeOp.format(monitorType, Some(previous)),
                monitorTypeOp.format(monitorType, Some(l)), mtCase.unit)
              val ret = List(s1)
              previous = l
              ret
            }
          }
          val series = for {
            level <- 0 to levels.length
          } yield {
            val data =
              for (dir <- 0 until nWay)
                yield (dir.toLong, Some(windMap(dir)(level)))

            seqData(concLevels(level), data)
          }

          val title = ""
          val chart = HighchartData(
            scala.collection.immutable.Map("polar" -> "true", "type" -> "column"),
            scala.collection.immutable.Map("text" -> title),
            XAxis(Some(dirStrSeq)),
            Seq(YAxis(None, AxisTitle(Some(Some(""))), None)),
            series)
          Ok(Json.toJson(chart))
        }
      } catch {
        case ex: Throwable =>
          Logger.error(ex.getMessage, ex)
          Future {
            BadRequest("無資料")
          }
      }
  }

  case class InstrumentReport(columnNames: Seq[String], rows: Seq[RowData])

  implicit val instrumentReportWrite: OWrites[InstrumentReport] = Json.writes[InstrumentReport]

  def aqiTrendChart(monitorStr: String, isDailyAqi: Boolean, startNum: Long, endNum: Long, outputTypeStr: String): Action[AnyContent] =
    Security.Authenticated.async {
      val monitors = monitorStr.split(':')
      val start = new DateTime(startNum).withTimeAtStartOfDay()
      val end = new DateTime(endNum).withTimeAtStartOfDay() + 1.day
      val outputType = OutputType.withName(outputTypeStr)

      def getAqiMap(m: String): Future[Map[DateTime, AqiReport]] = {
        if (isDailyAqi) {
          val aqiListFuture: List[Future[(DateTime, AqiReport)]] =
            for (current <- getPeriods(start, end, 1.day)) yield {
              for (v <- AQI.getMonitorDailyAQI(m, current)(recordOp)) yield
                (current, v)
            }
          for (ret <- Future.sequence(aqiListFuture)) yield
            ret.toMap
        } else {
          AQI.getRealtimeAqiTrend(m, start, end)(recordOp)
        }
      }

      val timeSet = if (isDailyAqi)
        getPeriods(start, end, 1.day)
      else
        getPeriods(start, end, 1.hour)

      val monitorAqiPairFutures =
        for (m <- monitors.toList) yield {
          for (map <- getAqiMap(m)) yield
            m -> map
        }

      for (monitorAqiPair <- Future.sequence(monitorAqiPairFutures)) yield {
        val monitorAqiMap = monitorAqiPair.toMap
        val title = "AQI歷史趨勢圖"
        val timeSeq = timeSet.zipWithIndex

        val series = for {
          m <- monitors
          timeData = timeSeq.map { t =>
            val time = t._1
            val x = t._2
            if (monitorAqiMap(m).contains(time)) {
              val aqi = monitorAqiMap(m).get(time).flatMap(_.aqi)
              (time.getMillis, aqi)
            } else
              (time.getMillis, None)
          }
        } yield {
          seqData(monitorOp.map(m).desc, timeData)
        }
        val timeStrSeq =
          if (isDailyAqi)
            timeSeq.map(_._1.toString("YY/MM/dd"))
          else
            timeSeq.map(_._1.toString("MM/dd HH:00"))

        val chart = HighchartData(
          scala.collection.immutable.Map("type" -> "column"),
          scala.collection.immutable.Map("text" -> title),
          XAxis(None),
          Seq(YAxis(None, AxisTitle(Some(Some(""))), None)),
          series)

        if (outputType == OutputType.excel) {
          val excelFile = excelUtility.exportChartData(chart, Array(0), showSec = false)
          Ok.sendFile(excelFile, fileName = _ =>
            s"AQI查詢.xlsx",
            onClose = () => {
              Files.deleteIfExists(excelFile.toPath)
            })
        } else
          Ok(Json.toJson(chart))
      }
    }
}