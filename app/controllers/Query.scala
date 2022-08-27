package controllers

import com.github.nscala_time.time.Imports
import com.github.nscala_time.time.Imports._
import controllers.Highchart._
import models.ModelHelper.windAvg
import models._
import play.api._
import play.api.libs.json._
import play.api.mvc._

import java.nio.file.{Files, Path, Paths}
import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class Stat(avg: Option[Double],
                min: Option[Double],
                max: Option[Double],
                count: Int,
                total: Int,
                overCount: Int,
                valid: Boolean) {
  val effectPercent = {
    if (total > 0)
      Some(count.toDouble * 100 / total)
    else
      None
  }

  val isEffective = valid

  val overPercent = {
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
                      manualAuditLogOp: ManualAuditLogDB, excelUtility: ExcelUtility,
                      configuration: Configuration, earthquakeDb: EarthquakeDb) extends Controller {

  implicit val cdWrite = Json.writes[CellData]
  implicit val rdWrite = Json.writes[RowData]
  implicit val dtWrite = Json.writes[DataTab]
  val trendShowActual = configuration.getBoolean("logger.trendShowActual").getOrElse(true)

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
      val values = records.filter(rec=> MonitorStatusFilter.isMatched(statusFilter, rec.status))
        .flatMap(x => x.value)
      if (values.length == 0)
        Stat(None, None, None, 0, 0, 0, false)
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
          windAvg(windSpeed, windDir)
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

    val validMinimumCount = if(period == 1.day)
      16
    else if(period == 1.month)
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

  def historyTrendChart(monitorStr: String, monitorTypeStr: String, reportUnitStr: String, statusFilterStr: String,
                        startNum: Long, endNum: Long, outputTypeStr: String) = Security.Authenticated {
    implicit request =>
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
            (TableType.min, new DateTime(startNum).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0),
              new DateTime(endNum).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0))
        } else
          (TableType.hour, new DateTime(startNum).withMillisOfDay(0), new DateTime(endNum).withMillisOfDay(0))


      val outputType = OutputType.withName(outputTypeStr)
      val chart = trendHelper(monitors, monitorTypes, tabType,
        reportUnit, start, end, trendShowActual)(statusFilter)

      if (outputType == OutputType.excel) {
        import java.nio.file.Files
        val excelFile = excelUtility.exportChartData(chart, monitorTypes, true)
        val downloadFileName =
          if (chart.downloadFileName.isDefined)
            chart.downloadFileName.get
          else
            chart.title("text")

        Ok.sendFile(excelFile, fileName = _ =>
          s"${downloadFileName}.xlsx",
          onClose = () => {
            Files.deleteIfExists(excelFile.toPath())
          })
      } else {
        Results.Ok(Json.toJson(chart))
      }
  }

  def trendHelper(monitors: Seq[String], monitorTypes: Seq[String], tabType: TableType.Value,
                  reportUnit: ReportUnit.Value, start: DateTime, end: DateTime, showActual: Boolean)(statusFilter: MonitorStatusFilter.Value) = {
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
      if (lines.length > 0)
        Some(lines)
      else
        None
    }

    val yAxisGroup: Map[String, Seq[(String, Option[Seq[AxisLine]])]] = monitorTypes.map(mt => {
      (monitorTypeOp.map(mt).unit, getAxisLines(mt))
    }).groupBy(_._1)
    val yAxisGroupMap = yAxisGroup map {
      kv =>
        val lines: Seq[AxisLine] = kv._2.map(_._2).flatten.flatten
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
          monitor -> getPeriodReportMap(monitor, monitorTypes, tabType, period, statusFilter)(start, end)
        }

      val monitorReportMap = monitorReportPairs.toMap
      for {
        m <- monitors
        mt <- monitorTypes
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
            (time, valueOpt.getOrElse(None))
        }
        val timeStatus = timeData.map {
          t =>
            val statusOpt = for (x <- t._2) yield x._2
            statusOpt.getOrElse(None)
        }
        seqData(name = s"${monitorOp.map(m).desc}_${monitorTypeOp.map(mt).desp}",
          data = timeValues, yAxis = yAxisUnitMap(monitorTypeOp.map(mt).unit),
          tooltip = Tooltip(monitorTypeOp.map(mt).prec), statusList = timeStatus)
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
        val mt = monitorTypes(0)
        val mtCase = monitorTypeOp.map(monitorTypes(0))

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

  def getPeriodReportMap(monitor: String, mtList: Seq[String],
                         tabType: TableType.Value, period: Period,
                         statusFilter: MonitorStatusFilter.Value = MonitorStatusFilter.ValidData)
                        (start: DateTime, end: DateTime): Map[String, Map[DateTime, (Option[Double], Option[String])]] = {
    val mtRecordListMap = recordOp.getRecordMap(TableType.mapCollection(tabType))(monitor, mtList, start, end)

    val mtRecordPairs =
      for (mt <- mtList) yield {
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
              if (mt == MonitorType.WIN_DIRECTION) {
                val windDir = records
                val windSpeed = recordOp.getRecordMap(TableType.mapCollection(tabType))(monitor, List(MonitorType.WIN_SPEED), period_start, period_start + period)(MonitorType.WIN_SPEED)
                period_start -> (windAvg(windSpeed, windDir), None)
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

  def historyData(monitorStr: String, monitorTypeStr: String, tabTypeStr: String,
                  startNum: Long, endNum: Long) = Security.Authenticated.async {
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
      val emtpyCell = CellData("-", Seq.empty[String])
      for (recordList <- resultFuture) yield {
        import scala.collection.mutable.Map
        val timeMtMonitorMap = Map.empty[DateTime, Map[String, Map[String, CellData]]]
        recordList foreach {
          r =>
            val stripedTime = new DateTime(r._id.time).withSecondOfMinute(0).withMillisOfSecond(0)
            val mtMonitorMap = timeMtMonitorMap.getOrElseUpdate(stripedTime, Map.empty[String, Map[String, CellData]])
            for (mt <- monitorTypes.toSeq) {
              val monitorMap = mtMonitorMap.getOrElseUpdate(mt, Map.empty[String, CellData])
              val cellData = if (r.mtMap.contains(mt)) {
                val mtRecord = r.mtMap(mt)
                CellData(monitorTypeOp.format(mt, mtRecord.value),
                  monitorTypeOp.getCssClassStr(mtRecord), Some(mtRecord.status))
              } else
                emtpyCell

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
              cellDataList = cellDataList :+ (mtMonitorMap(mt)(m))
            else
              cellDataList = cellDataList :+ (emtpyCell)
          }
          RowData(time.getMillis, cellDataList)
        }

        val columnNames = monitorTypes.toSeq map {
          monitorTypeOp.map(_).desp
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

  def calibrationReport(startNum: Long, endNum: Long) = Security.Authenticated {
    import calibrationOp.jsonWrites
    val (start, end) = (new DateTime(startNum), new DateTime(endNum))
    val report: Seq[Calibration] = calibrationOp.calibrationReport(start, end)
    val jsonReport = report map {
      _.toJSON
    }
    Ok(Json.toJson(jsonReport))
  }

  def alarmReport(level: Int, startNum: Long, endNum: Long) = Security.Authenticated.async {
    implicit val write = Json.writes[Alarm2JSON]
    val (start, end) = (new DateTime(startNum), new DateTime(endNum))
    for (report <- alarmOp.getAlarmsFuture(level, start, end + 1.day)) yield {
      val jsonReport = report map {
        _.toJson
      }
      Ok(Json.toJson(jsonReport))
    }
  }

  def getAlarms(src: String, level: Int, startNum: Long, endNum: Long) = Security.Authenticated.async {
    implicit val write = Json.writes[Alarm2JSON]
    val (start, end) = (new DateTime(startNum), new DateTime(endNum))
    for (report <- alarmOp.getAlarmsFuture(src, level, start, end + 1.day)) yield {
      val jsonReport = report map {
        _.toJson
      }
      Ok(Json.toJson(jsonReport))
    }
  }

  def instrumentStatusReport(id: String, startNum: Long, endNum: Long) = Security.Authenticated {
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

    implicit val write = Json.writes[InstrumentReport]
    Ok(Json.toJson(InstrumentReport(columnNames, rows)))
  }

  implicit val write = Json.writes[InstrumentReport]

  def recordList(mtStr: String, startLong: Long, endLong: Long) = Security.Authenticated {
    val monitorType = (mtStr)
    implicit val w = Json.writes[Record]
    val (start, end) = (new DateTime(startLong), new DateTime(endLong))

    val recordMap = recordOp.getRecordMap(recordOp.HourCollection)(Monitor.SELF_ID, List(monitorType), start, end)
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
            recordOp.updateRecordStatus(param.time, param.mt, param.status)(TableType.mapCollection(tabType))
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

  def calibrationRecordList(start: Long, end: Long, outputTypeStr: String) = Action.async {
    implicit request =>
      val startTime = new DateTime(start)
      val endTime = new DateTime(end)
      val outputType = OutputType.withName(outputTypeStr)
      val recordListF = calibrationOp.calibrationReportFuture(startTime, endTime)
      implicit val w = Json.writes[Calibration]
      for (recordList <- recordListF) yield {
        outputType match {
          case OutputType.html =>
            implicit val write = Json.writes[CalibrationJSON]
            Ok(Json.toJson(recordList.map(_.toJSON)))
          case OutputType.excel =>
            val excelFile = excelUtility.calibrationReport(startTime, endTime, recordList)
            Ok.sendFile(excelFile, fileName = _ =>
              s"校正紀錄.xlsx",
              onClose = () => {
                Files.deleteIfExists(excelFile.toPath())
              })
        }
      }


  }

  def windRoseReport(monitor: String, monitorType: String, tabTypeStr: String, nWay: Int, start: Long, end: Long) = Security.Authenticated.async {
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
              dir <- 0 to nWay - 1
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
              for (dir <- 0 to nWay - 1)
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

  case class EarthquakeYearEvents(year:Int, events:List[EarthQuakeData])
  def getEarthquakeEvents() = Security.Authenticated {
    implicit val writes = Json.writes[EarthQuakeData]
    implicit val w1 = Json.writes[EarthquakeYearEvents]
    val db = earthquakeDb.dataMap.toList.groupBy(entry=>entry._1.getYear)
      .map(kv=>{
        val events = kv._2.map(_._2)
        EarthquakeYearEvents(year=kv._1, events=events)
      }).toList.sortBy(_.year)
    Ok(Json.toJson(db))
  }
  def getEarthquakeReportImage(dateTime:Long) = Security.Authenticated {
    val dt = new DateTime(dateTime)
    val dtStr = dt.toString("yyyyMMddHHmmss")
    val path = Paths.get(earthquakeDb.rootPath, s"EQ_REPORT/${dt.getYear}/${dtStr}.gif")
    Ok.sendFile(path.toFile)
  }
  def getEarthquakeBImage(dateTime:Long) = Security.Authenticated {
    val dt = new DateTime(dateTime)
    val dtStr = dt.toString("yyyyMMddHHmmss")
    val path = Paths.get(earthquakeDb.rootPath, s"EQ_CBPV_B/${dt.getYear}/CBPV-B_${dtStr}.png")
    Ok.sendFile(path.toFile)
  }
  def getEarthquakeDImage(dateTime:Long) = Security.Authenticated {
    val dt = new DateTime(dateTime)
    val dtStr = dt.toString("yyyyMMddHHmmss")
    val path = Paths.get(earthquakeDb.rootPath, s"EQ_CBPV_D/${dt.getYear}/CBPV-D_${dtStr}.png")
    Ok.sendFile(path.toFile)
  }

  def getWaveImage(dateTime:Long, src:String, sub:String)= Security.Authenticated {
    val dt = new DateTime(dateTime)
    val dtStr = dt.toString("yyyyMMdd")
    val path = Paths.get(earthquakeDb.rootPath, s"DAY_CBPV_${src}/${dt.getYear}/CBPV-${src}_${dtStr}.${sub}.png")
    Ok.sendFile(path.toFile)
  }

  case class InstrumentReport(columnNames: Seq[String], rows: Seq[RowData])
}