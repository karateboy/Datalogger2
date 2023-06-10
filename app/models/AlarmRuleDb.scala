package models

import org.mongodb.scala.result.{DeleteResult, UpdateResult}
import play.api.Logger

import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, LocalTime, ZoneId}
import scala.concurrent.Future
import scala.math.Ordering.Implicits.infixOrderingOps

case class AlarmRule(_id: String, monitorTypes: Seq[String], monitors: Seq[String], max: Option[Double], min:Option[Double],
                     alarmLevel:Int, tableTypes: Seq[String], enable: Boolean, startTime: Option[String], endTime:Option[String])
trait AlarmRuleDb {

  def getRulesAsync: Future[Seq[AlarmRule]]

  def upsertAsync(rule: AlarmRule): Future[UpdateResult]

  def deleteAsync(_id: String): Future[DeleteResult]

  def checkAlarm(tableType: TableType.Value, recordList: RecordList, alarmRules: Seq[AlarmRule])
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
            Some(LocalTime.parse(str, DateTimeFormatter.ofPattern("H:m")))
          } catch {
            case ex: Throwable =>
              Logger.error(s"parse $str failed", ex)
              None
          }
        }

        val startTime = rule.startTime.flatMap(parseLocalTime).getOrElse(LocalTime.MIN)
        val endTime = rule.endTime.flatMap(parseLocalTime).getOrElse(LocalTime.MAX)
        val time = LocalDateTime.ofInstant(recordList._id.time.toInstant, ZoneId.systemDefault()).toLocalTime
        val max = rule.max.getOrElse(Double.MaxValue)
        val min = rule.min.getOrElse(Double.MinValue)
        val mtData = mtMap(monitorType)

        for (v <- mtData.value if MonitorStatus.isValid(mtData.status)) yield {
          if ((startTime <= time   && time < endTime) && (v > max || v < min)) {
            val monitor = monitorDB.map(recordList._id.monitor)
            val monitorType = monitorTypeDB.map(mtData.mtName)
            val localDateTime = LocalDateTime.ofInstant(recordList._id.time.toInstant, ZoneId.systemDefault())
            val msg = s"${monitor.desc} ${monitorType.desp} ${localDateTime.toString} 測值 ${monitorTypeDB.format(mtData.mtName, Some(v))} 超限"
            Some(Alarm(recordList._id.time, alarmDB.src(mtData.mtName), rule.alarmLevel, msg))
          } else
            None
        }
      }
    alarms.flatten.flatten
  }
}
