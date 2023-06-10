package models

import akka.actor._
import com.github.nscala_time.time.Imports._
import models.Calibration.{CalibrationListMap, emptyCalibrationListMap, findTargetCalibrationMB}
import models.DataCollectManager.{calculateHourAvgMap, calculateMinAvgMap}
import models.ForwardManager.{ForwardHour, ForwardHourRecord, ForwardMin, ForwardMinRecord}
import models.ModelHelper._
import models.TapiTxx.T700_STANDBY_SEQ
import org.mongodb.scala.result.UpdateResult
import play.api._
import play.api.libs.concurrent.InjectedActorSupport

import javax.inject._
import scala.collection.mutable.ListBuffer
import scala.collection.{immutable, mutable}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS, SECONDS}
import scala.language.postfixOps
import scala.math.BigDecimal.RoundingMode
import scala.util.{Failure, Success}


case class StartInstrument(inst: Instrument)

case class StopInstrument(id: String)

case class RestartInstrument(id: String)

case object RestartMyself

case class SetState(instId: String, state: String)

case class SetMonitorTypeState(instId: String, mt: String, state: String)

case class MonitorTypeData(mt: String, var value: Double, status: String)

case class ReportData(private val _dataList: List[MonitorTypeData]) {
  def dataList(monitorTypeDB: MonitorTypeDB): List[MonitorTypeData] =
    _dataList.map(mtd => {
      val mtCase = monitorTypeDB.map(mtd.mt)
      val m: Double = mtCase.fixedM.getOrElse(1d)
      val b: Double = mtCase.fixedB.getOrElse(0d)
      mtd.value = mtd.value * m + b
      mtd
    })
}

case class SignalData(mt: String, value: Boolean)

case class ReportSignalData(dataList: Seq[SignalData])

case class ExecuteSeq(seqName: String, on: Boolean)

case object PurgeSeq

case object CalculateData

case object AutoSetState

case class AutoCalibration(instId: String)

case class ManualZeroCalibration(instId: String)

case class ManualSpanCalibration(instId: String)

case class CalibrationType(auto: Boolean, zero: Boolean)

object AutoZero extends CalibrationType(true, true)

object AutoSpan extends CalibrationType(true, false)

object ManualZero extends CalibrationType(false, true)

object ManualSpan extends CalibrationType(false, false)

case class WriteTargetDO(instId: String, bit: Int, on: Boolean)

case class ToggleTargetDO(instId: String, bit: Int, seconds: Int)

case class WriteDO(bit: Int, on: Boolean)

case object ResetCounter

case object GetLatestData

case object GetLatestSignal

case class IsTargetConnected(instId: String)

case object IsConnected

case class AddSignalTypeHandler(mtId: String, handler: Boolean => Unit)

case class WriteSignal(mtId: String, bit: Boolean)

case object CheckInstruments

case class UpdateCalibrationMap(map: CalibrationListMap)

case object ReaderReset

@Singleton
class DataCollectManagerOp @Inject()(@Named("dataCollectManager") manager: ActorRef,
                                     instrumentOp: InstrumentDB,
                                     recordOp: RecordDB,
                                     alarmDb: AlarmDB,
                                     monitorDB: MonitorDB,
                                     monitorTypeDb: MonitorTypeDB,
                                     sysConfigDB: SysConfigDB,
                                     alarmRuleDb: AlarmRuleDb,
                                     cdxUploader: CdxUploader)() {
  def startCollect(inst: Instrument): Unit = {
    manager ! StartInstrument(inst)
  }

  def startCollect(id: String): Unit = {
    val instList = instrumentOp.getInstrument(id)
    instList.map { inst => manager ! StartInstrument(inst) }
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

  def toggleTargetDO(id: String, bit: Int, seconds: Int): Unit = {
    manager ! ToggleTargetDO(id, bit, seconds)
  }

  def executeSeq(seqName: String, on: Boolean): Unit = {
    manager ! ExecuteSeq(seqName, on)
  }

  def getLatestData: Future[Map[String, Record]] = {
    import akka.pattern.ask
    import akka.util.Timeout

    import scala.concurrent.duration._
    implicit val timeout = Timeout(Duration(3, SECONDS))

    val f = manager ? GetLatestData
    f.mapTo[Map[String, Record]]
  }

  def getLatestSignal: Future[Map[String, Boolean]] = {
    import akka.pattern.ask
    import akka.util.Timeout

    import scala.concurrent.duration._
    implicit val timeout = Timeout(Duration(3, SECONDS))

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
        val recordList = RecordList.factory(current.minusHours(1), mtDataList.toSeq, monitor)
        // Alarm check
        val alarms = alarmRuleDb.checkAlarm(TableType.hour, recordList, alarmRules)(monitorDB, monitorTypeDb, alarmDb)
        alarms.foreach(alarmDb.log)

        val f = recordOp.upsertRecord(recordOp.HourCollection)(recordList)
        if (forward) {
          f onComplete {
            case Success(_) =>
              manager ! ForwardHour
              for (cdxConfig <- sysConfigDB.getCdxConfig if cdxConfig.enable; cdxMtConfigs <- sysConfigDB.getCdxMonitorTypes)
                cdxUploader.upload(recordList = recordList, cdxConfig = cdxConfig, mtConfigs = cdxMtConfigs)

            case Failure(exception) =>
              Logger.error("failed", exception)
          }
        }

        f
      }
    ret.flatMap(x => x)
  }
}

