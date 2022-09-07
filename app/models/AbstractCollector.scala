package models

import akka.actor._
import models.ModelHelper._
import models.Protocol.ProtocolParam
import models.TapiTxx.T700_STANDBY_SEQ
import play.api._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}

case class ModelRegValue2(inputRegs: List[(InstrumentStatusType, Double)],
                          modeRegs: List[(InstrumentStatusType, Boolean)],
                          warnRegs: List[(InstrumentStatusType, Boolean)])

object AbstractCollector {
  case object ResetConnection
}

abstract class AbstractCollector(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                                 alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                                 calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                                (instId: String, desc: String, deviceConfig: DeviceConfig, protocol: ProtocolParam) extends Actor {

  import TapiTxxCollector._

  self ! ConnectHost
  @volatile var readRegTimer: Option[Cancellable] = None
  @volatile var (collectorState: String, instrumentStatusTypesOpt) = {
    val instList = instrumentOp.getInstrument(instId)
    if (instList.nonEmpty) {
      val inst: Instrument = instList(0)
      (inst.state, inst.statusType)
    } else
      (MonitorStatus.NormalStat, None)
  }
  @volatile var connected = false
  @volatile var oldModelReg: Option[ModelRegValue2] = None
  @volatile var nextLoggingStatusTime = {
    def getNextTime(period: Int) = {
      import com.github.nscala_time.time.Imports._
      val now = DateTime.now()
      val nextMin = (now.getMinuteOfHour / period + 1) * period
      val hour = (now.getHourOfDay + (nextMin / 60)) % 24
      val nextDay = (now.getHourOfDay + (nextMin / 60)) / 24

      now.withHourOfDay(hour).withMinuteOfHour(nextMin % 60).withSecondOfMinute(0).withMillisOfSecond(0) + nextDay.day
    }

    val period = 30
    val nextTime = getNextTime(period)
    nextTime
  }

  def probeInstrumentStatusType: Seq[InstrumentStatusType]

  def readReg(statusTypeList: List[InstrumentStatusType], full: Boolean): Future[Option[ModelRegValue2]]

  import scala.concurrent.{Future, blocking}

  def receive(): Receive = normalPhase
  import AbstractCollector._

  def readRegHanlder(recordCalibration: Boolean): Unit = {
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
        Logger.error(s"${instId}:${desc}=>${ex.getMessage}", ex)
        if (connected)
          alarmOp.log(alarmOp.instrumentSrc(instId), alarmOp.Level.ERR, s"${ex.getMessage}")

        connected = false
    } finally {
      import scala.concurrent.duration._
      readRegTimer = if (protocol.protocol == Protocol.tcp)
        Some(context.system.scheduler.scheduleOnce(Duration(3, SECONDS), self, ReadRegister))
      else
        Some(context.system.scheduler.scheduleOnce(Duration(4, SECONDS), self, ReadRegister))
    }
  }

  def executeCalibration(calibrationType: CalibrationType): Unit = {
    if (deviceConfig.monitorTypes.isEmpty)
      Logger.error("There is no monitor type for calibration.")
    else if (!connected)
      Logger.error("Cannot calibration before connected.")
    else {
      startCalibration(calibrationType, deviceConfig.monitorTypes.get)
    }
  }

  def connectHost: Unit

  def getDataRegList: Seq[DataReg]

  def normalPhase(): Receive = {
    case ConnectHost =>
      Future {
        blocking {
          try {
            connectHost
            connected = true
            if (instrumentStatusTypesOpt.isEmpty) {
              val statusTypeList = probeInstrumentStatusType.toList
              if (getDataRegList.forall(reg => statusTypeList.exists(statusType => statusType.addr == reg.address))) {
                // Data register must included in the list
                instrumentStatusTypesOpt = Some(statusTypeList)
                instrumentOp.updateStatusType(instId, instrumentStatusTypesOpt.get)
              } else {
                val dataReg = getDataRegList
                Logger.error(s"statusType ${statusTypeList}")
                Logger.error(s"dataReg ${dataReg} not in statusType")
                throw new Exception("Probe register failed. Data register is not in there...")
              }
            }
            import scala.concurrent.duration._
            readRegTimer = Some(context.system.scheduler.scheduleOnce(Duration(3, SECONDS), self, ReadRegister))
          } catch {
            case ex: Exception =>
              Logger.error(s"${instId}:${desc}=>${ex.getMessage}", ex)
              alarmOp.log(alarmOp.instrumentSrc(instId), alarmOp.Level.ERR, s"無法連接:${ex.getMessage}")
              import scala.concurrent.duration._

              context.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectHost)
          }
        }
      }

    case ResetConnection =>
      Logger.info(s"$instId : Reset connection")
      for (timer <- readRegTimer) {
        timer.cancel()
        readRegTimer = None
        self ! ConnectHost
      }

    case ReadRegister =>
      readRegHanlder(false)

    case SetState(_, state) =>
      if (state == MonitorStatus.ZeroCalibrationStat) {
        Logger.error(s"Unexpected command: SetState($state)")
      } else {
        collectorState = state
        instrumentOp.setState(instId, collectorState)
      }
      Logger.info(s"$self => ${monitorStatusOp.map(collectorState).desp}")

    case AutoCalibration(instId) =>
      executeCalibration(AutoZero)

    case ManualZeroCalibration(instId) =>
      executeCalibration(ManualZero)

    case ManualSpanCalibration(instId) =>
      executeCalibration(ManualSpan)

    case ExecuteSeq(seq, on) =>
      executeSeq(seq, on)

    case WriteSignal(mtId, bit) =>
      onWriteSignal(mtId, bit)
  }

  def executeSeq(str: String, bool: Boolean): Unit = {}

  def onWriteSignal(mt:String, bit:Boolean): Unit = {}

  def startCalibration(calibrationType: CalibrationType, monitorTypes: List[String]): Unit = {
    Logger.info(s"start calibrating ${monitorTypes.mkString(",")}")
    onCalibrationStart()
    import com.github.nscala_time.time.Imports._
    val endState = collectorState
    if (!calibrationType.zero &&
      deviceConfig.calibratorPurgeTime.getOrElse(0) != 0) {
      val timer = Some(purgeCalibrator())
      context become calibrationPhase(calibrationType, DateTime.now, false, List.empty[ReportData],
        List.empty[(String, Double)], endState, timer)
    } else {
      context become calibrationPhase(calibrationType, DateTime.now, false, List.empty[ReportData],
        List.empty[(String, Double)], endState, None)
      self ! RaiseStart
    }
  }

  import com.github.nscala_time.time.Imports._

  def calibrationErrorHandler(id: String, timerOpt: Option[Cancellable], endState: String): PartialFunction[Throwable, Unit] = {
    case ex: Exception =>
      for (timer <- timerOpt)
        timer.cancel()

      logInstrumentError(id, s"${self.path.name}: ${ex.getMessage}. ", ex)
      resetToNormal
      instrumentOp.setState(id, endState)
      collectorState = endState
      context become normalPhase
  }

  def calibrationPhase(calibrationType: CalibrationType, startTime: DateTime, recordCalibration: Boolean, calibrationReadingList: List[ReportData],
                       zeroReading: List[(String, Double)],
                       endState: String, calibrationTimerOpt: Option[Cancellable]): Receive = {
    case ConnectHost =>
      Logger.error("unexpected ConnectHost msg")

    case ResetConnection =>
      Logger.info(s"$instId: Reset connection")
      Logger.info(s"$instId: Cancel calibration.")
      for (calibrationTimer <- calibrationTimerOpt)
        calibrationTimer.cancel()

      collectorState = MonitorStatus.NormalStat
      instrumentOp.setState(instId, MonitorStatus.NormalStat)
      resetToNormal
      for (timer <- readRegTimer) {
        timer.cancel()
        readRegTimer = None
        self ! ConnectHost
      }
      context become normalPhase

    case ReadRegister =>
      readRegHanlder(recordCalibration)

    case SetState(_, targetState) =>
      if (targetState == MonitorStatus.ZeroCalibrationStat) {
        Logger.info("Already in calibration. Ignore it")
      } else if (targetState == MonitorStatus.NormalStat) {
        Logger.info("Cancel calibration.")
        for (calibrationTimer <- calibrationTimerOpt)
          calibrationTimer.cancel()

        collectorState = targetState
        instrumentOp.setState(instId, targetState)
        resetToNormal
        context become normalPhase
      } else {
        Logger.info(s"During calibration ignore $targetState change.")
      }
      Logger.info(s"$self => ${monitorStatusOp.map(collectorState).desp}")

    case RaiseStart =>
      collectorState =
        if (calibrationType.zero)
          MonitorStatus.ZeroCalibrationStat
        else
          MonitorStatus.SpanCalibrationStat

      instrumentOp.setState(instId, collectorState)

      Logger.info(s"${self.path.name} => RaiseStart")
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
      } onFailure calibrationErrorHandler(instId, calibrationTimerOpt, endState)

    case HoldStart =>
      Logger.info(s"${self.path.name} => HoldStart")
      import scala.concurrent.duration._
      val calibrationTimer = {
        for (holdTime <- deviceConfig.holdTime) yield
          context.system.scheduler.scheduleOnce(Duration(holdTime, SECONDS), self, DownStart)
      }
      context become calibrationPhase(calibrationType, startTime, true, calibrationReadingList,
        zeroReading, endState, calibrationTimer)

    case DownStart =>
      Logger.info(s"${self.path.name} => DownStart (${calibrationReadingList.length})")
      import scala.concurrent.duration._

      if (calibrationType.auto && calibrationType.zero) {
        context become calibrationPhase(calibrationType, startTime, false, calibrationReadingList,
          zeroReading, endState, None)
        // Auto zero calibration will jump to end immediately
        self ! CalibrateEnd
      } else {
        collectorState = MonitorStatus.CalibrationResume
        instrumentOp.setState(instId, collectorState)
        val calibrationTimerOpt =
          for (downTime <- deviceConfig.downTime) yield
            context.system.scheduler.scheduleOnce(Duration(downTime, SECONDS), self, CalibrateEnd)

        context become calibrationPhase(calibrationType, startTime, false, calibrationReadingList,
          zeroReading, endState, calibrationTimerOpt)
      }

      Future {
        blocking {
          if (calibrationType.zero)
            triggerZeroCalibration(false)
          else
            triggerSpanCalibration(false)
        }
      } onFailure (calibrationErrorHandler(instId, calibrationTimerOpt, endState))

    case rd: ReportData =>
      if (recordCalibration)
        context become calibrationPhase(calibrationType, startTime, recordCalibration, rd :: calibrationReadingList,
          zeroReading, endState, calibrationTimerOpt)

    case CalibrateEnd =>
      Logger.info(s"$self =>$calibrationType CalibrateEnd")

      val values = for {mt <- deviceConfig.monitorTypes.getOrElse(List.empty[String])} yield {
        val calibrations = calibrationReadingList.flatMap {
          reading =>
            reading.dataList.filter {
              _.mt == mt
            }.map { r => r.value }
        }

        if (calibrations.length == 0) {
          Logger.warn(s"No calibration data for $mt")
          (mt, 0d)
        } else
          (mt, calibrations.sum / calibrations.length)
      }

      //For auto calibration, span will be executed after zero
      if (calibrationType.auto && calibrationType.zero) {
        for (v <- values)
          Logger.info(s"${v._1} zero calibration end. (${v._2})")

        if (deviceConfig.calibratorPurgeTime.isDefined) {
          collectorState = MonitorStatus.NormalStat
          instrumentOp.setState(instId, collectorState)
          val raiseStartTimer = Some(purgeCalibrator)
          context become calibrationPhase(AutoSpan, startTime, false, List.empty[ReportData],
            values, endState, raiseStartTimer)
        } else {
          context become calibrationPhase(AutoSpan, startTime, false, List.empty[ReportData],
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
            val spanStd = monitorTypeOp.map(mt).span
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
                val spanStd = monitorTypeOp.map(mt).span
                Calibration(mt, startTime, endTime, None, spanStd, values)
              }
            calibrationOp.insertFuture(cal)
          }
        }
        onCalibrationEnd()
        Logger.info("All monitorTypes are calibrated.")
        collectorState = endState
        instrumentOp.setState(instId, collectorState)
        resetToNormal
        context become normalPhase
        Logger.info(s"$self => ${monitorStatusOp.map(collectorState).desp}")
      }
  }

  def getCalibrationReg: Option[CalibrationReg]

  def setCalibrationReg(address: Int, on: Boolean): Unit

  def onCalibrationStart(): Unit = {

  }

  def onCalibrationEnd() = {

  }

  def resetToNormal() {
    try {
      deviceConfig.calibrateZeoDO map {
        doBit =>
          context.parent ! WriteDO(doBit, false)
      }

      deviceConfig.calibrateSpanSeq map {
        seq =>
          context.parent ! ExecuteSeq(seq, false)
      }

      if (!deviceConfig.skipInternalVault.contains(true)) {
        for (reg <- getCalibrationReg) {
          setCalibrationReg(reg.zeroAddress, false)
          setCalibrationReg(reg.spanAddress, false)
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


      if (deviceConfig.skipInternalVault != Some(true)) {
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
      deviceConfig.calibrateSpanDO map {
        doBit =>
          context.parent ! WriteDO(doBit, v)
      }

      if (v)
        deviceConfig.calibrateSpanSeq map {
          seq =>
            context.parent ! ExecuteSeq(seq, v)
        }

      if (deviceConfig.skipInternalVault != Some(true)) {
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
    Logger.info(s"Purge calibrator. Delay start of calibration $purgeTime seconds")
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
          alarmOp.log(alarmOp.instrumentSrc(instId), alarmOp.Level.INFO, statusType.desc)
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
          alarmOp.log(alarmOp.instrumentSrc(instId), alarmOp.Level.WARN, statusType.desc)
        }
      } else {
        if (oldModelReg.isDefined && oldModelReg.get.warnRegs(idx)._2 != enable) {
          alarmOp.log(alarmOp.instrumentSrc(instId), alarmOp.Level.INFO, s"${statusType.desc} 解除")
        }
      }
    }

    if (logStatus) {
      try {
        logInstrumentStatus(regValue)
      } catch {
        case _: Throwable =>
          Logger.error("Log instrument status failed")
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
        instrumentStatusOp.Status(k.key, v)
    }
    val instStatus = instrumentStatusOp.InstrumentStatus(DateTime.now(), instId, isList).excludeNaN
    instrumentStatusOp.log(instStatus)
  }

  def findDataRegIdx(regValue: ModelRegValue2)(addr: Int): Option[Int] = {
    val dataReg = regValue.inputRegs.zipWithIndex.find(r_idx => r_idx._1._1.addr == addr)
    if (dataReg.isEmpty) {
      Logger.warn(s"$instId Cannot found Data register $addr !")
      Logger.info(regValue.inputRegs.toString())
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
      t => MonitorTypeData(t._1, t._2._2.toDouble, collectorState))

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