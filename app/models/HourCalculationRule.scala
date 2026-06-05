package models

import com.github.nscala_time.time.Imports._
import models.ModelHelper.errorHandler
import play.api.Logger
import play.api.libs.json.{Json, OWrites, Reads}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class HourCalculationRule(name: String, monitorTypes: Seq[String], delay: Int, hourRule: Int)

object HourCalculationRule {
  val logger = Logger(this.getClass)

  private val LastMinValue = 1
  private val DesignatedMinAvg = 2
  private val LastHourMinAvg = 3

  val defaultRules: List[HourCalculationRule] = List(
    HourCalculationRule("BTEX (00:40+00:55+01:10+01:25)/4", Seq.empty[String], 2, DesignatedMinAvg),
    HourCalculationRule("611/811 1:59分鐘值", Seq.empty[String], 2, LastMinValue),
    HourCalculationRule("Marga IC", Seq.empty, 3, LastHourMinAvg)
  )

  private var rules: List[HourCalculationRule] = defaultRules

  implicit val reads: Reads[HourCalculationRule] = Json.reads[HourCalculationRule]
  implicit val writes: OWrites[HourCalculationRule] = Json.writes[HourCalculationRule]

  def init(sysConfigDB: SysConfigDB): Unit = {
    var defaultRuleMap: Map[String, HourCalculationRule] = defaultRules.map(rule=>rule.name -> rule).toMap
    for (hourRules <- sysConfigDB.getHourCalculationRules) {
      rules = List.empty
      hourRules.foreach(rule=> {
        defaultRuleMap = defaultRuleMap - rule.name
        rules = rules :+ rule
      })
      rules = rules ++ defaultRuleMap.values.toSeq
      logger.info(s"HourCalculationRules = $rules")
    }
  }

  def getRules: List[HourCalculationRule] = rules
  def updateRules(newRules: List[HourCalculationRule]): Unit =
    rules = newRules

  private def getLastMinValue(rule: HourCalculationRule, recordMap: Map[String, Seq[Record]]): Seq[MtRecord] = {
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

  private def getLastHourMinAvg(rule: HourCalculationRule, current:DateTime, recordMap: Map[String, Seq[Record]]): Seq[MtRecord] = {
    rule.monitorTypes map {
      mt =>
        val records = recordMap.getOrElse(mt, Seq.empty[Record]).filter(record=>{
          current.minusHours(1) <= record.time && record.time < current
        })
        val values = records.flatMap(_.value)
        if (values.nonEmpty) {
          val maxStatus = records.groupBy(_.status).maxBy(_._2.length)._1
          MtRecord(mt, Some(values.sum/values.length), maxStatus)
        } else {
          MtRecord(mt, None, MonitorStatus.DataLost)
        }
    }
  }
  private def getDesignatedValueAvg(rule: HourCalculationRule, current: DateTime, recordMap: Map[String, Seq[Record]]): Seq[MtRecord] = {
    val start = current.minusHours(rule.delay)
    rule.monitorTypes map {
      mt =>
        val records = recordMap.getOrElse(mt, Seq.empty[Record])
        if (records.nonEmpty) {
          val filteredRecords = records.filter(r => {
            //00:40, 00:55, 01:10, 01:25
            r.time == start.plusMinutes(40) ||
              r.time == start.plusMinutes(55) ||
              r.time == start.plusHours(1).plusMinutes(10) ||
              r.time == start.plusHours(1).plusMinutes(25)
          })
          val values = filteredRecords flatMap (_.value)
          // Count occurrences of each string
          val statusCount = filteredRecords.groupBy(_.status).mapValues(_.size)
          // Find the string with the maximum count
          val (status, count) = statusCount.maxBy(_._2)

          val avg = if (count != 0)
            Some(values.sum / values.length)
          else
            None

          if (values.nonEmpty && count >= 3)
            MtRecord(mt, avg, status)
          else
            MtRecord(mt, avg, MonitorStatus.DataLost)
        } else
          MtRecord(mt, None, MonitorStatus.DataLost)
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
                    getLastMinValue(rule, recordMap)
                  case DesignatedMinAvg =>
                    getDesignatedValueAvg(rule, current, recordMap)
                  case LastHourMinAvg=>
                    getLastHourMinAvg(rule, current, recordMap)
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
