package models

import akka.actor._
import com.github.nscala_time.time.Imports
import models.ModelHelper._
import models.MultiCalibrator.TriggerVault
import models.Protocol.ProtocolParam
import models.TapiTxx.T700_STANDBY_SEQ
import play.api.Logger

import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.util.{Failure, Success}

case class ModelRegValue2(inputRegs: List[(InstrumentStatusType, Double)],
                          modeRegs: List[(InstrumentStatusType, Boolean)],
                          warnRegs: List[(InstrumentStatusType, Boolean)])

object AbstractCollector {
  case object ResetConnection
}

abstract class AbstractCollector(instrumentOp: InstrumentDB,
                                 monitorStatusOp: MonitorStatusDB,
                                 alarmOp: AlarmDB,
                                 monitorTypeDB: MonitorTypeDB,
                                 calibrationOp: CalibrationDB,
                                 instrumentStatusOp: InstrumentStatusDB)
                                (instId: String,
                                 desc: String,
                                 deviceConfig: DeviceConfig,
                                 protocol: ProtocolParam) extends Actor {

  import AbstractCollector._
  import DataCollectManager._
  import TapiTxxCollector._
  import context.dispatcher
  val logger: Logger

  self ! ConnectHost
  @volatile private var readRegTimer: Option[Cancellable] = None
  @volatile var (collectorState: String, instrumentStatusTypesOpt) = {
    val instList = instrumentOp.getInstrument(instId)
    if (instList.nonEmpty) {
      val inst: Instrument = instList.head
      (inst.state, inst.statusType)
    } else
      (MonitorStatus.NormalStat, None)
  }
  @volatile var connected = false
  @volatile var oldModelReg: Option[ModelRegValue2] = None
  @volatile var nextLoggingStatusTime: Imports.DateTime = getNextTime(30)

  def probeInstrumentStatusType: Seq[InstrumentStatusType]

  def readReg(statusTypeList: List[InstrumentStatusType], full: Boolean): Future[Option[ModelRegValue2]]

  import scala.concurrent.{Future, blocking}

  val readPeriod : Int = 3

  def receive(): Receive = normalPhase()
  import AbstractCollector._

  private def readRegHandler(recordCalibration: Boolean): Unit = {
    try {
      for (instrumentStatusTypes <- instrumentStatusTypesOpt) {
        import com.github.nscala_time.time.Imports._
        val logStatus = DateTime.now() > nextLoggingStatusTime
        val readRegF = readReg(instrumentStatusTypes, logStatus)
        readRegF.onComplete {
          case Success(regValueOpt) =>
            for (regValues <- regValueOpt) {
              regValueReporter(regValues, logStatus)(recordCalibration)
            }
          case Failure(exception) =>
            errorHandler(exception)
            throw exception
        }
      }
      connected = true
    } catch {
      case ex: Exception =>
        logger.error(s"$instId:$desc=>${ex.getMessage}", ex)
        if (connected)
          alarmOp.log(alarmOp.instrumentSrc(instId), Alarm.Level.ERR, s"${ex.getMessage}")

        connected = false
    } finally {
      import scala.concurrent.duration._
      readRegTimer = if (protocol.protocol == Protocol.tcp)
        Some(context.system.scheduler.scheduleOnce(Duration(readPeriod, SECONDS), self, ReadRegister))
      else
        Some(context.system.scheduler.scheduleOnce(Duration(4, SECONDS), self, ReadRegister))
    }
  }

  def executeCalibration(calibrationType: CalibrationType): Unit = {
    if (deviceConfig.monitorTypes.isEmpty)
      logger.error("There is no monitor type for calibration.")
    else if (!connected)
      logger.error("Cannot calibration before connected.")
    else {
      startCalibration(calibrationType, deviceConfig.monitorTypes.get)
    }
  }

  def connectHost(): Unit

  def getDataRegList: Seq[DataReg]

  def normalPhase(): Receive = {
    case ConnectHost =>
      Future {
        blocking {
          try {
            connectHost()
            connected = true
            if (instrumentStatusTypesOpt.isEmpty) {
              val statusTypeList = probeInstrumentStatusType.toList
              if (getDataRegList.forall(reg => statusTypeList.exists(statusType => statusType.addr == reg.address))) {
                // Data register must included in the list
                instrumentStatusTypesOpt = Some(statusTypeList)
                instrumentOp.updateStatusType(instId, instrumentStatusTypesOpt.get)
              } else {
                val dataReg = getDataRegList
                logger.error(s"statusType ${statusTypeList}")
                logger.error(s"dataReg ${dataReg} not in statusType")
                throw new Exception("Probe register failed. Data register is not in there...")
              }
            }
            import scala.concurrent.duration._
            readRegTimer = Some(context.system.scheduler.scheduleOnce(FiniteDuration(3, SECONDS), self, ReadRegister))
          } catch {
            case ex: Exception =>
              logger.error(s"$instId:$desc=>${ex.getMessage}", ex)
              alarmOp.log(alarmOp.instrumentSrc(instId), Alarm.Level.ERR, s"無法連接:${ex.getMessage}")
              import scala.concurrent.duration._

              context.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectHost)
          }
        }
      }

    case ResetConnection =>
      logger.info(s"$instId : Reset connection")
      for (timer <- readRegTimer) {
        timer.cancel()
        readRegTimer = None
        self ! ConnectHost
      }

    case ReadRegister =>
      readRegHandler(false)

    case SetState(_, state) =>
      if (state == MonitorStatus.ZeroCalibrationStat) {
        logger.error(s"Unexpected command: SetState($state)")
      } else {
        collectorState = state
        instrumentOp.setState(instId, collectorState)
      }
      logger.info(s"$self => ${monitorStatusOp.map(collectorState).name}")

    case AutoCalibration(_) =>
      executeCalibration(AutoZero)

    case ManualZeroCalibration(_) =>
      executeCalibration(ManualZero)

    case ManualSpanCalibration(_) =>
      executeCalibration(ManualSpan)

    case ExecuteSeq(seq, on) =>
      executeSeq(seq, on)

    case WriteSignal(mtId, bit) =>
      onWriteSignal(mtId, bit)

    case TriggerVault(zero, on) =>
      logger.info(s"TriggerVault($zero, $on)")
      Future.successful(triggerVault(zero, on))
  }

  def triggerVault(zero: Boolean, on: Boolean): Unit

  def executeSeq(str: String, bool: Boolean): Unit = {}

  def onWriteSignal(mt:String, bit:Boolean): Unit = {}

  def startCalibration(calibrationType: CalibrationType, monitorTypes: List[String]): Unit = {
    logger.info(s"start calibrating ${monitorTypes.mkString(",")}")
    Future{
      blocking{
        onCalibrationStart()
        import com.github.nscala_time.time.Imports._
        val endState = collectorState
        if (!calibrationType.zero &&
          deviceConfig.calibratorPurgeTime.getOrElse(0) != 0) {
          val timer = Some(purgeCalibrator())
          context become calibrationPhase(calibrationType, DateTime.now, recordCalibration = false, List.empty[ReportData],
            List.empty[(String, Double)], endState, timer)
        } else {
          context become calibrationPhase(calibrationType, DateTime.now, recordCalibration = false, List.empty[ReportData],
            List.empty[(String, Double)], endState, None)
          val delay = getDelayAfterCalibrationStart
          if(delay != 0)
            context.system.scheduler.scheduleOnce(FiniteDuration(delay, MILLISECONDS), self, RaiseStart)
          else
            self ! RaiseStart
        }
      }
    }
  }

  import com.github.nscala_time.time.Imports._

  def calibrationErrorHandler(id: String, timerOpt: Option[Cancellable], endState: String): PartialFunction[Throwable, Unit] = {
    case ex: Exception =>
      for (timer <- timerOpt)
        timer.cancel()

      logInstrumentError(id, s"${self.path.name}: ${ex.getMessage}. ", ex)
      resetToNormal()
      instrumentOp.setState(id, endState)
      collectorState = endState
      context become normalPhase()
  }

  private def calibrationPhase(calibrationType: CalibrationType, startTime: DateTime, recordCalibration: Boolean, calibrationReadingList: List[ReportData],
                               zeroReading: List[(String, Double)],
                               endState: String, calibrationTimerOpt: Option[Cancellable]): Receive = {
    case ConnectHost =>
      logger.error("unexpected ConnectHost msg")

    case ResetConnection =>
      logger.info(s"$instId: Reset connection")
      logger.info(s"$instId: Cancel calibration.")
      for (calibrationTimer <- calibrationTimerOpt)
        calibrationTimer.cancel()

      collectorState = MonitorStatus.NormalStat
      instrumentOp.setState(instId, MonitorStatus.NormalStat)
      resetToNormal()
      for (timer <- readRegTimer) {
        timer.cancel()
        readRegTimer = None
        self ! ConnectHost
      }
      context become normalPhase()

    case ReadRegister =>
      readRegHandler(recordCalibration)

    case SetState(_, targetState) =>
      if (targetState == MonitorStatus.ZeroCalibrationStat) {
        logger.info("Already in calibration. Ignore it")
      } else if (targetState == MonitorStatus.NormalStat) {
        logger.info("Cancel calibration.")
        for (calibrationTimer <- calibrationTimerOpt)
          calibrationTimer.cancel()

        collectorState = targetState
        instrumentOp.setState(instId, targetState)
        resetToNormal()
        context become normalPhase()
      } else {
        logger.info(s"During calibration ignore $targetState change.")
      }
      logger.info(s"$self => ${monitorStatusOp.map(collectorState).name}")

    case RaiseStart =>
      collectorState =
        if (calibrationType.zero)
          MonitorStatus.ZeroCalibrationStat
        else
          MonitorStatus.SpanCalibrationStat

      instrumentOp.setState(instId, collectorState)

      logger.info(s"${self.path.name} => RaiseStart")
      import scala.concurrent.duration._

      val calibrationTimer =
        for (raiseTime <- deviceConfig.raiseTime) yield
          context.system.scheduler.scheduleOnce(Duration(raiseTime, SECONDS), self, HoldStart)

      context become calibrationPhase(calibrationType, startTime, recordCalibration,
        calibrationReadingList, zeroReading, endState, calibrationTimer)

      Future {
        blocking {
          if (calibrationType.zero)
            triggerZeroCalibration(true)
          else
            triggerSpanCalibration(true)
        }
      }.failed.foreach(calibrationErrorHandler(instId, calibrationTimerOpt, endState))

    case HoldStart =>
      logger.info(s"${self.path.name} => HoldStart")
      import scala.concurrent.duration._
      val calibrationTimer = {
        for (holdTime <- deviceConfig.holdTime) yield
          context.system.scheduler.scheduleOnce(Duration(holdTime, SECONDS), self, DownStart)
      }
      context become calibrationPhase(calibrationType, startTime, recordCalibration = true, calibrationReadingList,
        zeroReading, endState, calibrationTimer)

    case DownStart =>
      logger.info(s"${self.path.name} => DownStart (${calibrationReadingList.length})")
      import scala.concurrent.duration._

      if (calibrationType.auto && calibrationType.zero) {
        context become calibrationPhase(calibrationType, startTime, recordCalibration = false, calibrationReadingList,
          zeroReading, endState, None)
        // Auto zero calibration will jump to end immediately
        self ! CalibrateEnd
      } else {
        collectorState = MonitorStatus.CalibrationResume
        instrumentOp.setState(instId, collectorState)
        val calibrationTimerOpt =
          for (downTime <- deviceConfig.downTime) yield
            context.system.scheduler.scheduleOnce(Duration(downTime, SECONDS), self, CalibrateEnd)

        context become calibrationPhase(calibrationType, startTime, recordCalibration = false, calibrationReadingList,
          zeroReading, endState, calibrationTimerOpt)
      }

      Future {
        blocking {
          if (calibrationType.zero)
            triggerZeroCalibration(false)
          else
            triggerSpanCalibration(false)
        }
      }.failed.foreach(calibrationErrorHandler(instId, calibrationTimerOpt, endState))

    case rd: ReportData =>
      if (recordCalibration)
        context become calibrationPhase(calibrationType, startTime, recordCalibration, rd :: calibrationReadingList,
          zeroReading, endState, calibrationTimerOpt)

    case CalibrateEnd =>
      logger.info(s"$self =>$calibrationType CalibrateEnd")

      val values = for {mt <- deviceConfig.monitorTypes.getOrElse(List.empty[String])} yield {
        val calibrations = calibrationReadingList.flatMap {
          reading =>
            reading.dataList(monitorTypeDB).filter {
              _.mt == mt
            }.map { r => r.value }
        }

        if (calibrations.isEmpty) {
          logger.warn(s"No calibration data for $mt")
          (mt, 0d)
        } else
          (mt, calibrations.sum / calibrations.length)
      }

      //For auto calibration, span will be executed after zero
      if (calibrationType.auto && calibrationType.zero) {
        for (v <- values)
          logger.info(s"${v._1} zero calibration end. (${v._2})")

        if (deviceConfig.calibratorPurgeTime.isDefined) {
          collectorState = MonitorStatus.NormalStat
          instrumentOp.setState(instId, collectorState)
          val raiseStartTimer = Some(purgeCalibrator)
          context become calibrationPhase(AutoSpan, startTime, recordCalibration = false, List.empty[ReportData],
            values, endState, raiseStartTimer)
        } else {
          context become calibrationPhase(AutoSpan, startTime, recordCalibration = false, List.empty[ReportData],
            values, endState, None)
          self ! RaiseStart
        }
      } else {
        val endTime = DateTime.now()

        if (calibrationType.auto) {
          val zeroMap = zeroReading.toMap
          val spanMap = values.toMap

          for (mt <- deviceConfig.monitorTypes.getOrElse(List.empty[String])) {
            val zero = zeroMap.get(mt)
            val span = spanMap.get(mt)
            val spanStd = monitorTypeDB.map(mt).span
            val cal = Calibration(mt, startTime, endTime, zero, spanStd, span)
            calibrationOp.insertFuture(cal)
          }
        } else {
          val valueMap = values.toMap
          for (mt <- deviceConfig.monitorTypes.getOrElse(List.empty[String])) {
            val values = valueMap.get(mt)
            val cal =
              if (calibrationType.zero)
                Calibration(mt, startTime, endTime, values, None, None)
              else {
                val spanStd = monitorTypeDB.map(mt).span
                Calibration(mt, startTime, endTime, None, spanStd, values)
              }
            calibrationOp.insertFuture(cal)
          }
        }
        logger.info("All monitorTypes are calibrated.")
        collectorState = endState
        instrumentOp.setState(instId, collectorState)
        resetToNormal()
        onCalibrationEnd()
        context become normalPhase()
        logger.info(s"$self => ${monitorStatusOp.map(collectorState).name}")
      }
  }

  def getCalibrationReg: Option[CalibrationReg]

  def setCalibrationReg(address: Int, on: Boolean): Unit

  def onCalibrationStart(): Unit = {}

  def getDelayAfterCalibrationStart: Int = 0

  def onCalibrationEnd(): Unit = {}

  def resetToNormal(): Unit = {
    try {
      deviceConfig.calibrateZeoDO foreach {
        doBit =>
          context.parent ! WriteDO(doBit, on = false)
      }

      deviceConfig.calibrateSpanSeq foreach {
        seq =>
          context.parent ! ExecuteSeq(seq, on = false)
      }

      context.parent ! ExecuteSeq(T700_STANDBY_SEQ, on = true)

      if (!deviceConfig.skipInternalVault.contains(true)) {
        for (reg <- getCalibrationReg) {
          setCalibrationReg(reg.zeroAddress, on = false)
          setCalibrationReg(reg.spanAddress, on = false)
        }
      }
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  def triggerZeroCalibration(v: Boolean): Unit = {
    try {
      deviceConfig.calibrateZeoDO.foreach {
        doBit =>
          context.parent ! WriteDO(doBit, v)
      }

      if (v)
        deviceConfig.calibrateZeoSeq.foreach {
          seq =>
            context.parent ! ExecuteSeq(seq, v)
        }


      if (!deviceConfig.skipInternalVault.contains(true)) {
        for (reg <- getCalibrationReg)
          setCalibrationReg(reg.zeroAddress, v)
      }
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  def triggerSpanCalibration(v: Boolean): Unit = {
    try {
      deviceConfig.calibrateSpanDO foreach {
        doBit =>
          context.parent ! WriteDO(doBit, v)
      }

      if (v)
        deviceConfig.calibrateSpanSeq foreach  {
          seq =>
            context.parent ! ExecuteSeq(seq, v)
        }

      if (!deviceConfig.skipInternalVault.contains(true)) {
        for (reg <- getCalibrationReg)
          setCalibrationReg(reg.spanAddress, v)
      }
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  def purgeCalibrator(): Cancellable = {
    import scala.concurrent.duration._

    val purgeTime = deviceConfig.calibratorPurgeTime.getOrElse(60)
    logger.info(s"Purge calibrator. Delay start of calibration $purgeTime seconds")
    triggerCalibratorPurge(true)
    context.system.scheduler.scheduleOnce(Duration(purgeTime + 1, SECONDS), self, RaiseStart)
  }

  def triggerCalibratorPurge(v: Boolean): Unit = {
    try {
      for (seq <- deviceConfig.calibratorPurgeSeq) {
        context.parent ! ExecuteSeq(seq, v)
      }
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  def getLoggingStatusPeriod: Int = 10

  def regValueReporter(regValue: ModelRegValue2, logStatus: Boolean)(recordCalibration: Boolean): Unit = {
    for (report <- reportData(regValue)) {
      context.parent ! report
      if (recordCalibration)
        self ! report
    }

    for {
      r <- regValue.modeRegs.zipWithIndex
      statusType = r._1._1
      enable = r._1._2
      idx = r._2
    } {
      if (enable) {
        if (oldModelReg.isEmpty || oldModelReg.get.modeRegs(idx)._2 != enable) {
          alarmOp.log(alarmOp.instrumentSrc(instId), Alarm.Level.INFO, statusType.desc)
        }
      }
    }

    for {
      r <- regValue.warnRegs.zipWithIndex
      statusType = r._1._1
      enable = r._1._2
      idx = r._2
    } {
      if (enable) {
        if (oldModelReg.isEmpty || oldModelReg.get.warnRegs(idx)._2 != enable) {
          alarmOp.log(alarmOp.instrumentSrc(instId), Alarm.Level.WARN, statusType.desc)
        }
      } else {
        if (oldModelReg.isDefined && oldModelReg.get.warnRegs(idx)._2 != enable) {
          alarmOp.log(alarmOp.instrumentSrc(instId), Alarm.Level.INFO, s"${statusType.desc} 解除")
        }
      }
    }

    if (logStatus) {
      try {
        logInstrumentStatus(regValue)
      } catch {
        case _: Throwable =>
          logger.error("Log instrument status failed")
      }
      nextLoggingStatusTime = nextLoggingStatusTime + getLoggingStatusPeriod.minute
    }
    oldModelReg = Some(regValue)
  }

  def logInstrumentStatus(regValue: ModelRegValue2): Unit = {
    val isList = regValue.inputRegs.map {
      kv =>
        val k = kv._1
        val v = kv._2
        InstrumentStatusDB.Status(k.key, v)
    }
    val instStatus = InstrumentStatusDB.InstrumentStatus(DateTime.now(), instId, isList).excludeNaN
    instrumentStatusOp.log(instStatus)
  }

  def findDataRegIdx(regValue: ModelRegValue2)(addr: Int): Option[Int] = {
    val dataReg = regValue.inputRegs.zipWithIndex.find(r_idx => r_idx._1._1.addr == addr)
    if (dataReg.isEmpty) {
      logger.warn(s"$instId Cannot found Data register $addr !")
      logger.info(regValue.inputRegs.toString())
      None
    } else
      Some(dataReg.get._2)
  }


  def reportData(regValue: ModelRegValue2): Option[ReportData] = {
    val optValues: Seq[Option[(String, (InstrumentStatusType, Double))]] = {
      for (dataReg <- getDataRegList) yield {
        for (idx <- findDataRegIdx(regValue)(dataReg.address)) yield {
          val rawValue: (InstrumentStatusType, Double) = regValue.inputRegs(idx)
          (dataReg.monitorType, (rawValue._1, rawValue._2 * dataReg.multiplier))
        }
      }
    }

    val monitorTypeData = optValues.flatten.map(
      t => MonitorTypeData(t._1, t._2._2, collectorState))

    if (monitorTypeData.isEmpty)
      None
    else
      Some(ReportData(monitorTypeData.toList))
  }

  override def postStop(): Unit = {
    for (timer <- readRegTimer)
      timer.cancel()
  }
}