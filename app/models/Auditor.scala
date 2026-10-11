package models

import com.github.nscala_time.time.Imports._
import akka.actor.Actor
import models.mongodb.RecordOp
import play.api.Logger

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global

object Auditor {
  case class Audit(current: DateTime)

  private def checkSpikeRule(current: DateTime, rules: Seq[SpikeRule])
                            (monitorDB: MonitorDB,
                             monitorTypeDB: MonitorTypeDB,
                             recordOp: RecordDB,
                             alarmDB: AlarmDB): Unit = {
    val monitorSet = scala.collection.mutable.Set.empty[String]
    val mtSet = scala.collection.mutable.Set.empty[String]
    for (rule <- rules) {
      monitorSet.add(rule._id.monitor)
      mtSet.add(rule._id.monitorType)
    }

    for (m <- monitorSet) {
      val dataTime = (current-1.hour).toDate
      val mtList = mtSet.toList
      for (recordMap <- recordOp.getRecordMapFuture(recordOp.HourCollection)(m, mtList, current - 3.hour, current)) {
        val matchedRules = rules.filter(rule => rule._id.monitor == m && mtList.contains(rule._id.monitorType))
        for {rule <- matchedRules
             mtRecords = recordMap.getOrElse(rule._id.monitorType, Seq.empty) if mtRecords.length >= 3
             } {
          val head = mtRecords.head
          val mid = mtRecords(1)
          val last = mtRecords.last
          if (mtRecords.forall(record => MonitorStatus.isValid(record.status))) {
            for (v1 <- head.value; v2 <- mid.value; v <- last.value) {
              if (Math.abs((v1 + v2) / 2 - v) > rule.abs) {
                recordOp.updateRecordStatus(recordOp.HourCollection)(dataTime.getTime, rule._id.monitorType, "A10", rule._id.monitor)
                alarmDB.log(alarmDB.src(rule._id.monitorType), Alarm.Level.ERR,
                  s"${monitorDB.map(rule._id.monitor).desc} ${monitorTypeDB.map(rule._id.monitorType).desp} 突波高值檢核觸發")
              }
            }
          }
        }
      }
    }
  }

  private def checkConstantRule(current: DateTime, rules: Seq[ConstantRule])
                               (monitorDB: MonitorDB,
                                monitorTypeDB: MonitorTypeDB,
                                recordOp: RecordDB,
                                alarmDB: AlarmDB): Unit = {
    val monitorSet = scala.collection.mutable.Set.empty[String]
    val mtSet = scala.collection.mutable.Set.empty[String]
    for (rule <- rules) {
      monitorSet.add(rule._id.monitor)
      mtSet.add(rule._id.monitorType)
    }

    for (m <- monitorSet) {
      val mtList = mtSet.toList
      val dataTime = (current-1.hour).toDate
      val maxCount = rules.maxBy(_.count).count
      for (recordMap <- recordOp.getRecordMapFuture(recordOp.HourCollection)(m, mtList, current - (maxCount + 1).hour, current)) {
        val matchedRules = rules.filter(rule => rule._id.monitor == m && mtList.contains(rule._id.monitorType))
        for {rule <- matchedRules
             mtRecords = recordMap.getOrElse(rule._id.monitorType, Seq.empty) if mtRecords.length >= rule.count
             } {
          val mtRecordToCheck = mtRecords.reverse.take(rule.count)
          if (mtRecordToCheck.forall(record => MonitorStatus.isValid(record.status))) {
            val values = mtRecordToCheck.flatMap(_.value)
            val count = values.groupBy(identity).mapValues(_.size).values.max
            if (count >= rule.count) {
              recordOp.updateRecordStatus(recordOp.HourCollection)(dataTime.getTime, rule._id.monitorType, "A10", rule._id.monitor)
              alarmDB.log(alarmDB.src(rule._id.monitorType), Alarm.Level.ERR,
                s"${monitorDB.map(rule._id.monitor).desc} ${monitorTypeDB.map(rule._id.monitorType).desp} 定值檢核觸發")
            }

          }
        }
      }
    }

  }

  private def checkVariationRule(current: DateTime, rules: Seq[VariationRule])
                                (monitorDB: MonitorDB,
                                 monitorTypeDB: MonitorTypeDB,
                                 recordOp: RecordDB,
                                 alarmDB: AlarmDB) = {
    val monitorSet = scala.collection.mutable.Set.empty[String]
    val mtSet = scala.collection.mutable.Set.empty[String]
    for (rule <- rules) {
      monitorSet.add(rule._id.monitor)
      mtSet.add(rule._id.monitorType)
    }

    for (m <- monitorSet) {
      val dataTime = (current-1.hour).toDate
      val mtList = mtSet.toList
      for (recordMap <- recordOp.getRecordMapFuture(recordOp.HourCollection)(m, mtList, current - 2.hour, current)) {
        val matchedRules = rules.filter(rule => rule._id.monitor == m && mtList.contains(rule._id.monitorType))
        for {rule <- matchedRules
             mtRecords = recordMap.getOrElse(rule._id.monitorType, Seq.empty) if mtRecords.length >= 2
             } {
          val head = mtRecords.head
          val last = mtRecords.last
          if (head.status == MonitorStatus.NormalStat && last.status == MonitorStatus.NormalStat) {
            for (v1 <- head.value; v2 <- last.value) {
              if (Math.abs(v1 - v2) > rule.abs) {
                recordOp.updateRecordStatus(recordOp.HourCollection)(dataTime.getTime, rule._id.monitorType, "A10", rule._id.monitor)
                alarmDB.log(alarmDB.src(rule._id.monitorType), Alarm.Level.ERR,
                  s"${monitorDB.map(rule._id.monitor).desc} ${monitorTypeDB.map(rule._id.monitorType).desp} 變動檢核觸發")
              }
            }
          }
        }
      }
    }
  }

  trait Factory {
    def apply(): Actor
  }
}
import scala.concurrent.ExecutionContext.Implicits.global

class Auditor @Inject()(monitorDB: MonitorDB,
                        monitorTypeDB: MonitorTypeDB,
                        recordOp: RecordOp,
                        spikeRuleOp: SpikeRuleDB,
                        constantRuleOp: ConstantRuleDB,
                        variationRuleOp: VariationRuleDB,
                        alarmDB: AlarmDB) extends Actor {
  val logger: Logger = Logger(this.getClass)

  import Auditor._

  logger.info("Auditor start!")

  override def receive: Receive = {
    case Audit(current) =>
      try {
        for (rules <- spikeRuleOp.getRules())
          checkSpikeRule(current, rules)(monitorDB, monitorTypeDB, recordOp, alarmDB)

        for (rules <- constantRuleOp.getRules())
          checkConstantRule(current, rules)(monitorDB, monitorTypeDB, recordOp, alarmDB)

        for (rules <- variationRuleOp.getRules())
          checkVariationRule(current, rules)(monitorDB, monitorTypeDB, recordOp, alarmDB)
      } catch {
        case ex: Throwable =>
          logger.error("Failed to audit", ex)
      }
  }
}