object DataCollectManager {
  var effectiveRatio = 0.75

  def updateEffectiveRatio(sysConfig: SysConfigDB): Unit = {
    for (ratio <- sysConfig.getEffectiveRatio)
      effectiveRatio = ratio
  }

  def calculateMinAvgMap(monitorTypeDB: MonitorTypeDB,
                         mtMap: Map[String, Map[String, ListBuffer[(DateTime, Double)]]],
                         alwaysValid: Boolean)(calibrationMap: CalibrationListMap, dateTime: DateTime): immutable.Iterable[MtRecord] = {
    for {
      (mt, statusMap) <- mtMap
      total = statusMap.map {
        _._2.size
      }.sum if total != 0
    } yield {
      val minuteRawAvg: (Option[Double], String) = {
        val totalSize = statusMap map {
          _._2.length
        } sum
        val statusKV = {
          val kv = statusMap.maxBy(kv => kv._2.length)
          if (kv._1 == MonitorStatus.NormalStat && (alwaysValid ||
            statusMap(kv._1).size < totalSize * effectiveRatio)) {
            //return most status except normal
            val noNormalStatusMap = statusMap - kv._1
            noNormalStatusMap.maxBy(kv => kv._2.length)
          } else
            kv
        }
        val values = statusKV._2.map(_._2)
        val avgOpt = if (values.length == 0)
          None
        else {
          val mtCase = monitorTypeDB.map(mt)
          mt match {
            case MonitorType.WIN_DIRECTION =>
              val windDir = values
              if (mtMap.contains(MonitorType.WIN_SPEED)) {
                val speedStatusMap = mtMap(MonitorType.WIN_SPEED)
                val speedMostStatus = speedStatusMap.maxBy(kv => kv._2.length)
                val speeds = speedMostStatus._2.map(_._2)
                directionAvg(speeds.toList, windDir.toList)
              } else { //assume wind speed is all equal
                val speeds = List.fill(windDir.length)(1.0)
                directionAvg(speeds, windDir.toList)
              }
            case MonitorType.DIRECTION =>
              val directions = values
              if (mtMap.contains(MonitorType.SPEED)) {
                val speedMostStatus = mtMap(MonitorType.SPEED).maxBy(kv => kv._2.length)
                val speeds = speedMostStatus._2.map(_._2)
                directionAvg(speeds.toList, directions.toList)
              } else { //assume wind speed is all equal
                val speeds = List.fill(directions.length)(1.0)
                directionAvg(speeds, directions.toList)
              }
            case MonitorType.RAIN =>
              if (mtCase.accumulated.contains(true))
                Some(values.max)
              else
                Some(values.sum)
            case MonitorType.PM10 =>
              Some(values.last)
            case MonitorType.PM25 =>
              Some(values.last)
            case _ =>
              if(mtCase.acoustic.contains(true)){
                val noNanValues = values.filter(v => !v.isNaN)
                if(noNanValues.isEmpty)
                  None
                else
                  Some(10 * Math.log10(noNanValues.map(v => Math.pow(10, v / 10)).sum / noNanValues.size))
              } else {
                val v = values.sum / values.length
                if (v.isNaN)
                  None
                else
                  Some(values.sum / values.length)
              }
          }
        }

        try {
          val roundedAvg =
            for (avg <- avgOpt) yield
              BigDecimal(avg).setScale(monitorTypeDB.map(mt).prec, RoundingMode.HALF_EVEN).doubleValue()
          (roundedAvg, statusKV._1)
        } catch {
          case _: Throwable =>
            (None, statusKV._1)
        }
      }

      val mintueAvg = minuteRawAvg._1.map(rawValue => {
        val mtCase = monitorTypeDB.map(mt)

        rawValue
      })

      val (mOpt, bOpt) = findTargetCalibrationMB(calibrationMap, mt, dateTime).getOrElse((None, None))
      monitorTypeDB.getMinMtRecordByRawValue(mt, minuteRawAvg._1, minuteRawAvg._2)(mOpt, bOpt)
    }
  }

