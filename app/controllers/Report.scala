package controllers

import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import models._
import play.api.libs.json.Json
import play.api.mvc._

import java.nio.file.Files
import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

object PeriodReport extends Enumeration {
  val DailyReport: Value = Value("daily")
  val MonthlyReport: Value = Value("monthly")
  val YearlyReport: Value = Value("yearly")

  def map: Map[Value, String] =
    Map(DailyReport -> "日報",
      MonthlyReport -> "月報",
      YearlyReport -> "年報")

}

case class StatRow(name: String, cellData: Seq[CellData])

case class HourEntry(time: Long, cells: CellData)

case class DisplayReport(columnNames: Seq[String], rows: Seq[RowData], statRows: Seq[StatRow])

@Singleton
class Report @Inject()(monitorTypeOp: MonitorTypeDB,
                       recordOp: RecordDB,
                       query: Query,
                       groupDB: GroupDB,
                       excelUtility: ExcelUtility,
                       security: Security,
                       cc: ControllerComponents) extends AbstractController(cc) {
  implicit val w3 = Json.writes[CellData]
  implicit val w2 = Json.writes[StatRow]
  implicit val w1 = Json.writes[RowData]
  implicit val w = Json.writes[DisplayReport]

  def getMonitorReport(reportTypeStr: String, startNum: Long, outputTypeStr: String): Action[AnyContent] = security.Authenticated {
    implicit request =>
      val userInfo = security.getUserinfo(request).get
      val group = groupDB.getGroupByID(userInfo.group).get

      val reportType = PeriodReport.withName(reportTypeStr)
      val outputType = OutputType.withName(outputTypeStr)
      val mtList = if(userInfo.isAdmin)
        monitorTypeOp.realtimeMtvList
      else
        monitorTypeOp.realtimeMtvList filter group.monitorTypes.contains

      reportType match {
        case PeriodReport.DailyReport =>
          val startDate = new DateTime(startNum).withMillisOfDay(0)
          val periodMap = recordOp.getRecordMap(recordOp.HourCollection)(Monitor.activeId, mtList, startDate, startDate + 1.day)
          val mtTimeMap: Map[String, Map[DateTime, Record]] = periodMap.map { pair =>
            val k = pair._1
            val v = pair._2
            k -> Map(v.map { r => r.time -> r }: _*)
          }
          val statMap: Map[String, Map[DateTime, Stat]] =
            query.getPeriodStatReportMap(periodMap, 1.day)(startDate, startDate + 1.day)

          val avgRow = {
            val avgData =
              for (mt <- mtList) yield {
                CellData(monitorTypeOp.format(mt, statMap(mt)(startDate).avg), Seq.empty[String])
              }
            StatRow("平均", avgData)
          }
          val maxRow = {
            val maxData =
              for (mt <- mtList) yield {
                CellData(monitorTypeOp.format(mt, statMap(mt)(startDate).max), Seq.empty[String])
              }
            StatRow("最大", maxData)
          }
          val minRow = {
            val minData =
              for (mt <- mtList) yield {
                CellData(monitorTypeOp.format(mt, statMap(mt)(startDate).min), Seq.empty[String])
              }
            StatRow("最小", minData)
          }
          val effectiveRow = {
            val effectiveData =
              for (mt <- mtList) yield {
                CellData(monitorTypeOp.format(mt, statMap(mt)(startDate).effectPercent), Seq.empty[String])
              }
            StatRow("有效率(%)", effectiveData)
          }
          val statRows = Seq(avgRow, maxRow, minRow, effectiveRow)

          val hourRows =
            for (i <- 0 to 23) yield {
              val recordTime = startDate + i.hour
              val mtData =
                for (mt <- mtList) yield {
                  CellData(monitorTypeOp.formatRecord(mt, mtTimeMap(mt).get(recordTime)),
                    monitorTypeOp.getCssClassStr(mt, mtTimeMap(mt).get(recordTime)))
                }
              RowData(recordTime.getMillis, mtData)
            }
          val columnNames = mtList map {
            monitorTypeOp.map(_).desp
          }
          val dailyReport = DisplayReport(columnNames, hourRows, statRows)

          if (outputType == OutputType.html)
            Ok(Json.toJson(dailyReport))
          else {
            val (title, excelFile) =
              ("日報" + startDate.toString("YYYYMMdd"), excelUtility.exportDailyReport(startDate, dailyReport))

            Ok.sendFile(excelFile, fileName = _ =>
              Some(s"$title.xlsx"),
              onClose = () => {
                Files.deleteIfExists(excelFile.toPath)
              })
          }

        case PeriodReport.MonthlyReport =>
          val start = new DateTime(startNum).withMillisOfDay(0).withDayOfMonth(1)
          val periodMap = recordOp.getRecordMap(recordOp.HourCollection)(Monitor.activeId, monitorTypeOp.activeMtvList, start, start + 1.month)
          val statMap = query.getPeriodStatReportMap(periodMap, 1.day)(start, start + 1.month)
          val overallStatMap = getOverallStatMap(statMap, 20)
          val avgRow = {
            val avgData =
              for (mt <- mtList) yield {
                CellData(monitorTypeOp.format(mt, overallStatMap(mt).avg), Seq.empty[String])
              }
            StatRow("平均", avgData)
          }
          val maxRow = {
            val maxData =
              for (mt <- mtList) yield {
                CellData(monitorTypeOp.format(mt, overallStatMap(mt).max), Seq.empty[String])
              }
            StatRow("最大", maxData)
          }
          val minRow = {
            val minData =
              for (mt <- mtList) yield {
                CellData(monitorTypeOp.format(mt, overallStatMap(mt).min), Seq.empty[String])
              }
            StatRow("最小", minData)
          }
          val effectiveRow = {
            val effectiveData =
              for (mt <- mtList) yield {
                CellData(monitorTypeOp.format(mt, overallStatMap(mt).effectPercent), Seq.empty[String])
              }
            StatRow("有效率(%)", effectiveData)
          }
          val statRows = Seq(avgRow, maxRow, minRow, effectiveRow)

          val dayRows =
            for (recordTime <- getPeriods(start, start + 1.month, 1.day)) yield {
              val mtData =
                for (mt <- mtList) yield {
                  val status = if (statMap(mt)(recordTime).isEffective)
                    MonitorStatus.NormalStat
                  else
                    MonitorStatus.InvalidDataStat

                  CellData(monitorTypeOp.format(mt, statMap(mt)(recordTime).avg),
                    MonitorStatus.getCssClassStr(status), Some(status))
                }
              RowData(recordTime.getMillis, mtData)
            }
          val columnNames = mtList map {
            monitorTypeOp.map(_).desp
          }
          val monthlyReport = DisplayReport(columnNames, dayRows, statRows)
          if (outputType == OutputType.html)
            Ok(Json.toJson(monthlyReport))
          else {
            val (title, excelFile) =
              ("月報" + start.toString("YYYYMM"),
                excelUtility.exportDisplayReport(s"監測月報 ${start.toString("YYYY年MM月")}", monthlyReport))

            Ok.sendFile(excelFile, fileName = _ =>
              Some(s"$title.xlsx"),
              onClose = () => {
                Files.deleteIfExists(excelFile.toPath)
              })
          }

        case PeriodReport.YearlyReport =>
          val start = new DateTime(startNum)
          val startDate = start.withMillisOfDay(0).withDayOfMonth(1).withMonthOfYear(1)
          val periodMap = recordOp.getRecordMap(recordOp.HourCollection)(Monitor.activeId, monitorTypeOp.activeMtvList, startDate, startDate + 1.year)
          val statMap = query.getPeriodStatReportMap(periodMap, 1.month)(start, start + 1.year)
          val overallStatMap = getOverallStatMap(statMap, 12)
          Ok("")

        //case PeriodReport.MonthlyReport =>
        //val nDays = monthlyReport.typeArray(0).dataList.length
        //("月報", "")
      }
  }

  private def getOverallStatMap(statMap: Map[String, Map[DateTime, Stat]], minimalValidCount: Int): Map[String, Stat] = {
    statMap.map { pair =>
      val mt = pair._1
      val dateMap = pair._2
      val values = dateMap.values.filter(_.valid).toList
      val total = dateMap.values.size
      val count = values.size
      val overCount = values.map {
        _.overCount
      }.sum
      val max = if (values.nonEmpty)
        values.map {
          _.avg
        }.max
      else
        None
      val min = if (values.nonEmpty)
        values.map {
          _.avg
        }.min
      else
        None
      val avg =
        if (mt != MonitorType.WIN_DIRECTION) {
          if (total == 0 || count == 0)
            None
          else {
            val sum = values.flatMap(_.avg).sum
            Some(sum / count)
          }
        } else {
          val winSpeedMap = statMap(MonitorType.WIN_SPEED)
          val dates = dateMap.keys.toList
          val windDir = dates.map {
            dateMap
          }
          val windSpeed = dates.map {
            winSpeedMap
          }

          def windAvg1(): Option[Double] = {
            val windRecord = windSpeed.zip(windDir).filter(w => w._1.avg.isDefined && w._2.avg.isDefined)
            if (windRecord.isEmpty)
              None
            else {
              val wind_sin = windRecord.map {
                v => v._1.avg.get * Math.sin(Math.toRadians(v._2.avg.get))
              }.sum

              val wind_cos = windRecord.map(v => v._1.avg.get * Math.cos(Math.toRadians(v._2.avg.get))).sum
              Some(directionAvg(wind_sin, wind_cos))
            }
          }

          windAvg1()
        }

      mt -> Stat(
        avg = avg,
        min = min,
        max = max,
        total = total,
        count = count,
        overCount = overCount,
        valid = count >= minimalValidCount)
    }
  }

  def monthlyHourReport(monitorTypeStr: String, startDate: Long, outputTypeStr: String): Action[AnyContent] = security.Authenticated {
    val mt = monitorTypeStr
    val start = new DateTime(startDate).withMillisOfDay(0).withDayOfMonth(1)
    val outputType = OutputType.withName(outputTypeStr)
    val recordList = recordOp.getRecordMap(recordOp.HourCollection)(Monitor.activeId, List(mt), start, start + 1.month)(mt)
    val timePair = recordList.map { r => r.time -> r }
    val timeMap = Map(timePair: _*)

    def getHourPeriodStat(records: Seq[Record], hourList: List[DateTime]) = {
      val values = records.filter(rec => MonitorStatusFilter.isMatched(MonitorStatusFilter.ValidData, rec.status))
        .flatMap { r => r.value }
      if (values.isEmpty)
        Stat(None, None, None, 0, 0, 0, valid = false)
      else {
        val min = values.min
        val max = values.max
        val sum = values.sum
        val count = values.length
        val total = new Duration(start, start + 1.month).getStandardDays.toInt
        val overCount = if (monitorTypeOp.map(mt).std_law.isDefined) {
          values.count {
            _ > monitorTypeOp.map(mt).std_law.get
          }
        } else
          0

        val avg = if (mt == MonitorType.WIN_DIRECTION) {
          val windDir = records
          val windSpeed = hourList.map(timeMap)
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
          valid = true)
      }
    }

    val hourValues =
      for {
        h <- 0 to 23
        hourList = query.getPeriods(start + h.hour, start + 1.month, 1.day)
      } yield {
        h -> getHourPeriodStat(hourList.flatMap {
          timeMap.get
        }, hourList)
      }
    val hourStatMap = Map(hourValues: _*)
    val dayStatMap = query.getPeriodStatReportMap(Map(mt -> recordList), 1.day)(start, start + 1.month)
    val overallPeriod: Period = new Period(start, start + 1.month)
    val overallStat = query.getPeriodStatReportMap(Map(mt -> recordList), overallPeriod)(start, start + 1.month)(mt)(start)
    var columns = Seq.empty[String]
    for (i <- 0 to 23) {
      columns = columns.:+(s"$i:00")
    }
    columns = columns ++ Seq("平均", "最大", "最小", "有效筆數")

    val avgRow = {
      var avgData =
        for (h <- 0 to 23) yield {
          CellData(monitorTypeOp.format(mt, hourStatMap(h).avg), Seq.empty[String])
        }

      avgData = avgData.:+(CellData(monitorTypeOp.format(mt, overallStat.avg), Seq.empty[String]))
      avgData = avgData.:+(CellData("", Seq.empty[String]))
      avgData = avgData.:+(CellData("", Seq.empty[String]))
      avgData = avgData.:+(CellData("", Seq.empty[String]))
      StatRow("平均", avgData)
    }
    val maxRow = {
      var maxData =
        for (h <- 0 to 23) yield {
          CellData(monitorTypeOp.format(mt, hourStatMap(h).max), Seq.empty[String])
        }
      maxData = maxData.:+(CellData("", Seq.empty[String]))
      maxData = maxData.:+(CellData(monitorTypeOp.format(mt, overallStat.max), Seq.empty[String]))
      maxData = maxData.:+(CellData("", Seq.empty[String]))
      maxData = maxData.:+(CellData("", Seq.empty[String]))
      StatRow("最大", maxData)
    }
    val minRow = {
      var minData =
        for (h <- 0 to 23) yield {
          CellData(monitorTypeOp.format(mt, hourStatMap(h).min), Seq.empty[String])
        }
      minData = minData.:+(CellData("", Seq.empty[String]))
      minData = minData.:+(CellData("", Seq.empty[String]))
      minData = minData.:+(CellData(monitorTypeOp.format(mt, overallStat.min), Seq.empty[String]))
      minData = minData.:+(CellData("", Seq.empty[String]))
      StatRow("最小", minData)
    }

    val statRows = Seq(avgRow, maxRow, minRow)

    var rows = Seq.empty[RowData]
    for (day <- getPeriods(start, start + 1.month, 1.day)) yield {
      val date = day.getMillis
      var cellData = Seq.empty[CellData]
      for (h <- 0 to 23) {
        val recordOpt = timeMap.get(day + h.hour)
        cellData = cellData :+ CellData(monitorTypeOp.formatRecord(mt, recordOpt),
          monitorTypeOp.getCssClassStr(mt, recordOpt))
      }
      cellData = cellData.:+(CellData(monitorTypeOp.format(mt, dayStatMap(mt)(day).avg), Seq.empty[String]))
      cellData = cellData.:+(CellData(monitorTypeOp.format(mt, dayStatMap(mt)(day).max), Seq.empty[String]))
      cellData = cellData.:+(CellData(monitorTypeOp.format(mt, dayStatMap(mt)(day).min), Seq.empty[String]))
      cellData = cellData.:+(CellData(dayStatMap(mt)(day).count.toString, Seq.empty[String]))
      rows = rows.:+(RowData(date, cellData))
    }

    val report = DisplayReport(columns, rows, statRows)
    if (outputType == OutputType.html || outputType == OutputType.pdf) {
      Ok(Json.toJson(report))
    } else {
      val (title, excelFile) =
        ("月份時報表" + start.toString("YYYYMM"),
          excelUtility.exportDisplayReport(s"月份時報表 ${start.toString("YYYY年MM月")}", report))

      Ok.sendFile(excelFile, fileName = _ =>
        Some(s"$title.xlsx"),
        onClose = () => {
          Files.deleteIfExists(excelFile.toPath)
        })
    }
  }
}