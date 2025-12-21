package models

import akka.actor.ActorRef
import com.github.nscala_time.time.Imports.DateTime
import models.ForwardManager.ForwardHour
import org.mongodb.scala.result.UpdateResult
import play.api.Logger

import javax.inject.{Inject, Named, Singleton}
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.util.{Failure, Success}
import com.github.nscala_time.time.Imports._
import scala.concurrent.ExecutionContext.Implicits.global
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

  def recalculateHourData(monitor: String, current: DateTime, forward: Boolean = true, alwaysValid: Boolean = false)
                         (mtList: Seq[String], monitorTypeDB: MonitorTypeDB): Future[UpdateResult] = {
    val ret =
      for (recordMap <- recordOp.getMtRecordMapFuture(recordOp.MinCollection)(monitor, mtList, current - 1.hour, current);
           alarmRules <- alarmRuleDb.getRulesAsync) yield {
        val mtMap = mutable.Map.empty[String, mutable.Map[String, ListBuffer[MtRecord]]]

        for {
          (mt, mtRecordList) <- recordMap
          mtRecord <- mtRecordList
        } {
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

        val mtDataList = calculateHourAvgMap(mtMap, alwaysValid, monitorTypeDB)
        val recordList = RecordList.factory(current.minusHours(1).toDate, mtDataList.toSeq, monitor)
        // Alarm check
        val alarms = alarmRuleDb.checkAlarm(tableType.hour, recordList, alarmRules)(monitorDB, monitorTypeDb, alarmDb)
        alarms.foreach(alarmDb.log)

        val f = recordOp.upsertRecord(recordOp.HourCollection)(recordList)
        if (forward) {
          f onComplete {
            case Success(_) =>
              manager ! ForwardHour
              for {cdxConfig <- sysConfigDB.getCdxConfig if monitor == Monitor.activeId
                   cdxMtConfigs <- sysConfigDB.getCdxMonitorTypes} {
                cdxUploader.upload(recordList = recordList, cdxConfig = cdxConfig, mtConfigs = cdxMtConfigs)
                newTaipeiOpenData.upload(recordList, cdxMtConfigs)
              }

            case Failure(exception) =>
              logger.error("failed", exception)
          }
        }

        f
      }
    ret.flatMap(x => x)
  }

  def resetReaders(): Unit = {
    manager ! ReaderReset
  }
}


