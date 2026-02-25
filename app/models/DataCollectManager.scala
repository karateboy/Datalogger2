package models

import akka.actor._
import com.github.nscala_time.time.Imports._
import models.Calibration.{CalibrationListMap, emptyCalibrationListMap, findTargetCalibrationMB}
import models.ForwardManager.{ForwardHour, ForwardHourRecord, ForwardMin, ForwardMinRecord}
import models.ModelHelper._
import models.MultiCalibrator.MultiCalibrationDone
import models.TapiTxx.T700_STANDBY_SEQ
import org.mongodb.scala.result.UpdateResult
import play.api._
import play.api.libs.concurrent.InjectedActorSupport

import javax.inject._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS, SECONDS}
import scala.language.postfixOps
import scala.math.BigDecimal.RoundingMode
import scala.util.{Failure, Success}

object DataCollectManager {
  var effectiveRatio = 0.75

  case class InstrumentParam(actor: ActorRef, mtList: List[String],
                             var calibrationTimerOpt: Option[Cancellable],
                             calibrationTimeOpt: Option[LocalTime])

  case object ReaderReset

  case class WriteTargetDO(instId: String, bit: Int, on: Boolean)

  private case class ToggleTargetDO(instId: String, bit: Int, seconds: Int)

  case class WriteDO(bit: Int, on: Boolean)

  case object GetLatestData

  case object GetLatestSignal

  private case class IsTargetConnected(instId: String)

  case class AddSignalTypeHandler(mtId: String, handler: Boolean => Unit)

  case class WriteSignal(mtId: String, bit: Boolean)

  private case object CheckInstruments

  private case class UpdateCalibrationMap(map: CalibrationListMap)

  case class StartInstrument(inst: Instrument)

  case class StopInstrument(id: String)

  case class StartMultiCalibration(calibrationConfig: CalibrationConfig)

  case class UpdateMultiCalibratorState(calibrationConfig: CalibrationConfig, state: String)

  case class StopMultiCalibration(calibrationConfig: CalibrationConfig)

  case class SetupMultiCalibrationTimer(calibrationConfig: CalibrationConfig)

  case class RemoveMultiCalibrationTimer(_id: String)

  private case class RestartInstrument(id: String)

  case object RestartMyself

  case class SetState(instId: String, state: String)

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

  private case object CalculateData

  case class AutoCalibration(instId: String)

  case class ManualZeroCalibration(instId: String)

  case class ManualSpanCalibration(instId: String)

  case class CalibrationType(auto: Boolean, zero: Boolean)

  object AutoZero extends CalibrationType(true, true)

  object AutoSpan extends CalibrationType(true, false)

  object ManualZero extends CalibrationType(false, true)

  object ManualSpan extends CalibrationType(false, false)

  private case object ReloadAlarmRule

  private case object UpdateHourAccumulatedRain

  case object CheckStatus

  private def updateEffectiveRatio(sysConfig: SysConfigDB): Unit = {
    for (ratio <- sysConfig.getEffectiveRatio)
      effectiveRatio = ratio
  }

