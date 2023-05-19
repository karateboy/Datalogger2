package controllers

import com.github.nscala_time.time.Imports._
import controllers.Highchart._
import models.ModelHelper.windAvg
import models._
import play.api._
import play.api.libs.json._
import play.api.mvc._

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class Stat(
                 avg: Option[Double],
                 min: Option[Double],
                 max: Option[Double],
                 count: Int,
                 total: Int,
                 overCount: Int) {
  val effectPercent = {
    if (total > 0)
      Some(count.toDouble * 100 / total)
    else
      None
  }

  val isEffective = {
    effectPercent.isDefined && effectPercent.get > 75
  }
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
class Query @Inject()(recordOp: RecordOp, monitorTypeOp: MonitorTypeOp, monitorOp: MonitorOp,
                      instrumentStatusOp: InstrumentStatusOp, instrumentOp: InstrumentOp,
                      alarmOp: AlarmOp, calibrationOp: CalibrationOp, groupOp: GroupOp,
                      configuration: Configuration,
                      manualAuditLogOp: ManualAuditLogOp, excelUtility: ExcelUtility,
                      errorReportOp: ErrorReportOp) extends Controller {

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

  def getPeriodStatReportMap(recordListMap: Map[String, Seq[Record]], period: Period, statusFilter: List[String] = List("010"))(start: DateTime, end: DateTime): Map[String, Map[DateTime, Stat]] = {
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

    def getPeriodStat(records: Seq[Record], mt: String, period_start: DateTime) = {
      if (records.length == 0)
        Stat(None, None, None, 0, 0, 0)
      else {
        val values = records.map { r => r.value }
        val min = values.min
        val max = values.max
        val sum = values.sum
        val count = records.length
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
          sum / total
        }
        Stat(
          avg = Some(avg),
          min = Some(min),
          max = Some(max),
          total = total,
          count = count,
          overCount = overCount)
      }
    }

    val pairs = {
      for {
        mt <- mTypes
      } yield {
        val timePairs =
          for {
            period_start <- getPeriods(start, end, period)
            records = periodSlice(recordListMap(mt), period_start, period_start + period)
          } yield {
            period_start -> getPeriodStat(records, mt, period_start)
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
      val groupID = request.user.group
      val monitors = monitorStr.split(':')
      val monitorTypeStrArray = monitorTypeStr.split(':')
      val monitorTypes = monitorTypeStrArray
      val reportUnit = ReportUnit.withName(reportUnitStr)
      val statusFilter = MonitorStatusFilter.withName(statusFilterStr)
      val (tabType, start, end) =
        if (reportUnit.id <= ReportUnit.Hour.id) {
          val tab = if (reportUnit == ReportUnit.Hour)
            TableType.hour
          else if (reportUnit == ReportUnit.Sec)
            TableType.second
          else
            TableType.min

          (tab, new DateTime(startNum), new DateTime(endNum))
        } else if (reportUnit.id <= ReportUnit.Day.id) {
          (TableType.hour, new DateTime(startNum), new DateTime(endNum))
        } else {
          (TableType.hour, new DateTime(startNum), new DateTime(endNum))
        }


      val outputType = OutputType.withName(outputTypeStr)
      val chart = trendHelper(monitors, monitorTypes, tabType, reportUnit, start.withSecondOfMinute(0).withMillisOfSecond(0),
        end.withSecondOfMinute(0).withMillisOfSecond(0), trendShowActual)(statusFilter)

      if (outputType == OutputType.excel) {
        import java.nio.file.Files
        val excelFile = excelUtility.exportChartData(chart, monitorTypes, reportUnit == ReportUnit.Sec)
        val downloadFileName =
          if (chart.downloadFileName.isDefined)
            chart.downloadFileName.get
          else
            chart.title("text")

        Ok.sendFile(excelFile, fileName = _ =>
          play.utils.UriEncoding.encodePathSegment(downloadFileName + ".xlsx", "UTF-8"),
          onClose = () => {
            Files.deleteIfExists(excelFile.toPath())
          })
      } else {
        Results.Ok(Json.toJson(chart))
      }
  }

  def trendHelper(monitors: Seq[String], monitorTypes: Seq[String], tabType: TableType.Value,
                  reportUnit: ReportUnit.Value, start: DateTime, end: DateTime, showActual: Boolean = false)(statusFilter: MonitorStatusFilter.Value) = {
    val (adjustedStart, adjustEnd, period: Period) =
      reportUnit match {
        case ReportUnit.Min =>
          (start, end, 1.minute.toPeriod)
        case ReportUnit.TenMin =>
          (start.withMinuteOfHour(0),
            end.withMinuteOfHour(0), 10.minute.toPeriod)
        case ReportUnit.Hour =>
          (start.withMinuteOfHour(0), end.withMinuteOfHour(0), 1.hour.toPeriod)
        case ReportUnit.Day =>
          (start.withHourOfDay(0).withMinuteOfHour(0), end.withHourOfDay(0).withMinuteOfHour(0), 1.day.toPeriod)
        case ReportUnit.Month =>
          (start.withHourOfDay(0).withMinuteOfHour(0), end.withHourOfDay(0).withMinuteOfHour(0), 1.month.toPeriod)
        case ReportUnit.Quarter =>
          (start.withHourOfDay(0).withMinuteOfHour(0), end.withHourOfDay(0).withMinuteOfHour(0), 3.month.toPeriod)
        case ReportUnit.Year =>
          (start.withHourOfDay(0).withMinuteOfHour(0), end.withHourOfDay(0).withMinuteOfHour(0), 1.year.toPeriod)
      }

    val timeList = getPeriods(adjustedStart, adjustEnd, period)
    val timeSeq = timeList

    def getSeries: Seq[seqData] = {
      val monitorReportPairs =
        for {
          monitor <- monitors
        } yield {
          val pair =
            for {
              mt <- monitorTypes
              reportMap = getPeriodReportMap(monitor, mt, tabType, period, statusFilter)(start, end)
            } yield mt -> reportMap
          monitor -> pair.toMap
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
                Seq(Some(time.getMillis.toDouble), Some(valueMap(time)))
              else
                Seq(Some(time.getMillis.toDouble), None)
            }
          } else {
            for (time <- valueMap.keys.toList.sorted) yield {
              Seq(Some(time.getMillis.toDouble), Some(valueMap(time)))
            }
          }

        if (monitorTypes.length > 1) {
          seqData(s"${monitorOp.map(m).desc}_${monitorTypeOp.map(mt).desp}", timeData)
        } else {
          seqData(s"${monitorOp.map(m).desc}_${monitorTypeOp.map(mt).desp}", timeData)
        }
      }
    }

    val downloadFileName = {
      val startName = start.toString("YYMMdd")
      val mtNames = monitorTypes.map {
        monitorTypeOp.map(_).desp
      }
      startName + mtNames.mkString
    }

    val title =
      reportUnit match {
        case ReportUnit.Min =>
          s"趨勢圖 (${start.toString("YYYY年MM月dd日 HH:mm")}~${end.toString("YYYY年MM月dd日 HH:mm")})"
        case ReportUnit.TenMin =>
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
          getSeries,
          Some(downloadFileName))
      } else {
        val yAxis =
          Seq(YAxis(None, AxisTitle(Some(None)), None))

        HighchartData(
          Map("type" -> "line"),
          Map("text" -> title),
          xAxis,
          yAxis,
          getSeries,
          Some(downloadFileName))
      }

    chart
  }

  def getPeriodReportMap(monitor: String, mt: String, tabType: TableType.Value, period: Period,
                         statusFilter: MonitorStatusFilter.Value = MonitorStatusFilter.ValidData)(start: DateTime, end: DateTime) = {
    val recordList = recordOp.getRecordMap(TableType.mapCollection(tabType))(monitor, List(mt), start, end)(mt)

    def periodSlice(period_start: DateTime, period_end: DateTime) = {
      recordList.dropWhile {
        _.time < period_start
      }.takeWhile {
        _.time < period_end
      }
    }

    val pairs =
      if (period.getHours == 1) {
        recordList.filter { r => MonitorStatusFilter.isMatched(statusFilter, r.status) }.map { r => r.time -> r.value }
      } else {
        for {
          period_start <- getPeriods(start, end, period)
          records = periodSlice(period_start, period_start + period) if records.length > 0
        } yield {
          if (mt == MonitorType.WIN_DIRECTION) {
            val windDir = records
            val windSpeed = recordOp.getRecordMap(TableType.mapCollection(tabType))(monitor, List(MonitorType.WIN_SPEED), period_start, period_start + period)(mt)
            period_start -> windAvg(windSpeed, windDir)
          } else {
            val values = records.map { r => r.value }
            period_start -> values.sum / values.length
          }
        }
      }

    Map(pairs: _*)
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

  def historyData(monitorStr: String, monitorTypeStr: String, tabTypeStr: String,
                  startNum: Long, endNum: Long) = Security.Authenticated.async {
    implicit request =>
      val groupID = request.user.group
      val groupMtMap = waitReadyResult(monitorTypeOp.getGroupMapAsync(groupID))

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
        recordList map {
          r =>
            val stripedTime = new DateTime(r._id.time).withSecondOfMinute(0).withMillisOfSecond(0)
            val mtMonitorMap = timeMtMonitorMap.getOrElseUpdate(stripedTime, Map.empty[String, Map[String, CellData]])
            for (mt <- monitorTypes.toSeq) {
              val monitorMap = mtMonitorMap.getOrElseUpdate(mt, Map.empty[String, CellData])
              val cellData = if (r.mtMap.contains(mt)) {
                val mtRecord = r.mtMap(mt)
                CellData(monitorTypeOp.format(mt, Some(mtRecord.value)),
                  monitorTypeOp.getCssClassStr(mtRecord, groupMtMap), Some(mtRecord.status))
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

  def queryData(group: Int, monitorTypeStr:String, tabTypeStr: String, startNum: Long, endNum: Long): Action[AnyContent] = Action.async {
    val monitors =
      if (group == 1) {
        Seq("355001090059923", "355001090037531", "355001090024034", "355001090010884", "355001090037515",
          "352818662095834", "352818662094654")
      }else{
        Seq.empty[String]
      }

    val monitorTypes = monitorTypeStr.split(':')
    val tabType = TableType.withName(tabTypeStr)
    val (start, end) =
      if (tabType == TableType.hour) {
        val original_start = new DateTime(startNum)
        val original_end = new DateTime(endNum)
        (original_start.withMinuteOfHour(0), original_end.withMinute(0) + 1.hour)
      } else {
        (new DateTime(startNum), new DateTime(endNum))
      }

    Logger.info(s"start=$start end=$end")
    val resultFuture: Future[Seq[RecordList]] = recordOp.getRecordListFuture(TableType.mapCollection(tabType))(start, end, monitors)
    implicit val recordListIDwrite: OWrites[RecordListID] = Json.writes[RecordListID]
    implicit val mtDataWrite: OWrites[MtRecord] = Json.writes[MtRecord]
    implicit val recordListWrite: OWrites[RecordList] = Json.writes[RecordList]
    for(result<-resultFuture) yield {
      Logger.info(s"total ${result.size}")
      result.foreach(rs=>rs.mtDataList=rs.mtDataList.filter(mtData=>monitorTypes.contains(mtData.mtName)))
      Ok(Json.toJson(result))
    }
  }

  def latestData(monitorStr: String, monitorTypeStr: String, tabTypeStr: String) = Security.Authenticated.async {
    implicit request =>
      val groupID = request.user.group
      val groupMtMap = waitReadyResult(monitorTypeOp.getGroupMapAsync(groupID))

      val monitors = monitorStr.split(":")
      val monitorTypes = monitorTypeStr.split(':')
      val tabType = TableType.withName(tabTypeStr)

      val futures = for (m <- monitors.toSeq) yield
        recordOp.getLatestRecordFuture(TableType.mapCollection(tabType))(m)

      val allFutures = Future.sequence(futures)

      val emtpyCell = CellData("-", Seq.empty[String])
      for (allRecordlist <- allFutures) yield {
        val recordList = allRecordlist.fold(Seq.empty[RecordList])((a, b) => {
          a ++ b
        })
        import scala.collection.mutable.Map
        val timeMtMonitorMap = Map.empty[DateTime, Map[String, Map[String, CellData]]]
        recordList map {
          r =>
            val stripedTime = new DateTime(r._id.time).withSecondOfMinute(0).withMillisOfSecond(0)
            val mtMonitorMap = timeMtMonitorMap.getOrElseUpdate(stripedTime, Map.empty[String, Map[String, CellData]])
            for (mt <- monitorTypes.toSeq) {
              val monitorMap = mtMonitorMap.getOrElseUpdate(mt, Map.empty[String, CellData])
              val cellData = if (r.mtMap.contains(mt)) {
                val mtRecord = r.mtMap(mt)
                CellData(monitorTypeOp.format(mt, Some(mtRecord.value)),
                  monitorTypeOp.getCssClassStr(mtRecord, groupMtMap), Some(mtRecord.status))
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

  def realtimeStatus() = Security.Authenticated.async {
    implicit request =>
      val userInfo = request.user
      val groupID = userInfo.group
      val group = groupOp.map(groupID)
      import recordOp.recordListWrite
      val monitors = group.monitors
      val tabType = TableType.min
      val oneHourAgo = DateTime.now.minusHours(1).toDate
      val futures = for (m <- monitors) yield
        recordOp.getLatestRecordWithOldestLimitFuture(TableType.mapCollection(tabType))(m, oneHourAgo)

      val allFutures = Future.sequence(futures)

      for (allRecords <- allFutures) yield {
        val recordList = allRecords.fold(Seq.empty[RecordList])((a, b) => {
          a ++ b
        })

        Ok(Json.toJson(recordList))
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
      import recordOp.recordListWrite
      for (recordList <- f) yield
        Ok(Json.toJson(recordList))
  }

  def calibrationReport(startNum: Long, endNum: Long) = Security.Authenticated {
    import calibrationOp.jsonWrites
    val (start, end) = (new DateTime(startNum), new DateTime(endNum) + 1.day)
    val report: Seq[Calibration] = calibrationOp.calibrationReport(start, end)
    val jsonReport = report map {
      _.toJSON
    }
    Ok(Json.toJson(jsonReport))
  }

  def alarmReport(level: Int, startNum: Long, endNum: Long) = Security.Authenticated.async {
    implicit request =>
      val userInfo = Security.getUserinfo(request).get
      val group = groupOp.getGroupByID(userInfo.group).get

      implicit val write = Json.writes[Alarm2JSON]
      val (start, end) =
        (new DateTime(startNum),
          new DateTime(endNum))
      for (report <- alarmOp.getAlarms(level, group._id, start, end + 1.day)) yield {
        val jsonReport = report map {
          _.toJson
        }
        Ok(Json.toJson(jsonReport))
      }
  }

  def getErrorReports(startN: Long, endN: Long) = Security.Authenticated.async {
    implicit request =>
      val end = new DateTime(endN).withMillisOfDay(0)
      val start = new DateTime(startN).withMillisOfDay(0)
      for (reports <- errorReportOp.get(start.toDate, end.toDate)) yield {
        import ErrorReport._
        Ok(Json.toJson(reports))
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

    val columnNames = keyList map statusTypeMap
    val rows = for (report <- reportMap) yield {
      val cellData = for (key <- keyList) yield
        if (report._2.contains(key))
          CellData(instrumentStatusOp.formatValue(report._2(key)), Seq.empty[String])
        else
          CellData("-", Seq.empty[String])
      RowData(report._1.getMillis, cellData)
    }

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
      import recordOp.recordListWrite
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
      import recordOp.recordListWrite
      for (recordList <- recordListF) yield {
        Ok(Json.toJson(recordList))
      }
  }

  def calibrationRecordList(start: Long, end: Long) = Action.async {
    implicit request =>
      val startTime = new DateTime(start)
      val endTime = new DateTime(end)
      val recordListF = calibrationOp.calibrationReportFuture(startTime, endTime)
      implicit val w = Json.writes[Calibration]
      for (recordList <- recordListF) yield {
        Ok(Json.toJson(recordList))
      }
  }

  def alertRecordList(start: Long, end: Long) = Action.async {
    implicit request =>
      val startTime = new DateTime(start)
      val endTime = new DateTime(end)
      for (recordList <- alarmOp.getAlarmsFuture(startTime, endTime)) yield {
        implicit val w = Json.writes[Alarm]
        Ok(Json.toJson(recordList))
      }
  }

  case class InstrumentReport(columnNames: Seq[String], rows: Seq[RowData])

  //
  //  def windRose() = Security.Authenticated {
  //    implicit request =>
  //      Ok(views.html.windRose(false))
  //  }
  //
  //  def monitorTypeRose() = Security.Authenticated {
  //    implicit request =>
  //      Ok(views.html.windRose(true))
  //  }
  //
  //  def windRoseReport(monitorStr: String, monitorTypeStr: String, nWay: Int, startStr: String, endStr: String) = Security.Authenticated {
  //    val monitor = EpaMonitor.withName(monitorStr)
  //    val monitorType = (monitorTypeStr)
  //    val start = DateTime.parse(startStr, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm"))
  //    val end = DateTime.parse(endStr, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm"))
  //    val mtCase = monitorTypeOp.map(monitorType)
  //    assert(nWay == 8 || nWay == 16 || nWay == 32)
  //
  //    try {
  //      val level = List(1f, 2f, 5f, 15f)
  //      val windMap = Record.getMtRose(monitor, monitorType, start, end, level, nWay)
  //      val nRecord = windMap.values.map { _.length }.sum
  //
  //      val dirMap =
  //        Map(
  //          (0 -> "北"), (1 -> "北北東"), (2 -> "東北"), (3 -> "東北東"), (4 -> "東"),
  //          (5 -> "東南東"), (6 -> "東南"), (7 -> "南南東"), (8 -> "南"),
  //          (9 -> "南南西"), (10 -> "西南"), (11 -> "西西南"), (12 -> "西"),
  //          (13 -> "西北西"), (14 -> "西北"), (15 -> "北北西"))
  //
  //      val dirStrSeq =
  //        for {
  //          dir <- 0 to nWay - 1
  //          dirKey = if (nWay == 8)
  //            dir * 2
  //          else if (nWay == 32) {
  //            if (dir % 2 == 0) {
  //              dir / 2
  //            } else
  //              dir + 16
  //          } else
  //            dir
  //        } yield dirMap.getOrElse(dirKey, "")
  //
  //      var last = 0f
  //      val speedLevel = level.flatMap { l =>
  //        if (l == level.head) {
  //          last = l
  //          List(s"< ${l} ${mtCase.unit}")
  //        } else if (l == level.last) {
  //          val ret = List(s"${last}~${l} ${mtCase.unit}", s"> ${l} ${mtCase.unit}")
  //          last = l
  //          ret
  //        } else {
  //          val ret = List(s"${last}~${l} ${mtCase.unit}")
  //          last = l
  //          ret
  //        }
  //      }
  //
  //      import Highchart._
  //
  //      val series = for {
  //        level <- 0 to level.length
  //      } yield {
  //        val data =
  //          for (dir <- 0 to nWay - 1)
  //            yield Seq(Some(dir.toDouble), Some(windMap(dir)(level).toDouble))
  //
  //        seqData(speedLevel(level), data)
  //      }
  //
  //      val title =
  //        if (monitorType == "")
  //          "風瑰圖"
  //        else {
  //          mtCase.desp + "玫瑰圖"
  //        }
  //
  //      val chart = HighchartData(
  //        scala.collection.immutable.Map("polar" -> "true", "type" -> "column"),
  //        scala.collection.immutable.Map("text" -> title),
  //        XAxis(Some(dirStrSeq)),
  //        Seq(YAxis(None, AxisTitle(Some(Some(""))), None)),
  //        series)
  //
  //      Results.Ok(Json.toJson(chart))
  //    } catch {
  //      case e: AssertionError =>
  //        Logger.error(e.toString())
  //        BadRequest("無資料")
  //    }
  //  }

}