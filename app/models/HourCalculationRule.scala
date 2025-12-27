package models

import com.github.nscala_time.time.Imports.DateTime
import models.ModelHelper.errorHandler
import play.api.Logger
import play.api.libs.json.{Json, OWrites, Reads}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class HourCalculationRule(name: String, monitorTypes: Seq[String], delay: Int, hourRule: Int)

object HourCalculationRule {
  val logger = Logger(this.getClass)

  private val LastMinValue = 1
  private val SeparateMinAvg = 2

  val defaultRules: List[HourCalculationRule] = List(
    HourCalculationRule("BTEX (00:40+00:55+01:10+01:25)/4", Seq.empty[String], 2, SeparateMinAvg),
    HourCalculationRule("611/811 1:59分鐘值", Seq.empty[String], 2, LastMinValue)
  )

  private var rules: List[HourCalculationRule] = defaultRules

  implicit val reads: Reads[HourCalculationRule] = Json.reads[HourCalculationRule]
  implicit val writes: OWrites[HourCalculationRule] = Json.writes[HourCalculationRule]

  def init(sysConfigDB: SysConfigDB): Unit = {
    for (hourRules <- sysConfigDB.getHourCalculationRules) {
      rules = hourRules.toList
      logger.info(s"HourCalculationRules = $rules")
    }
  }

  def updateRules(newRules:List[HourCalculationRule]): Unit =
    rules = newRules

  private def lastMinValueRuleHandler(rule: HourCalculationRule, recordMap: Map[String, Seq[Record]]): Seq[MtRecord] = {
    rule.monitorTypes map {
      mt =>
        val records = recordMap.getOrElse(mt, Seq.empty[Record])
        if (records.nonEmpty) {
          val last = records.last
          MtRecord(mt, last.value, last.status)
        } else {
          MtRecord(mt, None, MonitorStatus.DataLost)
        }
    }
  }

  private def separateMinAvgRuleHandler(rule: HourCalculationRule, recordMap: Map[String, Seq[Record]]): Seq[MtRecord] = {
    rule.monitorTypes map {
      mt =>
        val records = recordMap.getOrElse(mt, Seq.empty[Record])
        if (records.nonEmpty) {
          val filteredRecords = records.filter(r => true)
          val values = filteredRecords flatMap (_.value)
          if (values.nonEmpty)
            MtRecord(mt, Some(values.sum / values.length), MonitorStatus.NormalStat)
          else
            MtRecord(mt, None, MonitorStatus.DataLost)
        } else {
          MtRecord(mt, None, MonitorStatus.DataLost)
        }
    }
  }

  def calculateHourRecord(monitor: String, current: DateTime, recordDB: RecordDB): Future[List[RecordList]] = {

    val delayRulesMap: Map[Int, List[HourCalculationRule]] = rules.groupBy(_.delay)

    val retFutureList =
      for (delay <- delayRulesMap.keys.toList.sorted) yield {
        val hourRules = delayRulesMap(delay)
        val mtList = {
          (hourRules map { r => Set(r.monitorTypes: _*) }).foldLeft(Set.empty[String])((a, b) => a ++ b).toList
        }
        val recordMapF = recordDB.getRecordMapFuture(recordDB.MinCollection)(monitor,
          mtList, current.minusHours(delay), current)

        for (recordMap <- recordMapF) yield {
          val mtRecords =
            hourRules.flatMap(rule => {
              try {
                rule.hourRule match {
                  case LastMinValue =>
                    lastMinValueRuleHandler(rule, recordMap)
                  case SeparateMinAvg =>
                    separateMinAvgRuleHandler(rule, recordMap)
                  case x =>
                    logger.warn(s"Unknown rule type $x")
                    Seq.empty[MtRecord]
                }
              } catch {
                case ex: Throwable =>
                  logger.error("failed to calculated", ex)
                  Seq.empty[MtRecord]
              }
            })
          RecordList.factory(current.minusHours(delay).toDate, mtRecords, monitor)
        }
      }

    val retFuture = Future.sequence(retFutureList)
    retFuture.failed.foreach(errorHandler)

    for (ret <- retFuture) yield
      ret
  }
}