  private def calculateMinAvgMap(mtList: Seq[String],
                                 mtStatusMap: mutable.Map[String, mutable.Map[String, ListBuffer[(DateTime, Double)]]],
                                 monitorTypeDB: MonitorTypeDB,
                                 monitorStatusDB: MonitorStatusDB)
                                (calibrationMap: CalibrationListMap, target: DateTime) = {
    //For data loss mt, fill in data loss record
    mtList.foreach(mt => {
      val statusMap = mtStatusMap.getOrElseUpdate(mt, mutable.Map.empty[String, ListBuffer[(DateTime, Double)]])
      if (statusMap.isEmpty)
        statusMap.update(MonitorStatus.DataLost, ListBuffer.empty)
    })

    // Generate MtRecord by status priority
    for (mt <- mtList if !MonitorType.IsCalculated(mt)) yield {
      val statusMap = mtStatusMap(mt)
      val status = statusMap.keys.toList.maxBy(status => monitorStatusDB._map(status).priority)

      val minuteRawAvg: Option[Double] = {
        val values = statusMap(status).map(_._2)
        val avgOpt = if (values.isEmpty)
          None
        else {
          val mtCase = monitorTypeDB.map(mt)
          mt match {
            case MonitorType.WIN_DIRECTION =>
              val windDir = values
              if (mtStatusMap.contains(MonitorType.WIN_SPEED)) {
                val speedStatusMap = mtStatusMap(MonitorType.WIN_SPEED)
                val speedMostStatus = speedStatusMap.maxBy(kv => kv._2.length)
                val speeds = speedMostStatus._2.map(_._2)
                directionAvg(speeds.toList, windDir.toList)
              } else { //assume wind speed is all equal
                val speeds = List.fill(windDir.length)(1.0)
                directionAvg(speeds, windDir.toList)
              }
            case MonitorType.WIN_SPEED =>
              if (mtStatusMap.contains(MonitorType.WIN_DIRECTION)) {
                val dirStatusMap = mtStatusMap(MonitorType.WIN_DIRECTION)
                val dirMostStatus = dirStatusMap.maxBy(kv => kv._2.length)
                val dirs = dirMostStatus._2.map(_._2)
                speedAvg(values.toList, dirs.toList)
              } else {
                val v = values.sum / values.length
                if (v.isNaN)
                  None
                else
                  Some(values.sum / values.length)
              }

            case MonitorType.DIRECTION =>
              val directions = values
              if (mtStatusMap.contains(MonitorType.SPEED)) {
                val speedMostStatus = mtStatusMap(MonitorType.SPEED).maxBy(kv => kv._2.length)
                val speeds = speedMostStatus._2.map(_._2)
                directionAvg(speeds.toList, directions.toList)
              } else { //assume wind speed is all equal
                val speeds = List.fill(directions.length)(1.0)
                directionAvg(speeds, directions.toList)
              }
            case MonitorType.RAIN =>
              if (mtCase.accumulated.contains(true)) {
                if (values.length < 2)
                  None
                else {
                  val diff = values.last - values.head
                  if (diff < 0)
                    None
                  else
                    Some(diff)
                }
              } else
                Some(values.sum)

            case MonitorType.PM10 =>
              if (LoggerConfig.config.pm25HourAvgUseLastRecord)
                Some(values.last)
              else
                Some(values.sum / values.length)

            case MonitorType.PM25 =>
              if (LoggerConfig.config.pm25HourAvgUseLastRecord)
                Some(values.last)
              else
                Some(values.sum / values.length)

            case _ =>
              if (mtCase.acoustic.contains(true)) {
                val noNanValues = values.filter(v => !v.isNaN)
                if (noNanValues.isEmpty)
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
          for (avg <- avgOpt) yield
            BigDecimal(avg).setScale(monitorTypeDB.map(mt).prec, RoundingMode.HALF_UP).doubleValue()
        } catch {
          case _: Throwable =>
            None
        }
      }

      val (mOpt, bOpt) = findTargetCalibrationMB(calibrationMap, mt, target).getOrElse((None, None))
      monitorTypeDB.getMinMtRecordByRawValue(mt, minuteRawAvg, status)(mOpt, bOpt)
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
          None
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
                  for (_ <- 1 to windDir.length)
                    yield 1.0

                directionAvg(windSpeed, values)
              }
            case MonitorType.WD10 =>
              val windDir = values.take(10)
              if (mtMap.contains(MonitorType.WIN_SPEED)) {
                val windSpeedMostStatus = mtMap(MonitorType.WIN_SPEED).maxBy(kv => kv._2.length)
                val windSpeed = windSpeedMostStatus._2.take(10)
                if (isRaw)
                  directionAvg(windSpeed.flatMap(_.rawValue), values)
                else
                  directionAvg(windSpeed.flatMap(_.value), values)
              } else { //assume wind speed is all equal
                val windSpeed =
                  for (_ <- 1 to windDir.length)
                    yield 1.0

                directionAvg(windSpeed, values)
              }
            case MonitorType.WIN_SPEED =>
              if (mtMap.contains(MonitorType.WIN_DIRECTION)) {
                val dirStatusMap = mtMap(MonitorType.WIN_DIRECTION)
                val dirMostStatus = dirStatusMap.maxBy(kv => kv._2.length)
                val dirs = dirMostStatus._2
                if (isRaw)
                  speedAvg(values.toList, dirs.flatMap(_.rawValue).toList)
                else
                  speedAvg(values.toList, dirs.flatMap(_.value).toList)
              } else {
                val v = values.sum / values.length
                if (v.isNaN)
                  None
                else
                  Some(values.sum / values.length)
              }
            case MonitorType.WS10 =>
              val v10 = values.take(10)
              val v = v10.sum / v10.length
              if (v.isNaN)
                None
              else
                Some(v10.sum / v10.length)

            case MonitorType.RAIN =>
              Some(values.sum)

            case MonitorType.PM10 =>
              if (LoggerConfig.config.pm25HourAvgUseLastRecord)
                Some(values.last)
              else
                Some(values.sum / values.length)

            case MonitorType.PM25 =>
              if (LoggerConfig.config.pm25HourAvgUseLastRecord)
                Some(values.last)
              else
                Some(values.sum / values.length)

            case _ =>
              if (mtCase.acoustic.contains(true)) {
                val noNanValues = values.filter(v => !v.isNaN)
                if (noNanValues.isEmpty)
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
          BigDecimal(avg).setScale(monitorTypeDB.map(mt).prec, RoundingMode.HALF_UP).doubleValue()

      val roundedRawAvg: Option[Double] =
        for (avg <- hourAccumulator(mtRecords.flatMap(_.rawValue), isRaw = true)) yield
          BigDecimal(avg).setScale(monitorTypeDB.map(mt).prec, RoundingMode.HALF_UP).doubleValue()

      MtRecord(mt, roundedAvg, status, rawValue = roundedRawAvg)
    }
  }

  private var isT700Calibrator = true
  private var calibratorOpt: Option[ActorRef] = None
  private var digitalOutputOpt: Option[ActorRef] = None
  private var onceTimer: Option[Cancellable] = None
  private var hourAccumulateRain: Option[Double] = None
}

@Singleton
class DataCollectManager @Inject()(config: Configuration,
                                   environment: Environment,
                                   recordOp: RecordDB,
                                   monitorTypeOp: MonitorTypeDB,
                                   monitorOp: MonitorDB,
                                   monitorStatusDB: MonitorStatusDB,
                                   dataCollectManagerOp: DataCollectManagerOp,
                                   instrumentTypeOp: InstrumentTypeOp,
                                   alarmOp: AlarmDB,
                                   instrumentOp: InstrumentDB,
                                   calibrationDB: CalibrationDB,
                                   sysConfig: SysConfigDB,
                                   calibrationConfigDB: CalibrationConfigDB,
                                   forwardManagerFactory: ForwardManager.Factory,
                                   alarmRuleDb: AlarmRuleDb,
                                   tableType: TableType) extends Actor with InjectedActorSupport {

  import DataCollectManager._
  val logger = Logger(this.getClass)

  logger.info(s"store second data = ${LoggerConfig.config.storeSecondData}")
  DataCollectManager.updateEffectiveRatio(sysConfig)
  HourCalculationRule.init(sysConfig)

  for (aqiMonitorTypes <- sysConfig.getAqiMonitorTypes)
    AQI.updateAqiTypeMapping(aqiMonitorTypes)

  val timer: Cancellable = {
    import scala.concurrent.duration._
    //Try to trigger at 30 sec
    val next30 = DateTime.now().withSecondOfMinute(30).plusMinutes(1)
    val postSeconds = new org.joda.time.Duration(DateTime.now, next30).getStandardSeconds
    context.system.scheduler.scheduleAtFixedRate(FiniteDuration(postSeconds, SECONDS), Duration(1, MINUTES), self, CalculateData)
  }

  private val autoStateConfigOpt: Option[Seq[AutoStateConfig]] = AutoState.getConfig(config)
  private val autoStateTimerOpt: Option[Cancellable] =
    if (autoStateConfigOpt.nonEmpty) {
      import scala.concurrent.duration._
      //Try to trigger at 30 sec
      val next = DateTime.now().withSecondOfMinute(0).plusMinutes(1)
      val postSeconds = new org.joda.time.Duration(DateTime.now, next).getStandardSeconds
      Some(context.system.scheduler.scheduleAtFixedRate(FiniteDuration(postSeconds, SECONDS), Duration(1, MINUTES), self, AutoState))
    } else
      None

  private val readerList: List[ActorRef] = startReaders()
  private val forwardManagerOpt: Option[ActorRef] =
    for (serverConfig <- ForwardManager.getConfig(config)) yield
      injectedChild(forwardManagerFactory(serverConfig.server, serverConfig.monitor), "forwardManager")

  private def startReaders(): List[ActorRef] = {
    val readers: ListBuffer[ActorRef] = ListBuffer.empty[ActorRef]

    for (readerRef <- SpectrumReader.start(config, context.system, sysConfig, monitorTypeOp, recordOp, dataCollectManagerOp))
      readers.append(readerRef)

    for (readerRef <- WeatherReader.start(config, context.system, sysConfig, monitorTypeOp, recordOp, dataCollectManagerOp))
      readers.append(readerRef)

    for (readerRef <- VocReader.start(config, context.system, monitorOp, monitorTypeOp, recordOp, self))
      readers.append(readerRef)

    for(readerRef <- ImsReader.start(config, context.system, monitorTypeOp = monitorTypeOp, recordOp = recordOp,
      dataCollectManager = self, dataCollectManagerOp = dataCollectManagerOp, environment = environment))
      readers.append(readerRef)

    readers.toList
  }


  // Start all active instruments
  val instrumentList: Seq[Instrument] = instrumentOp.getInstrumentList()
  instrumentList.foreach {
    inst =>
      if (inst.active)
        self ! StartInstrument(inst)
  }

  // Setup all multi-calibrations timer
  for (multiCalibrationList <- calibrationConfigDB.getListFuture) {
    multiCalibrationList.foreach { calibrationConfig =>
      self ! SetupMultiCalibrationTimer(calibrationConfig)
    }
  }

  // Reload Alarm Rule
  self ! ReloadAlarmRule

  self ! UpdateHourAccumulatedRain

  logger.info("DataCollect manager started")

  private def checkMinDataAlarm(minMtAvgList: Iterable[MtRecord]): Boolean = {
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
            alarmOp.log(alarmOp.src(mt), Alarm.Level.INFO, msg)
            overThreshold = true
            mtCase.overLawSignalType.foreach(signalType => {
              self ! WriteSignal(signalType, bit = true)
            })
          }
        }
    }
    overThreshold
  }

  def receive: Receive = handler(Map.empty[String, InstrumentParam],
    Map.empty[String, Map[String, Record]],
    List.empty[(DateTime, String, List[MonitorTypeData])],
    List.empty[String],
    Map.empty[String, Map[ActorRef, Boolean => Unit]],
    Map.empty[String, (DateTime, Boolean)],
    emptyCalibrationListMap,
    Map.empty[String, CalibratorState],
    Map.empty[String, Cancellable],
    Seq.empty[AlarmRule])

  private def getCollectorMap(instrumentMap: Map[String, InstrumentParam]): Map[ActorRef, String] =
    instrumentMap.map(kv => kv._2.actor -> kv._1)

  private def getCalibrationTimer(config: CalibrationConfig): Option[Cancellable] = {
    for (localTimeStr <- config.calibrationTime) yield {
      val localTime = java.time.LocalTime.parse(localTimeStr)
      val now = java.time.LocalDateTime.now()
      val calibrationTime = java.time.LocalDateTime.of(java.time.LocalDate.now(), localTime)
      val duration =
        if (now.isBefore(calibrationTime) && now.until(calibrationTime, java.time.temporal.ChronoUnit.MINUTES) >= 1)
          now.until(calibrationTime, java.time.temporal.ChronoUnit.MILLIS)
        else
          now.until(calibrationTime.plusDays(1), java.time.temporal.ChronoUnit.MILLIS)

      context.system.scheduler.scheduleOnce(
        FiniteDuration(duration, MILLISECONDS),
        self, StartMultiCalibration(config))
    }
  }

  case class CalibratorState(calibrator: ActorRef, state: String)

  def handler(instrumentMap: Map[String, InstrumentParam],
              latestDataMap: Map[String, Map[String, Record]],
              mtDataList: List[(DateTime, String, List[MonitorTypeData])],
              restartList: Seq[String],
              signalTypeHandlerMap: Map[String, Map[ActorRef, Boolean => Unit]],
              signalDataMap: Map[String, (DateTime, Boolean)],
              calibrationListMap: CalibrationListMap,
              instrumentCalibratorMap: Map[String, CalibratorState],
              calibratorTimerMap: Map[String, Cancellable],
              alarmRules: Seq[AlarmRule]): Receive = {
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

    case ReloadAlarmRule =>
      val f = alarmRuleDb.getRulesAsync
      f.onComplete {
        case Success(rules) =>
          context become handler(instrumentMap, latestDataMap, mtDataList, restartList,
            signalTypeHandlerMap, signalDataMap, calibrationListMap, instrumentCalibratorMap, calibratorTimerMap, rules)

        case Failure(ex) =>
          logger.error("Failed to reload alarm rules", ex)
      }

    case AutoState =>
      for (autoStateConfigs <- autoStateConfigOpt)
        autoStateConfigs.foreach(config => {
          if (config.period == "Hour" && config.time.toInt == DateTime.now().getMinuteOfHour) {
            logger.info(s"AutoState=>$config")
            self ! SetState(config.instID, config.state)
          }
        })

    case StartInstrument(inst) =>
      if (!instrumentTypeOp.map.contains(inst.instType))
        logger.error(s"${inst._id} of ${inst.instType} is unknown!")
      else if (instrumentMap.contains(inst._id))
        logger.error(s"${inst._id} is already started!")
      else {
        val instType = instrumentTypeOp.map(inst.instType)
        val collector = instrumentTypeOp.start(inst.instType, inst._id, inst.protocol, inst.param)
        val monitorTypes = instType.driver.getMonitorTypes(inst.param)
        for (mt <- MonitorType.populateCalculatedTypes(monitorTypes)) yield
          monitorTypeOp.addMeasuring(mt, inst._id, instType.analog, recordOp)

        val calibrateTimeOpt = instType.driver.getCalibrationTime(inst.param)
        val timerOpt = calibrateTimeOpt.map { localtime =>
          val now = DateTime.now()
          val calibrationTime = now.toLocalDate.toDateTime(localtime)
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
          latestDataMap, mtDataList, restartList, signalTypeHandlerMap, signalDataMap,
          calibrationListMap, instrumentCalibratorMap, calibratorTimerMap, alarmRules)
      }

    case StopInstrument(id: String) =>
      for (param <- instrumentMap.get(id)) {
        logger.info(s"Stop collecting instrument $id ")
        logger.info(s"remove ${param.mtList}")
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
          context become handler(instrumentMap - id,
            latestDataMap -- param.mtList, mtDataList, restartList,
            filteredSignalHandlerMap, signalDataMap, calibrationListMap,
            instrumentCalibratorMap, calibratorTimerMap, alarmRules)
        else {
          val removed = restartList.filter(_ != id)
          val f = instrumentOp.getInstrumentFuture(id)
          f.andThen({
            case Success(value) =>
              self ! StartInstrument(value)
          })
          context become handler(instrumentMap - id,
            latestDataMap -- param.mtList, mtDataList, removed,
            filteredSignalHandlerMap, signalDataMap, calibrationListMap,
            instrumentCalibratorMap, calibratorTimerMap, alarmRules)
        }
      }

    case SetupMultiCalibrationTimer(config) =>
      for (calibrationTimer <- getCalibrationTimer(config)) {
        context become handler(instrumentMap,
          latestDataMap, mtDataList, restartList,
          signalTypeHandlerMap, signalDataMap,
          calibrationListMap, instrumentCalibratorMap,
          calibratorTimerMap + (config._id -> calibrationTimer), alarmRules)
      }

    case RemoveMultiCalibrationTimer(_id) =>
      for (timer <- calibratorTimerMap.get(_id)) {
        timer.cancel()
      }

      context become handler(instrumentMap,
        latestDataMap, mtDataList, restartList,
        signalTypeHandlerMap, signalDataMap,
        calibrationListMap, instrumentCalibratorMap, calibratorTimerMap - _id, alarmRules)

    case StartMultiCalibration(calibrationConfig) =>
      val calibrator = MultiCalibrator.start(calibrationConfig,
        instrumentMap)(context, calibrationDB, monitorTypeOp, alarmOp)

      var newCalibratorMap = instrumentCalibratorMap
      for (instId <- calibrationConfig.instrumentIds) {
        newCalibratorMap += instId -> CalibratorState(calibrator, MonitorStatus.ZeroCalibrationStat)
      }
      // Always cancel the timer first
      for (timer <- calibratorTimerMap.get(calibrationConfig._id))
        timer.cancel()

      var newCalibratorTimerMap = calibratorTimerMap
      for (timer <- getCalibrationTimer(calibrationConfig))
        newCalibratorTimerMap += calibrationConfig._id -> timer

      context become handler(instrumentMap,
        latestDataMap, mtDataList, restartList,
        signalTypeHandlerMap, signalDataMap,
        calibrationListMap, newCalibratorMap, newCalibratorTimerMap, alarmRules)

    case UpdateMultiCalibratorState(calibrationConfig: CalibrationConfig, state: String) =>
      var newCalibratorMap = instrumentCalibratorMap
      for (instId <- calibrationConfig.instrumentIds)
        newCalibratorMap += instId -> CalibratorState(instrumentCalibratorMap(instId).calibrator, state)

      context become handler(instrumentMap,
        latestDataMap, mtDataList, restartList,
        signalTypeHandlerMap, signalDataMap,
        calibrationListMap, newCalibratorMap, calibratorTimerMap, alarmRules)

    case StopMultiCalibration(config) =>
      for (calibratorState <- instrumentCalibratorMap.get(config.instrumentIds.head))
        calibratorState.calibrator ! StopMultiCalibration(config)

      context become handler(instrumentMap,
        latestDataMap, mtDataList, restartList,
        signalTypeHandlerMap, signalDataMap,
        calibrationListMap, instrumentCalibratorMap -- config.instrumentIds, calibratorTimerMap, alarmRules)

    case MultiCalibrationDone(config) =>
      logger.info(s"MultiCalibrationDone ${config._id}")
      context become handler(instrumentMap,
        latestDataMap, mtDataList, restartList,
        signalTypeHandlerMap, signalDataMap,
        calibrationListMap, instrumentCalibratorMap -- config.instrumentIds, calibratorTimerMap, alarmRules)
      sender ! PoisonPill

    case RestartInstrument(id) =>
      self ! StopInstrument(id)
      context become handler(instrumentMap,
        latestDataMap, mtDataList, restartList :+ id, signalTypeHandlerMap, signalDataMap,
        calibrationListMap, instrumentCalibratorMap, calibratorTimerMap, alarmRules)

    case RestartMyself =>
      for (id <- getCollectorMap(instrumentMap).get(sender)) {
        logger.info(s"restart $id")
        self ! RestartInstrument(id)
      }

    case reportData: ReportData =>
      val now = DateTime.now
      for (instId <- getCollectorMap(instrumentMap).get(sender)) {
        val dataList: List[MonitorTypeData] =
          if (instrumentCalibratorMap.contains(instId)) {
            // Report to calibrator
            val calibratorState = instrumentCalibratorMap(instId)
            calibratorState.calibrator ! reportData
            reportData.dataList(monitorTypeOp).map(_.copy(status = calibratorState.state))
          } else
            reportData.dataList(monitorTypeOp)

        // Check for monitor type range
        val rangeCheckedDataList =
          dataList map {
            mtd =>
              if (mtd.status == MonitorStatus.NormalStat) {
                val mtCase = monitorTypeOp.map(mtd.mt)
                if (mtd.value < mtCase.more.getOrElse(MonitorTypeMore()).rangeMin.getOrElse(Double.MinValue))
                  mtd.copy(status = MonitorStatus.BelowNormalStat)
                else if (mtd.value > mtCase.more.getOrElse(MonitorTypeMore()).rangeMax.getOrElse(Double.MaxValue))
                  mtd.copy(status = MonitorStatus.OverNormalStat)
                else
                  mtd
              } else
                mtd
          }

        val fullDataList = rangeCheckedDataList ++ MonitorType.getCalculatedMonitorTypeData(dataList, now)

        // Update monitor location
        for {lat <- fullDataList.find(_.mt == MonitorType.LAT)
             lng <- fullDataList.find(_.mt == MonitorType.LNG)
             activeMonitor <- monitorOp.map.get(Monitor.activeId)}
          monitorOp.upsertMonitor(activeMonitor.copy(lat = Some(lat.value), lng = Some(lng.value)))

        val pairs =
          for (data <- fullDataList) yield {
            val currentMap = latestDataMap.getOrElse(data.mt, Map.empty[String, Record])
            val filteredMap = currentMap.filter { kv =>
              val r = kv._2
              r.time >= DateTime.now() - 6.second
            }

            data.mt -> (filteredMap ++ Map(instId -> Record(now, Some(data.value), data.status, Monitor.activeId)))
          }

        context become handler(instrumentMap,
          latestDataMap ++ pairs, (DateTime.now, instId, fullDataList) :: mtDataList, restartList,
          signalTypeHandlerMap, signalDataMap, calibrationListMap,
          instrumentCalibratorMap, calibratorTimerMap, alarmRules)
      }

    case CalculateData =>
      import scala.collection.mutable.ListBuffer
      self ! ReloadAlarmRule

      val now = DateTime.now()
      //Update Calibration Map
      for (map <- calibrationDB.getCalibrationListMapFuture(now.minusDays(2), now)(monitorTypeOp)) {
        self ! UpdateCalibrationMap(map)
      }

      def flushSecData(recordMap: mutable.Map[String, mutable.Map[String, ListBuffer[(DateTime, Double)]]]): Unit = {
        if (recordMap.nonEmpty) {
          val secRecordMap = mutable.Map.empty[DateTime, ListBuffer[(String, (Double, String))]]
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
              val (mt, (_, _)) = pair
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

      def updateMinData(current: DateTime, calibrationMap: CalibrationListMap): Future[UpdateResult] = {
        val (nextMinData, thisMinData) = mtDataList.span(mtData => mtData._1 >= current)

        val mtStatusMap = mutable.Map.empty[String, mutable.Map[String, ListBuffer[(String, DateTime, Double)]]]
        for {
          dl <- thisMinData
          instrumentId = dl._2
          data <- dl._3
        } {
          val statusMap = mtStatusMap.getOrElse(data.mt, {
            val map = mutable.Map.empty[String, ListBuffer[(String, DateTime, Double)]]
            mtStatusMap.put(data.mt, map)
            map
          })

          val lb = statusMap.getOrElseUpdate(data.status, ListBuffer.empty[(String, DateTime, Double)])
          lb.prepend((instrumentId, dl._1, data.value))
        }

        def filterInstrumentData(statusInstMap: mutable.Map[String, ListBuffer[(String, DateTime, Double)]],
                                 instrumentList: Seq[String]): mutable.Map[String, ListBuffer[(DateTime, Double)]] = {
          statusInstMap flatMap {
            pair => {
              val (status, measuringData) = pair
              val instDataList = instrumentList map { id => measuringData.filter(data => data._1 == id) }
              val nonEmptyInstData = instDataList.filter(_.nonEmpty)
              if (nonEmptyInstData.nonEmpty) {
                val targetData = nonEmptyInstData.head map { data => (data._2, data._3) }
                Some(status -> targetData)
              } else
                None
            }
          }
        }

        // Filter out redundant mt dat
        val msStatusMapNoRedundant = mtStatusMap map (
          pair => {
            val (mt, statusInstMap) = pair
            mt -> filterInstrumentData(statusInstMap, monitorTypeOp.map(mt).measuringBy.getOrElse(Seq.empty[String]))
          })

        if (LoggerConfig.config.storeSecondData)
          flushSecData(msStatusMapNoRedundant)

        val rawMtRecords = calculateMinAvgMap(
          monitorTypeOp.nonCalculatedMeasuringList,
          msStatusMapNoRedundant,
          monitorTypeOp,
          monitorStatusDB)(calibrationMap, current.minusMinutes(1)).toList

        val calculatedMtRecords = MonitorType.getCalculatedMtRecord(rawMtRecords, current)
        val mtRecords = rawMtRecords ++ calculatedMtRecords

        checkMinDataAlarm(mtRecords)

        // Update hour accumulated Rain
        mtRecords.foreach(mtRecord => {
          if (mtRecord.mtName == MonitorType.RAIN)
            hourAccumulateRain = Some(hourAccumulateRain.getOrElse(0d) + mtRecord.value.getOrElse(0d))
        })

        context become handler(instrumentMap,
          latestDataMap, nextMinData, restartList, signalTypeHandlerMap, signalDataMap, calibrationListMap,
          instrumentCalibratorMap, calibratorTimerMap, alarmRules)

        val recordList = RecordList.factory(current.minusMinutes(1), mtRecords, Monitor.activeId)

        // Alarm check
        val alarms = alarmRuleDb.checkAlarm(tableType.min, recordList, alarmRules)(monitorOp, monitorTypeOp, alarmOp)
        alarms.foreach(ar => alarmOp.log(ar.src, ar.level, ar.desc, 0))

        val f = recordOp.upsertRecord(recordOp.MinCollection)(recordList)
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
        val f = updateMinData(current, calibrationListMap)
        f onComplete {
          case Success(_) =>
            if (current.getMinuteOfHour == 0) {
              // reset hourAccumulatedRain to zero
              hourAccumulateRain = Some(0)

              for (m <- monitorOp.mvList)
                dataCollectManagerOp.recalculateHourData(monitor = m, current = current)

              self ! CheckInstruments
            }
          case Failure(exception) =>
            errorHandler(exception)
        }
      }

    case UpdateCalibrationMap(map) =>
      context become handler(instrumentMap,
        latestDataMap, mtDataList, restartList,
        signalTypeHandlerMap, signalDataMap, map,
        instrumentCalibratorMap, calibratorTimerMap, alarmRules)

    case SetState(instId, state) =>
      logger.info(s"SetState($instId, $state)")
      instrumentMap.get(instId).map { param =>
        param.actor ! SetState(instId, state)
      }

    case AutoCalibration(instId) =>
      instrumentMap.get(instId).map { param =>
        param.actor ! AutoCalibration(instId)

        param.calibrationTimerOpt =
          for (localTime: LocalTime <- param.calibrationTimeOpt) yield {
            val now = DateTime.now()
            val calibrationTime = now.toLocalDate.toDateTime(localTime)

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
          latestDataMap, mtDataList, restartList, signalTypeHandlerMap, signalDataMap,
          calibrationListMap, instrumentCalibratorMap, calibratorTimerMap, alarmRules)
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
      logger.debug(s"WriteTargetDO($instId, $bit, $on)")
      instrumentMap.get(instId).map { param =>
        param.actor ! WriteDO(bit, on)
      }

    case ToggleTargetDO(instId, bit: Int, seconds) =>
      //Cancel previous timer if any
      onceTimer map { t => t.cancel() }
      logger.debug(s"ToggleTargetDO($instId, $bit)")
      self ! WriteTargetDO(instId, bit, on = true)
      onceTimer = Some(context.system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(seconds, SECONDS),
        self, WriteTargetDO(instId, bit, on = false)))

    case IsTargetConnected(instId) =>
      import akka.pattern.ask
      import akka.util.Timeout

      import scala.concurrent.duration._
      implicit val timeout: Timeout = Timeout(Duration(3, SECONDS))
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
        logger.warn(s"Calibrator is not online! Ignore execute (${msg.seqName} - ${msg.on}).")
      }

    case msg: WriteDO =>
      if (digitalOutputOpt.isDefined)
        digitalOutputOpt.get ! msg
      else {
        logger.warn(s"DO is not online! Ignore output (${msg.bit} - ${msg.on}).")
      }

    case GetLatestSignal =>
      val now = DateTime.now()
      val filteredSignalMap = signalDataMap.filter(p => p._2._1.after(now - 6.seconds))
      val resultMap = filteredSignalMap map { p => p._1 -> p._2._2 }
      context become handler(instrumentMap, latestDataMap,
        mtDataList, restartList, signalTypeHandlerMap, filteredSignalMap,
        calibrationListMap, instrumentCalibratorMap, calibratorTimerMap, alarmRules)

      sender() ! resultMap

    case GetLatestData =>
      val latestMtRecordMap = mutable.Map.empty[String, Record]
      //Filter out older than 10 second
      latestDataMap.foreach({ kv =>
        val (mt, instRecordMap) = kv
        val timeout = if (mt == MonitorType.LAT || mt == MonitorType.LNG)
          1.minute
        else
          10.second

        val filteredRecordMap = instRecordMap.filter {
          kv =>
            val r = kv._2
            r.time >= DateTime.now() - timeout
        }

        if (monitorTypeOp.map(mt).measuringBy.isEmpty)
          logger.warn(s"$mt has not measuring instrument!")

        for {measuringList <- monitorTypeOp.map(mt).measuringBy
             records = measuringList flatMap { instID => filteredRecordMap.get(instID) } if records.nonEmpty
             } {
          latestMtRecordMap.update(mt, records.head)
        }
      })

      for {mt <- monitorTypeOp.measuringList if !latestMtRecordMap.contains(mt)
           measuringList <- monitorTypeOp.map(mt).measuringBy
           } {

        // Add Not Activated Record if applied
        if (measuringList.forall(inst => !instrumentMap.contains(inst)))
          latestMtRecordMap.update(mt, Record(DateTime.now, None, MonitorStatus.NotActivated, Monitor.activeId))

        // Add Data lost status
        if (!latestMtRecordMap.contains(mt))
          latestMtRecordMap.update(mt, Record(DateTime.now, None, MonitorStatus.DataLost, Monitor.activeId))
      }

      if (latestMtRecordMap.contains(MonitorType.RAIN)) {
        // Substitute RAIN raw record with hourAccumulated record
        val rawRecord = latestMtRecordMap(MonitorType.RAIN)
        MonitorType.RAIN -> rawRecord.copy(value = hourAccumulateRain)
        latestMtRecordMap.update(MonitorType.RAIN, rawRecord.copy(value = hourAccumulateRain))
      }

      sender ! latestMtRecordMap

    case AddSignalTypeHandler(mtId, signalHandler) =>
      var handlerMap = signalTypeHandlerMap.getOrElse(mtId, Map.empty[ActorRef, Boolean => Unit])
      handlerMap = handlerMap + (sender() -> signalHandler)
      context become handler(instrumentMap, latestDataMap,
        mtDataList, restartList, signalTypeHandlerMap + (mtId -> handlerMap), signalDataMap,
        calibrationListMap, instrumentCalibratorMap, calibratorTimerMap, alarmRules)

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
      context become handler(instrumentMap, latestDataMap,
        mtDataList, restartList, signalTypeHandlerMap, signalDataMap ++ updateMap,
        calibrationListMap, instrumentCalibratorMap, calibratorTimerMap, alarmRules)

    case CheckInstruments =>
      val now = DateTime.now()
      val f = recordOp.getMtRecordMapFuture(recordOp.MinCollection)(Monitor.activeId, monitorTypeOp.measuringList,
        now.minusHours(1), now)
      for (minRecordMap <- f) {
        for (kv <- instrumentMap) {
          val (instID, instParam) = kv;
          if (instParam.mtList.filter(mt => !monitorTypeOp.map(mt).signalType)
            .exists(mt => !minRecordMap.contains(mt) ||
              minRecordMap.contains(mt) && minRecordMap(mt).size < 45)) {
            logger.error(s"$instID has less than 45 minRecords. Restart $instID")
            alarmOp.log(alarmOp.srcInstrumentID(instID), Alarm.Level.ERR, s"$instID 每小時分鐘資料小於45筆. 重新啟動 $instID 設備")
            self ! RestartInstrument(instID)
          }
        }
      }

    case ReaderReset =>
      readerList.foreach(_ ! ReaderReset)

    case UpdateHourAccumulatedRain =>
      val now = DateTime.now
      val startOfTheHour = now.withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0)
      for (recordMap <- recordOp.getMtRecordMapFuture(recordOp.MinCollection)(Monitor.activeId, Seq(MonitorType.RAIN), startOfTheHour, now)) {
        val records = recordMap.getOrElse(MonitorType.RAIN, ListBuffer.empty[MtRecord])
        hourAccumulateRain = Some(records.foldLeft(0d)((acc, record) => acc + record.value.getOrElse(0.0)))
      }
  }

  override def postStop(): Unit = {
    timer.cancel()
    autoStateTimerOpt.foreach(_.cancel())
    onceTimer.foreach(_.cancel())
  }

}