  def calculateHourAvgMap(mtMap: mutable.Map[String, mutable.Map[String, ListBuffer[MtRecord]]],
                          alwaysValid: Boolean,
                          monitorTypeDB: MonitorTypeDB): mutable.Iterable[MtRecord] = {
    for {
      (mt, statusMap) <- mtMap
      totalSize = statusMap.map {
        _._2.size
      }.sum if totalSize != 0
    } yield {
      val status = {
        val kv = statusMap.maxBy(kv => kv._2.length)
        if (kv._1 == MonitorStatus.NormalStat && (alwaysValid ||
          statusMap(kv._1).size < totalSize * effectiveRatio)) {
          //return most status except normal
          val noNormalStatusMap = statusMap - kv._1
          noNormalStatusMap.maxBy(kv => kv._2.length)._1
        } else
          kv._1
      }
      val mtRecords = if (statusMap.contains(MonitorStatus.NormalStat))
        statusMap(MonitorStatus.NormalStat)
      else
        ListBuffer.empty[MtRecord]

      def hourAccumulator(values: Seq[Double], isRaw: Boolean): Option[Double] = {
        if (values.isEmpty)
          return None
        else {
          val mtCase = monitorTypeDB.map(mt)
          mt match {
            case MonitorType.WIN_DIRECTION =>
              val windDir = values
              if (mtMap.contains(MonitorType.WIN_SPEED)) {
                val windSpeedMostStatus = mtMap(MonitorType.WIN_SPEED).maxBy(kv => kv._2.length)
                val windSpeed = windSpeedMostStatus._2
                if (isRaw)
                  directionAvg(windSpeed.flatMap(_.rawValue), values)
                else
                  directionAvg(windSpeed.flatMap(_.value), values)
              } else { //assume wind speed is all equal
                val windSpeed =
                  for (r <- 1 to windDir.length)
                    yield 1.0

                directionAvg(windSpeed, values)
              }
            case MonitorType.RAIN =>
              if (mtCase.accumulated.contains(true))
                Some(values.max)
              else
                Some(values.sum)
            case MonitorType.PM10 =>
              Some(values.last)
            case MonitorType.PM25 =>
              Some(values.last)
            case _ =>
              if (mtCase.acoustic.contains(true)) {
                val noNanValues = values.filter(v => !v.isNaN)
                if(noNanValues.isEmpty)
                  None
                else
                  Some(10 * Math.log10(noNanValues.map(v => Math.pow(10, v / 10)).sum / noNanValues.size))
              } else {
                val v = values.sum / values.length
                if (v.isNaN)
                  None
                else
                  Some(values.sum / values.length)
              }
          }
        }
      }

      val roundedAvg =
        for (avg <- hourAccumulator(mtRecords.flatMap(_.value), isRaw = false)) yield
          BigDecimal(avg).setScale(monitorTypeDB.map(mt).prec, RoundingMode.HALF_EVEN).doubleValue()

      val roundedRawAvg: Option[Double] =
        for (avg <- hourAccumulator(mtRecords.flatMap(_.rawValue), isRaw = true)) yield
          BigDecimal(avg).setScale(monitorTypeDB.map(mt).prec, RoundingMode.HALF_EVEN).doubleValue()

      MtRecord(mt, roundedAvg, status, rawValue = roundedRawAvg)
    }
  }

}

