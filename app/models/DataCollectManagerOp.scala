package models

import akka.actor.ActorRef
import com.github.nscala_time.time.Imports.{DateTime, _}
import controllers.Highchart.{AxisLine, AxisLineLabel, AxisTitle, HighchartData, Tooltip, XAxis, YAxis, seqData}
import controllers.ReportUnit
import models.ForwardManager.ForwardHour
import models.ModelHelper.directionAvg
import play.api.Logger

import javax.inject.{Inject, Named, Singleton}
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}

@Singleton
class DataCollectManagerOp @Inject()(@Named("dataCollectManager") manager: ActorRef,
                                     instrumentOp: InstrumentDB,
                                     recordOp: RecordDB,
                                     alarmDb: AlarmDB,
                                     monitorDB: MonitorDB,
                                     monitorTypeDb: MonitorTypeDB,
                                     sysConfigDB: SysConfigDB,
                                     alarmRuleDb: AlarmRuleDb,
                                     cdxUploader: CdxUploader,
                                     newTaipeiOpenData: NewTaipeiOpenData,
                                     tableType: TableType)() {
  val logger: Logger = Logger(this.getClass)

  import DataCollectManager._

  def startCollect(inst: Instrument): Unit = {
    manager ! StartInstrument(inst)
  }

  def startCollect(id: String): Unit = {
    val instList = instrumentOp.getInstrument(id)
    instList.foreach { inst => manager ! StartInstrument(inst) }
  }

  def stopCollect(id: String): Unit = {
    manager ! StopInstrument(id)
  }

  def setInstrumentState(id: String, state: String): Unit = {
    manager ! SetState(id, state)
  }

  def autoCalibration(id: String): Unit = {
    manager ! AutoCalibration(id)
  }

  def zeroCalibration(id: String): Unit = {
    manager ! ManualZeroCalibration(id)
  }

  def spanCalibration(id: String): Unit = {
    manager ! ManualSpanCalibration(id)
  }

  def writeTargetDO(id: String, bit: Int, on: Boolean): Unit = {
    manager ! WriteTargetDO(id, bit, on)
  }

  def executeSeq(seqName: String, on: Boolean): Unit = {
    manager ! ExecuteSeq(seqName, on)
  }

  def getLatestData: Future[mutable.Map[String, Record]] = {
    import akka.pattern.ask
    import akka.util.Timeout

    import scala.concurrent.duration._
    implicit val timeout: Timeout = Timeout(Duration(3, SECONDS))

    val f = manager ? GetLatestData
    f.mapTo[mutable.Map[String, Record]]
  }

  def getLatestSignal: Future[Map[String, Boolean]] = {
    import akka.pattern.ask
    import akka.util.Timeout

    import scala.concurrent.duration._
    implicit val timeout: Timeout = Timeout(Duration(3, SECONDS))

    val f = manager ? GetLatestSignal
    f.mapTo[Map[String, Boolean]]
  }

  def writeSignal(mtId: String, bit: Boolean): Unit = {
    manager ! WriteSignal(mtId, bit)
  }

  def recalculateHourData(monitor: String,
                          current: DateTime,
                          checkAlarm: Boolean = true,
                          forward: Boolean = true,
                          alwaysValid: Boolean = false): Future[Unit] = {
    val mtList = monitorTypeDb.measuredList
    for (recordMap <- recordOp.getMtRecordMapFuture(recordOp.MinCollection)(monitor, mtList, current - 1.hour, current);
         alarmRules <- alarmRuleDb.getRulesAsync) yield {
      val mtMap = mutable.Map.empty[String, mutable.Map[String, ListBuffer[MtRecord]]]

      for ((mt, mtRecordList) <- recordMap; mtRecord <- mtRecordList) {
        val statusMap = mtMap.getOrElseUpdate(mt, mutable.Map.empty[String, ListBuffer[MtRecord]])
        val tagInfo = MonitorStatus.getTagInfo(mtRecord.status)
        val status = tagInfo.statusType match {
          case StatusType.ManualValid =>
            MonitorStatus.NormalStat
          case _ =>
            mtRecord.status
        }

        val lb = statusMap.getOrElseUpdate(status, ListBuffer.empty[MtRecord])

        for (v <- mtRecord.value if !v.isNaN)
          lb.append(mtRecord)
      }

      val mtDataList = calculateHourAvgMap(mtMap, alwaysValid, monitorTypeDb)
      val defaultHourRecordList = RecordList.factory(current.minusHours(1).toDate, mtDataList.toSeq, monitor)
      val hourRecordListsFuture = HourCalculationRule.calculateHourRecord(monitor, current, recordOp)
      for (ruleHourRecordLists <- hourRecordListsFuture) {
        val hourRecordLists = ruleHourRecordLists :+ defaultHourRecordList

        // Check alarm
        if (checkAlarm) {
          val alarms = alarmRuleDb.checkAlarm(tableType.hour, defaultHourRecordList, alarmRules)(monitorDB, monitorTypeDb, alarmDb)
          alarms.foreach(alarmDb.log)
        }

        val f = recordOp.upsertManyRecords(recordOp.HourCollection)(hourRecordLists)
        if (forward) {
          f onComplete {
            case Success(_) =>
              manager ! ForwardHour
              for {cdxConfig <- sysConfigDB.getCdxConfig if monitor == Monitor.activeId
                   cdxMtConfigs <- sysConfigDB.getCdxMonitorTypes} {
                cdxUploader.upload(recordList = defaultHourRecordList, cdxConfig = cdxConfig, mtConfigs = cdxMtConfigs)
                newTaipeiOpenData.upload(defaultHourRecordList, cdxMtConfigs)
              }

            case Failure(exception) =>
              logger.error("failed", exception)
          }
        }
      }
    }
  }

  def resetReaders(): Unit = {
    manager ! ReaderReset
  }

  private def getPeriodReportMap(monitor: String, mtList: Seq[String],
                                 myTabType: TableType#Value,
                                 period: Period,
                                 includeRaw: Boolean = false,
                                 statusFilter: MonitorStatusFilter.Value = MonitorStatusFilter.ValidData)
                                (start: DateTime, end: DateTime): Map[String, Map[DateTime, (Option[Double], Option[String])]] = {
    val mtRecordListMap = recordOp.getRecordMap(tableType.mapCollection(myTabType))(monitor, mtList, start, end, includeRaw)
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
          if ((myTabType == tableType.hour && period.getHours == 1) || (myTabType == tableType.min && period.getMinutes == 1)) {
            recordList.filter { r => MonitorStatusFilter.isMatched(statusFilter, r.status) }.map { r => r.time -> (r.value, Some(r.status)) }
          } else {
            for {
              period_start <- ModelHelper.getPeriods(start, end, period)
              records = periodSlice(period_start, period_start + period) if records.nonEmpty
            } yield {
              if (mt == MonitorType.WIN_DIRECTION || mt == MonitorType.getRawType(MonitorType.WIN_DIRECTION)) {
                val windDir = records
                val windSpeed = recordOp.getRecordMap(tableType.mapCollection(myTabType))(monitor, List(MonitorType.WIN_SPEED), period_start, period_start + period)(MonitorType.WIN_SPEED)
                period_start -> (directionAvg(windSpeed.flatMap(_.value), windDir.flatMap(_.value)), None)
              } else {
                val matchedRecords = records.filter { r => MonitorStatusFilter.isMatched(statusFilter, r.status) }
                val values = matchedRecords.flatMap { r => r.value }
                val status =
                  if (matchedRecords.nonEmpty)
                    Some(matchedRecords.head.status)
                  else
                    None

                if (values.nonEmpty)
                  period_start -> (Some(values.sum / values.length), status)
                else
                  period_start -> (None, status)
              }
            }
          }
        mt -> Map(pairs: _*)
      }
    mtRecordPairs.toMap
  }

  def trendHelper(monitors: Seq[String], monitorTypes: Seq[String], includeRaw: Boolean, tabType: TableType#Value,
                  reportUnit: ReportUnit.Value, start: DateTime, end: DateTime, showActual: Boolean)(statusFilter: MonitorStatusFilter.Value): HighchartData = {
    val period: Period =
      reportUnit match {
        case ReportUnit.Sec =>
          1.second
        case ReportUnit.Min =>
          1.minute
        case ReportUnit.FiveMin =>
          5.minute
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

    val timeSeq = ModelHelper.getPeriods(start, end, period)

    val downloadFileName = {
      val startName = start.toString("YYMMdd")
      val mtNames = monitorTypes.map {
        monitorTypeDb.map(_).desp
      }
      startName + mtNames.mkString
    }

    val title =
      reportUnit match {
        case ReportUnit.Sec =>
          s"趨勢圖 (${start.toString("YYYY年MM月dd日 HH:mm")}~${end.toString("YYYY年MM月dd日 HH:mm")})"
        case ReportUnit.Min =>
          s"趨勢圖 (${start.toString("YYYY年MM月dd日 HH:mm")}~${end.toString("YYYY年MM月dd日 HH:mm")})"
        case ReportUnit.FiveMin =>
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
      val mtCase = monitorTypeDb.map(mt)
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
      (monitorTypeDb.map(mt).unit, getAxisLines(mt))
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
        if (monitorTypeDb.map.contains(mt))
          seqData(name = s"${monitorDB.map(m).desc}_${monitorTypeDb.map(mt).desp}",
            data = timeValues, yAxis = yAxisUnitMap(monitorTypeDb.map(mt).unit),
            tooltip = Tooltip(monitorTypeDb.map(mt).prec), statusList = timeStatus)
        else {
          val realMt = MonitorType.getRealType(mt)
          seqData(name = s"${monitorDB.map(m).desc}_${monitorTypeDb.map(realMt).desp}原始值",
            data = timeValues, yAxis = yAxisUnitMap(monitorTypeDb.map(realMt).unit),
            tooltip = Tooltip(monitorTypeDb.map(realMt).prec), statusList = timeStatus)
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
        val mtCase = monitorTypeDb.map(monitorTypes.head)

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

}


