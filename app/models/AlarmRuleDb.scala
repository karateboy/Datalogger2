package models

import org.mongodb.scala.result.{DeleteResult, UpdateResult}
import play.api.Logger

import java.time.format.{DateTimeFormatter, FormatStyle}
import java.time.{Instant, LocalDateTime, LocalTime, ZoneId}
import scala.concurrent.Future
import scala.math.Ordering.Implicits.infixOrderingOps

case class AlarmRule(_id: String, monitorTypes: Seq[String], monitors: Seq[String], max: Option[Double], min: Option[Double],
                     alarmLevel: Int, tableTypes: Seq[String], enable: Boolean, startTime: Option[String], endTime: Option[String],
                     messageTemplate: Option[String],
                     coldPeriod: Option[Int])

trait AlarmRuleDb {
  val logger: Logger = Logger(this.getClass)

  def getRulesAsync: Future[Seq[AlarmRule]]

  def upsertAsync(rule: AlarmRule): Future[UpdateResult]

  def deleteAsync(_id: String): Future[DeleteResult]

  private var alarmRuleTriggerMap = Map.empty[AlarmRule, Instant]

  def checkAlarm(tableType: TableType#Value, recordList: RecordList, alarmRules: Seq[AlarmRule])
                (monitorDB: MonitorDB, monitorTypeDB: MonitorTypeDB, alarmDB: AlarmDB): Seq[Alarm] = {
    val mtMap = recordList.mtMap
    val alarms =
      for {
        rule <- alarmRules if rule.enable && rule.tableTypes.contains(tableType.toString)
        monitor <- rule.monitors if monitor == recordList._id.monitor
        monitorType <- rule.monitorTypes if mtMap.contains(monitorType)
      } yield {
        def parseLocalTime: String => Option[LocalTime] = (str: String) => {
          try {
            str.split(":") match {
              case Array(h, m) =>
                Some(LocalTime.of(h.toInt, m.toInt))
              case _ => None
            }
          } catch {
            case ex: Throwable =>
              logger.error(s"parse $str failed", ex)
              None
          }
        }

        val alarmLevel: Int = if (rule.coldPeriod.isEmpty)
          rule.alarmLevel
        else {
          val lastTrigger = alarmRuleTriggerMap.get(rule)
          if (lastTrigger.isDefined) {
            val now = Instant.now()
            val coldPeriod = rule.coldPeriod.get
            if (now < lastTrigger.get.plusSeconds(60 * coldPeriod)) {
              Alarm.Level.INFO
            } else {
              alarmRuleTriggerMap += rule -> Instant.now()
              rule.alarmLevel
            }
          } else {
            alarmRuleTriggerMap += rule -> Instant.now()
            rule.alarmLevel
          }
        }

        val startTime = rule.startTime.flatMap(parseLocalTime).getOrElse(LocalTime.MIN)
        val endTime = rule.endTime.flatMap(parseLocalTime).getOrElse(LocalTime.MAX)
        val time = LocalDateTime.ofInstant(recordList._id.time.toInstant, ZoneId.systemDefault()).toLocalTime
        val max = rule.max.getOrElse(Double.MaxValue)
        val min = rule.min.getOrElse(Double.MinValue)
        val mtData = mtMap(monitorType)

        for (v <- mtData.value if MonitorStatus.isValid(mtData.status)) yield {
          if ((startTime <= time && time < endTime) && (v > max || v < min)) {
            val monitor = monitorDB.map(recordList._id.monitor)
            val monitorType = monitorTypeDB.map(mtData.mtName)
            val localDateTime = LocalDateTime.ofInstant(recordList._id.time.toInstant, ZoneId.systemDefault())
            val message = if (rule.messageTemplate.isEmpty)
              s"${monitor.desc} ${monitorType.desp} ${localDateTime.toString} 測值 ${monitorTypeDB.format(mtData.mtName, Some(v))} 超限"
            else {
              var mtMessage = rule.messageTemplate.get
                .replace("$monitor", monitor.desc)
                .replace("$mt", monitorType.desp)
                .replace("$time", localDateTime.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)))
                .replace("$value", monitorTypeDB.format(mtData.mtName, Some(v)))

              mtMap.foreach { case (mtName, mtData) =>
                mtMessage = mtMessage.replace(s"$$($mtName)",
                  s"${monitorTypeDB.map(mtName).desp} ${monitorTypeDB.format(mtName, mtData.value)} ${monitorTypeDB.map(mtName).unit}")
              }
              mtMessage
            }


            Some(Alarm(recordList._id.time, alarmDB.src(mtData.mtName), alarmLevel, message))
          } else
            None
        }
      }
    alarms.flatten.flatten
  }
}
