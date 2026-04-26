package models

import akka.actor.ActorRef
import com.github.nscala_time.time.Imports.{DateTime, _}
import models.ForwardManager.ForwardHourRecord
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

  def toggleSignal(mtId: String, delay: Int): Unit = {
    manager ! ToggleSignal(mtId, delay)
  }

  def updateStatusMap(mtRecord: MtRecord, mtMap: mutable.Map[String, mutable.Map[String, ListBuffer[MtRecord]]]): Unit = {
    val statusMap = mtMap.getOrElseUpdate(mtRecord.mtName, mutable.Map.empty[String, ListBuffer[MtRecord]])
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

  private def calculateDayAvgHourRecord(monitor: String,
                                        mtList: Seq[String],
                                        current: DateTime,
                                        currentHourRecords: Seq[MtRecord]): Future[Seq[MtRecord]] = {

    for (recordMap <- recordOp.getMtRecordMapFuture(recordOp.HourCollection)
    (monitor, mtList, current - 24.hour, current)) yield {
      val mtMap = mutable.Map.empty[String, mutable.Map[String, ListBuffer[MtRecord]]]

      for (mtRecordList <- recordMap.values; mtRecord <- mtRecordList)
        updateStatusMap(mtRecord, mtMap)

      currentHourRecords.foreach(mtRecord => updateStatusMap(mtRecord, mtMap))

      val mtDataList = calculateHourAvgMap(mtMap, alwaysValid = false, monitorTypeDb)
      val mapDailyMtDataList = mtDataList.map(mtRecord => {
        if (MonitorType.DailyAvgMonitorTypeMap.contains(mtRecord.mtName))
          mtRecord.copy(mtName = MonitorType.DailyAvgMonitorTypeMap(mtRecord.mtName))
        else
          mtRecord
      })
      mapDailyMtDataList.toSeq
    }
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

      for (mtRecordList <- recordMap.values; mtRecord <- mtRecordList)
        updateStatusMap(mtRecord, mtMap)

      val mtDataList = calculateHourAvgMap(mtMap, alwaysValid, monitorTypeDb)
      val hourRecordListsFuture = HourCalculationRule.calculateHourRecord(monitor, current, recordOp)
      val dailyAvgMtRecordsFuture = calculateDayAvgHourRecord(monitor, MonitorType.DailyAvgMonitorTypes, current, mtDataList.toSeq)
      for (ruleHourRecordLists <- hourRecordListsFuture; dailyAvgMtRecords <- dailyAvgMtRecordsFuture) {
        val defaultHourRecordList = RecordList.factory(current.minusHours(1).toDate, mtDataList.toSeq ++ dailyAvgMtRecords, monitor)
        val hourRecordLists = (ruleHourRecordLists :+ defaultHourRecordList).sortBy(_._id.time)

        // Check alarm
        if (checkAlarm) {
          val alarms = alarmRuleDb.checkAlarm(tableType.hour, defaultHourRecordList, alarmRules)(monitorDB, monitorTypeDb, alarmDb)
          alarms.foreach(alarmDb.log)
        }

        val f = recordOp.upsertManyRecordsChecked(recordOp.HourCollection)(hourRecordLists)
        if (forward) {
          f onComplete {
            case Success(_) =>
              val start = new DateTime(hourRecordLists.head._id.time)
              val end = new DateTime(hourRecordLists.last._id.time)
              manager ! ForwardHourRecord(start, end.plusHours(1))
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
}