@Singleton
class DataCollectManager @Inject()
(config: Configuration, recordOp: RecordDB, monitorTypeOp: MonitorTypeDB, monitorOp: MonitorDB,
 dataCollectManagerOp: DataCollectManagerOp,
 instrumentTypeOp: InstrumentTypeOp, alarmOp: AlarmDB, instrumentOp: InstrumentDB,
 calibrationDB: CalibrationDB,
 sysConfig: SysConfigDB, forwardManagerFactory: ForwardManager.Factory) extends Actor with InjectedActorSupport {
  Logger.info(s"store second data = ${LoggerConfig.config.storeSecondData}")
  DataCollectManager.updateEffectiveRatio(sysConfig)

  for (aqiMonitorTypes <- sysConfig.getAqiMonitorTypes)
    AQI.updateAqiTypeMapping(aqiMonitorTypes)

  val timer = {
    import scala.concurrent.duration._
    //Try to trigger at 30 sec
    val next30 = DateTime.now().withSecondOfMinute(30).plusMinutes(1)
    val postSeconds = new org.joda.time.Duration(DateTime.now, next30).getStandardSeconds
    context.system.scheduler.schedule(Duration(postSeconds, SECONDS), Duration(1, MINUTES), self, CalculateData)
  }

  val autoStateConfigOpt: Option[Seq[AutoStateConfig]] = AutoState.getConfig(config)
  val autoStateTimerOpt: Option[Cancellable] =
    if (autoStateConfigOpt.nonEmpty) {
      import scala.concurrent.duration._
      //Try to trigger at 30 sec
      val next = DateTime.now().withSecondOfMinute(0).plusMinutes(1)
      val postSeconds = new org.joda.time.Duration(DateTime.now, next).getStandardSeconds
      Some(context.system.scheduler.schedule(FiniteDuration(postSeconds, SECONDS), Duration(1, MINUTES), self, AutoState))
    } else
      None

  val readerList: List[ActorRef] = startReaders()
  val forwardManagerOpt: Option[ActorRef] =
    for (serverConfig <- ForwardManager.getConfig(config)) yield
      injectedChild(forwardManagerFactory(serverConfig.server, serverConfig.monitor), "forwardManager")
  var isT700Calibrator = true
  var calibratorOpt: Option[ActorRef] = None
  var digitalOutputOpt: Option[ActorRef] = None
  var onceTimer: Option[Cancellable] = None
  var signalTypeHandlerMap = Map.empty[String, Map[ActorRef, Boolean => Unit]]

  def startReaders(): List[ActorRef] = {
    val readers: ListBuffer[ActorRef] = ListBuffer.empty[ActorRef]

    for(readerRef<-SpectrumReader.start(config, context.system, sysConfig, monitorTypeOp, recordOp, dataCollectManagerOp))
      readers.append(readerRef)

    for(readerRef<-WeatherReader.start(config, context.system, sysConfig, monitorTypeOp, recordOp, dataCollectManagerOp))
      readers.append(readerRef)

    for(readerRef<-VocReader.start(config, context.system, monitorOp, monitorTypeOp, recordOp, self))
      readers.append(readerRef)

    readers.toList
  }


  {
    val instrumentList = instrumentOp.getInstrumentList()
    instrumentList.foreach {
      inst =>
        if (inst.active)
          self ! StartInstrument(inst)
    }
    Logger.info("DataCollect manager started")
  }

  def checkMinDataAlarm(minMtAvgList: Iterable[MtRecord]) = {
    var overThreshold = false
    for {
      hourMtData <- minMtAvgList
      mt = hourMtData.mtName
      value = hourMtData.value
      status = hourMtData.status
    } {
      val mtCase = monitorTypeOp.map(mt)
      if (MonitorStatus.isValid(status))
        for (std_law <- mtCase.std_law; v <- value) {
          if (v > std_law) {
            val msg = s"${mtCase.desp}: ${monitorTypeOp.format(mt, value)}超過分鐘高值 ${monitorTypeOp.format(mt, mtCase.std_law)}"
            alarmOp.log(alarmOp.src(mt), alarmOp.Level.INFO, msg)
            overThreshold = true
            mtCase.overLawSignalType.foreach(signalType => {
              self ! WriteSignal(signalType, true)
            })
          }
        }
    }
    overThreshold
  }

  def receive: Receive = handler(Map.empty[String, InstrumentParam], Map.empty[ActorRef, String],
    Map.empty[String, Map[String, Record]],
    List.empty[(DateTime, String, List[MonitorTypeData])],
    List.empty[String],
    Map.empty[String, Map[ActorRef, Boolean => Unit]],
    Map.empty[String, (DateTime, Boolean)],
    emptyCalibrationListMap)

  def handler(instrumentMap: Map[String, InstrumentParam],
              collectorInstrumentMap: Map[ActorRef, String],
              latestDataMap: Map[String, Map[String, Record]],
              mtDataList: List[(DateTime, String, List[MonitorTypeData])],
              restartList: Seq[String],
              signalTypeHandlerMap: Map[String, Map[ActorRef, Boolean => Unit]],
              signalDataMap: Map[String, (DateTime, Boolean)],
              calibrationListMap: CalibrationListMap): Receive = {
    case ForwardHour =>
      for (forwardManager <- forwardManagerOpt)
        forwardManager ! ForwardHour

    case ForwardMin =>
      for (forwardManager <- forwardManagerOpt)
        forwardManager ! ForwardMin


    case fhr: ForwardHourRecord =>
      for (forwardManager <- forwardManagerOpt)
        forwardManager ! fhr

    case fmr: ForwardMinRecord =>
      for (forwardManager <- forwardManagerOpt)
        forwardManager ! fmr


    case AutoState =>
      for (autoStateConfigs <- autoStateConfigOpt)
        autoStateConfigs.foreach(config => {
          if (config.period == "Hour" && config.time.toInt == DateTime.now().getMinuteOfHour) {
            Logger.info(s"AutoState=>$config")
            self ! SetState(config.instID, config.state)
          }
        })

    case StartInstrument(inst) =>
      if (!instrumentTypeOp.map.contains(inst.instType))
        Logger.error(s"${inst._id} of ${inst.instType} is unknown!")
      else if (instrumentMap.contains(inst._id))
        Logger.error(s"${inst._id} is already started!")
      else {
        val instType = instrumentTypeOp.map(inst.instType)
        val collector = instrumentTypeOp.start(inst.instType, inst._id, inst.protocol, inst.param)
        val monitorTypes = instType.driver.getMonitorTypes(inst.param)
        val calibrateTimeOpt = instType.driver.getCalibrationTime(inst.param)
        val timerOpt = calibrateTimeOpt.map { localtime =>
          val now = DateTime.now()
          val calibrationTime = now.toLocalDate().toDateTime(localtime)
          val period = if (now < calibrationTime)
            new Period(now, calibrationTime)
          else
            new Period(now, calibrationTime + 1.day)

          val totalMillis = period.toDurationFrom(now).getMillis
          context.system.scheduler.scheduleOnce(
            FiniteDuration(totalMillis, MILLISECONDS),
            self, AutoCalibration(inst._id))
        }

        val instrumentParam = InstrumentParam(collector, monitorTypes, timerOpt, calibrateTimeOpt)
        if (instType.driver.isCalibrator) {
          isT700Calibrator = instType.driver.id == T700Collector.id
          calibratorOpt = Some(collector)
        } else if (instType.driver.isDoInstrument) {
          digitalOutputOpt = Some(collector)
        }

        context become handler(
          instrumentMap + (inst._id -> instrumentParam),
          collectorInstrumentMap + (collector -> inst._id),
          latestDataMap, mtDataList, restartList, signalTypeHandlerMap, signalDataMap, calibrationListMap)
      }

    case StopInstrument(id: String) =>
      for (param <- instrumentMap.get(id)) {
        Logger.info(s"Stop collecting instrument $id ")
        Logger.info(s"remove ${param.mtList}")
        for (timer <- param.calibrationTimerOpt)
          timer.cancel()

        val filteredSignalHandlerMap = signalTypeHandlerMap.map(kv => {
          val handlerMap = kv._2.filter(p => p._1 != param.actor)
          kv._1 -> handlerMap
        })
        param.actor ! PoisonPill

        if (calibratorOpt.contains(param.actor)) {
          calibratorOpt = None
        } else if (digitalOutputOpt.contains(param.actor)) {
          digitalOutputOpt = None
        }

        if (!restartList.contains(id))
          context become handler(instrumentMap - id, collectorInstrumentMap - param.actor,
            latestDataMap -- param.mtList, mtDataList, restartList,
            filteredSignalHandlerMap, signalDataMap, calibrationListMap)
        else {
          val removed = restartList.filter(_ != id)
          val f = instrumentOp.getInstrumentFuture(id)
          f.andThen({
            case Success(value) =>
              self ! StartInstrument(value)
          })
          context become handler(instrumentMap - id, collectorInstrumentMap - param.actor,
            latestDataMap -- param.mtList, mtDataList, removed,
            filteredSignalHandlerMap, signalDataMap, calibrationListMap)
        }
      }

    case RestartInstrument(id) =>
      self ! StopInstrument(id)
      context become handler(instrumentMap, collectorInstrumentMap,
        latestDataMap, mtDataList, restartList :+ id, signalTypeHandlerMap, signalDataMap, calibrationListMap)

    case RestartMyself =>
      val id = collectorInstrumentMap(sender)
      Logger.info(s"restart $id")
      self ! RestartInstrument(id)

    case reportData: ReportData =>
      val now = DateTime.now
      val dataList: List[MonitorTypeData] = reportData.dataList(monitorTypeOp)
      for (instId <- collectorInstrumentMap.get(sender)) {

        val pairs =
          for (data <- dataList) yield {
            val currentMap = latestDataMap.getOrElse(data.mt, Map.empty[String, Record])
            val filteredMap = currentMap.filter { kv =>
              val r = kv._2
              r.time >= DateTime.now() - 6.second
            }

            data.mt -> (filteredMap ++ Map(instId -> Record(now, Some(data.value), data.status, Monitor.activeId)))
          }

        context become handler(instrumentMap, collectorInstrumentMap,
          latestDataMap ++ pairs, (DateTime.now, instId, dataList) :: mtDataList, restartList,
          signalTypeHandlerMap, signalDataMap, calibrationListMap)
      }

    case CalculateData => {
      import scala.collection.mutable.ListBuffer

      val now = DateTime.now()
      //Update Calibration Map
      for (map <- calibrationDB.getCalibrationListMapFuture(now.minusDays(2), now)(monitorTypeOp)) {
        self ! UpdateCalibrationMap(map)
      }

      def flushSecData(recordMap: Map[String, Map[String, ListBuffer[(DateTime, Double)]]]) {

        if (recordMap.nonEmpty) {
          import scala.collection.mutable.Map
          val secRecordMap = Map.empty[DateTime, ListBuffer[(String, (Double, String))]]
          for {
            mt_pair <- recordMap
            mt = mt_pair._1
            statusMap = mt_pair._2
          } {
            def fillList(head: (DateTime, Double, String), tail: List[(DateTime, Double, String)]): List[(DateTime, Double, String)] = {
              val secondEnd = if (tail.isEmpty)
                60
              else
                tail.head._1.getSecondOfMinute

              val sameDataList =
                for (s <- head._1.getSecondOfMinute until secondEnd) yield {
                  val minPart = head._1.withSecond(0)
                  (minPart + s.second, head._2, head._3)
                }

              if (tail.nonEmpty)
                sameDataList.toList ++ fillList(tail.head, tail.tail)
              else
                sameDataList.toList
            }

            val mtList: Iterable[(DateTime, Double, String)] = statusMap.flatMap { status_pair =>
              val status = status_pair._1
              val recordList = status_pair._2
              val adjustedRecList = recordList map { rec => (rec._1.withMillisOfSecond(0), rec._2) }

              adjustedRecList map { record => (record._1, record._2, status) }
            }

            val mtSortedList = mtList.toList.sortBy(_._1)
            val completeList = if (mtSortedList.nonEmpty) {
              val head = mtSortedList.head
              if (head._1.getSecondOfMinute == 0)
                fillList(head, mtSortedList.tail)
              else
                fillList((head._1.withSecondOfMinute(0), head._2, head._3), mtSortedList)
            } else
              List.empty[(DateTime, Double, String)]

            for (record <- completeList) {
              val mtSecListBuffer = secRecordMap.getOrElseUpdate(record._1, ListBuffer.empty[(String, (Double, String))])
              mtSecListBuffer.append((mt, (record._2, record._3)))
            }
          }
          val docs = secRecordMap map { r => {
            val mtDataSeq = r._2.map(pair => {
              val (mt, (value, status)) = pair
              val mtCase = monitorTypeOp.map(mt)
              monitorTypeOp.getMinMtRecordByRawValue(pair._1, Some(pair._2._1), pair._2._2)(mtCase.fixedM, mtCase.fixedB)
            })

            r._1 -> RecordList(mtDataSeq, RecordListID(r._1, Monitor.activeId))
          }
          }

          val sortedDocs = docs.toSeq.sortBy { x => x._1 } map (_._2)
          if (sortedDocs.nonEmpty)
            recordOp.insertManyRecord(recordOp.SecCollection)(sortedDocs)
        }
      }

      def calculateMinData(current: DateTime, calibrationMap: CalibrationListMap): Future[UpdateResult] = {
        import scala.collection.mutable.Map
        val mtMap = Map.empty[String, Map[String, ListBuffer[(String, DateTime, Double)]]]

        val currentData = mtDataList.takeWhile(d => d._1 >= current)
        val minDataList = mtDataList.drop(currentData.length)

        for {
          dl <- minDataList
          instrumentId = dl._2
          data <- dl._3
        } {
          val statusMap = mtMap.getOrElse(data.mt, {
            val map = Map.empty[String, ListBuffer[(String, DateTime, Double)]]
            mtMap.put(data.mt, map)
            map
          })

          val lb = statusMap.getOrElseUpdate(data.status, ListBuffer.empty[(String, DateTime, Double)])
          lb.append((instrumentId, dl._1, data.value))
        }

        val priorityMtPair =
          for {
            mt_statusMap <- mtMap
            mt = mt_statusMap._1
            statusMap = mt_statusMap._2
          } yield {
            val winOutStatusPair =
              for {
                status_lb <- statusMap
                status = status_lb._1
                lb = status_lb._2
                measuringInstrumentList <- monitorTypeOp.map(mt).measuringBy
              } yield {
                val winOutInstrumentOpt = measuringInstrumentList.find { instrumentId =>
                  lb.exists { id_value =>
                    val id = id_value._1
                    instrumentId == id
                  }
                }
                val winOutLbOpt = winOutInstrumentOpt.map {
                  winOutInstrument =>
                    lb.filter(_._1 == winOutInstrument).map(r => (r._2, r._3))
                }

                status -> winOutLbOpt.getOrElse(ListBuffer.empty[(DateTime, Double)])
              }
            val winOutStatusMap = winOutStatusPair.toMap
            mt -> winOutStatusMap
          }
        val priorityMtMap = priorityMtPair.toMap

        if (LoggerConfig.config.storeSecondData)
          flushSecData(priorityMtMap)

        val minuteMtAvgList = calculateMinAvgMap(monitorTypeOp, priorityMtMap, alwaysValid = false)(calibrationMap, current.minusMinutes(1))

        checkMinDataAlarm(minuteMtAvgList)

        context become handler(instrumentMap, collectorInstrumentMap,
          latestDataMap, currentData, restartList, signalTypeHandlerMap, signalDataMap, calibrationListMap)
        val f = recordOp.upsertRecord(recordOp.MinCollection)(RecordList.factory(current.minusMinutes(1), minuteMtAvgList.toList, Monitor.activeId))
        f onComplete {
          case Success(_) =>
            self ! ForwardMin
          case Failure(exception) =>
            errorHandler(exception)
        }
        f
      }

      val current = DateTime.now().withSecondOfMinute(0).withMillisOfSecond(0)
      if (LoggerConfig.config.selfMonitor) {
        val f = calculateMinData(current, calibrationListMap)
        f onComplete {
          case Success(_) =>
            if (current.getMinuteOfHour == 0) {
              for (m <- monitorOp.mvList) {
                dataCollectManagerOp.recalculateHourData(monitor = m,
                  current = current)(monitorTypeOp.activeMtvList, monitorTypeOp)
              }
              self ! CheckInstruments
            }
          case Failure(exception) =>
            errorHandler(exception)
        }
      }
    }

    case UpdateCalibrationMap(map) =>
      context become handler(instrumentMap, collectorInstrumentMap,
        latestDataMap, mtDataList, restartList,
        signalTypeHandlerMap, signalDataMap, map)

    case SetState(instId, state) =>
      Logger.info(s"SetState($instId, $state)")
      instrumentMap.get(instId).map { param =>
        param.actor ! SetState(instId, state)
      }

    case AutoCalibration(instId) =>
      instrumentMap.get(instId).map { param =>
        param.actor ! AutoCalibration(instId)

        param.calibrationTimerOpt =
          for (localTime: LocalTime <- param.calibrationTimeOpt) yield {
            val now = DateTime.now()
            val calibrationTime = now.toLocalDate().toDateTime(localTime)

            val period = if (now < calibrationTime)
              new Period(now, calibrationTime)
            else
              new Period(now, calibrationTime + 1.day)

            val totalMilli = period.toDurationFrom(now).getMillis
            import scala.concurrent.duration._
            context.system.scheduler.scheduleOnce(
              FiniteDuration(totalMilli, MILLISECONDS),
              self, AutoCalibration(instId))
          }

        context become handler(
          instrumentMap + (instId -> param),
          collectorInstrumentMap,
          latestDataMap, mtDataList, restartList, signalTypeHandlerMap, signalDataMap, calibrationListMap)
      }

    case ManualZeroCalibration(instId) =>
      instrumentMap.get(instId).map { param =>
        param.actor ! ManualZeroCalibration(instId)
      }

    case ManualSpanCalibration(instId) =>
      instrumentMap.get(instId).map { param =>
        param.actor ! ManualSpanCalibration(instId)
      }
    case WriteTargetDO(instId, bit, on) =>
      Logger.debug(s"WriteTargetDO($instId, $bit, $on)")
      instrumentMap.get(instId).map { param =>
        param.actor ! WriteDO(bit, on)
      }

    case ToggleTargetDO(instId, bit: Int, seconds) =>
      //Cancel previous timer if any
      onceTimer map { t => t.cancel() }
      Logger.debug(s"ToggleTargetDO($instId, $bit)")
      self ! WriteTargetDO(instId, bit, true)
      onceTimer = Some(context.system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(seconds, SECONDS),
        self, WriteTargetDO(instId, bit, false)))

    case IsTargetConnected(instId) =>
      import akka.pattern.ask
      import akka.util.Timeout

      import scala.concurrent.duration._
      implicit val timeout = Timeout(Duration(3, SECONDS))
      instrumentMap.get(instId).map { param =>
        val f = param.actor ? IsTargetConnected(instId)
        for (ret <- f.mapTo[Boolean]) yield
          sender ! ret
      }
    case msg: ExecuteSeq =>
      if (calibratorOpt.nonEmpty) {
        if (msg.seqName == T700_STANDBY_SEQ) {
          if (isT700Calibrator)
            calibratorOpt.get ! msg
        } else
          calibratorOpt.get ! msg
      } else {
        Logger.warn(s"Calibrator is not online! Ignore execute (${msg.seqName} - ${msg.on}).")
      }

    case msg: WriteDO =>
      if (digitalOutputOpt.isDefined)
        digitalOutputOpt.get ! msg
      else {
        Logger.warn(s"DO is not online! Ignore output (${msg.bit} - ${msg.on}).")
      }

    case GetLatestSignal =>
      val now = DateTime.now()
      val filteredSignalMap = signalDataMap.filter(p => p._2._1.after(now - 6.seconds))
      val resultMap = filteredSignalMap map { p => p._1 -> p._2._2 }
      context become handler(instrumentMap, collectorInstrumentMap, latestDataMap,
        mtDataList, restartList, signalTypeHandlerMap, filteredSignalMap, calibrationListMap)

      sender() ! resultMap

    case GetLatestData =>
      //Filter out older than 10 second
      val latestMap = latestDataMap.flatMap { kv =>
        val mt = kv._1
        val instRecordMap = kv._2
        val timeout = if (mt == MonitorType.LAT || mt == MonitorType.LNG)
          1.minute
        else
          10.second

        val filteredRecordMap = instRecordMap.filter {
          kv =>
            val r = kv._2
            r.time >= DateTime.now() - timeout
        }

        if (monitorTypeOp.map(mt).measuringBy.isEmpty) {
          Logger.warn(s"$mt has not measuring instrument!")
          None
        } else {
          val measuringList = monitorTypeOp.map(mt).measuringBy.get
          val instrumentIdOpt = measuringList.find { instrumentId => filteredRecordMap.contains(instrumentId) }
          instrumentIdOpt map {
            mt -> filteredRecordMap(_)
          }
        }
      }
      context become handler(instrumentMap, collectorInstrumentMap, latestDataMap,
        mtDataList, restartList, signalTypeHandlerMap, signalDataMap, calibrationListMap)
      sender ! latestMap

    case AddSignalTypeHandler(mtId, signalHandler) =>
      var handlerMap = signalTypeHandlerMap.getOrElse(mtId, Map.empty[ActorRef, Boolean => Unit])
      handlerMap = handlerMap + (sender() -> signalHandler)
      context become handler(instrumentMap, collectorInstrumentMap, latestDataMap,
        mtDataList, restartList, signalTypeHandlerMap + (mtId -> handlerMap), signalDataMap, calibrationListMap)

    case WriteSignal(mtId, bit) =>
      monitorTypeOp.logDiMonitorType(alarmOp, mtId, bit)
      val handlerMap = signalTypeHandlerMap.getOrElse(mtId, Map.empty[ActorRef, Boolean => Unit])
      for (handler <- handlerMap.values)
        handler(bit)

    case ReportSignalData(dataList) =>
      dataList.foreach(signalData => monitorTypeOp.logDiMonitorType(alarmOp, signalData.mt, signalData.value))
      val updateMap: Map[String, (DateTime, Boolean)] = dataList.map(signal => {
        signal.mt -> (DateTime.now(), signal.value)
      }).toMap
      context become handler(instrumentMap, collectorInstrumentMap, latestDataMap,
        mtDataList, restartList, signalTypeHandlerMap, signalDataMap ++ updateMap, calibrationListMap)

    case CheckInstruments =>
      val now = DateTime.now()
      val f = recordOp.getMtRecordMapFuture(recordOp.MinCollection)(Monitor.activeId, monitorTypeOp.realtimeMtvList,
        now.minusHours(1), now)
      for (minRecordMap <- f) {
        for (kv <- instrumentMap) {
          val (instID, instParam) = kv;
          if (instParam.mtList.exists(mt => !minRecordMap.contains(mt) ||
            minRecordMap.contains(mt) && minRecordMap(mt).size < 45)) {
            Logger.error(s"$instID has less than 45 minRecords. Restart $instID")
            alarmOp.log(alarmOp.srcInstrumentID(instID), alarmOp.Level.ERR, s"$instID 每小時分鐘資料小於45筆. 重新啟動 $instID 設備")
            self ! RestartInstrument(instID)
          }
        }
      }

    case ReaderReset =>
      readerList.foreach(_ ! ReaderReset)

  }

  override def postStop(): Unit = {
    timer.cancel()
    autoStateTimerOpt.map(_.cancel())
    onceTimer map {
      _.cancel()
    }
  }

  case class InstrumentParam(actor: ActorRef, mtList: List[String],
                             var calibrationTimerOpt: Option[Cancellable],
                             calibrationTimeOpt: Option[LocalTime])

}