package models

import akka.actor._
import com.google.inject.assistedinject.Assisted
import com.serotonin.modbus4j.locator.BaseLocator
import com.serotonin.modbus4j.serial.SerialPortWrapper
import models.ModelHelper._
import models.MultiCalibrator.TriggerVault
import models.Protocol.ProtocolParam
import models.TapiTxx.T700_STANDBY_SEQ

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

class TcpModbusCollector @Inject()(instrumentOp: InstrumentDB,
                                   monitorStatusOp: MonitorStatusDB,
                                   alarmOp: AlarmDB,
                                   monitorTypeDB: MonitorTypeDB,
                                   calibrationOp: CalibrationDB,
                                   instrumentStatusOp: InstrumentStatusDB)
                                  (@Assisted("instId") instId: String,
                                   @Assisted("desc") desc: String,
                                   @Assisted modelReg: TcpModelReg,
                                   @Assisted deviceConfig: DeviceConfig,
                                   @Assisted("protocol") protocol: ProtocolParam) extends Actor with ActorLogging {
  val InputKey = "Input"

  import DataCollectManager._
  import TapiTxxCollector._
  import com.serotonin.modbus4j._
  import com.serotonin.modbus4j.ip.IpParameters

  val HoldingKey = "Holding"
  val ModeKey = "Mode"
  resetToNormal()
  self ! ConnectHost
  val WarnKey = "Warn"
  @volatile var timerOpt: Option[Cancellable] = None
  @volatile var masterOpt: Option[ModbusMaster] = None
  @volatile var (collectorState: String, instrumentStatusTypesOpt) = {
    val instList = instrumentOp.getInstrument(instId)
    if (instList.nonEmpty) {
      val inst: Instrument = instList(0)
      (inst.state, inst.statusType)
    } else
      (MonitorStatus.NormalStat, None)
  }
  @volatile var connected = false
  @volatile var oldModelReg: Option[ModelRegValue] = None
  @volatile var nextLoggingStatusTime = {
    def getNextTime(period: Int) = {
      import com.github.nscala_time.time.Imports._
      val now = DateTime.now()
      val nextMin = (now.getMinuteOfHour / period + 1) * period
      val hour = (now.getHourOfDay + (nextMin / 60)) % 24
      val nextDay = (now.getHourOfDay + (nextMin / 60)) / 24

      now.withHourOfDay(hour).withMinuteOfHour(nextMin % 60).withSecondOfMinute(0).withMillisOfSecond(0) + nextDay.day
    }

    // suppose every 10 min
    val period = 30
    val nextTime = getNextTime(period)
    //log.debug(s"$instId next logging time= $nextTime")
    nextTime
  }

  def probeInstrumentStatusType: Seq[InstrumentStatusType] = {
    log.info("Probing supported modbus registers...")
    import com.serotonin.modbus4j.code.DataType
    import com.serotonin.modbus4j.locator.BaseLocator

    def probeInputReg(addr: Int, desc: String) = {
      try {
        val locator = if (protocol.protocol == Protocol.tcp)
          BaseLocator.inputRegister(deviceConfig.slaveID.getOrElse(1), addr, DataType.FOUR_BYTE_FLOAT)
        else
          BaseLocator.inputRegister(deviceConfig.slaveID.getOrElse(1), addr, DataType.FOUR_BYTE_FLOAT_SWAPPED)

        for (master <- masterOpt)
          master.getValue(locator)

        true
      } catch {
        case ex: Throwable =>
          log.error(s"$instId:$desc=> $ex.getMessage", ex)
          log.info(s"$addr $desc is not supported.")
          false
      }
    }

    def probeHoldingReg(addr: Int, desc: String): Boolean = {
      try {
        val locator = BaseLocator.holdingRegister(deviceConfig.slaveID.getOrElse(1), addr, DataType.FOUR_BYTE_FLOAT)
        for (master <- masterOpt)
          master.getValue(locator)

        true
      } catch {
        case _: Throwable =>
          log.info(s"$instId:$desc=>$addr $desc is not supported.")
          false
      }
    }

    def probeInputStatus(addr: Int, desc: String): Boolean = {
      try {
        val locator = BaseLocator.inputStatus(deviceConfig.slaveID.getOrElse(1), addr)
        masterOpt.get.getValue(locator)
        true
      } catch {
        case _: Throwable =>
          log.info(s"${instId}:${desc}=>$addr $desc is not supported.")
          false
      }
    }

    val inputRegs =
      for {r <- modelReg.inputRegs if probeInputReg(r.addr, r.desc)}
        yield r

    val inputRegStatusType =
      for {
        r <- inputRegs
      } yield InstrumentStatusType(key = s"$InputKey${r.addr}", addr = r.addr, desc = r.desc, unit = r.unit)

    val holdingRegs =
      for (r <- modelReg.holdingRegs if probeHoldingReg(r.addr, r.desc))
        yield r

    val holdingRegStatusType =
      for {
        r <- holdingRegs
      } yield InstrumentStatusType(key = s"$HoldingKey${r.addr}", addr = r.addr, desc = r.desc, unit = r.unit)

    val modeRegs =
      for (r <- modelReg.modeRegs if probeInputStatus(r.addr, r.desc))
        yield r

    val modeRegStatusType =
      for {
        r <- modeRegs
      } yield InstrumentStatusType(key = s"$ModeKey${r.addr}", addr = r.addr, desc = r.desc, unit = "-")

    val warnRegs =
      for (r <- modelReg.warnRegs if probeInputStatus(r.addr, r.desc))
        yield r

    val warnRegStatusType =
      for {
        r <- warnRegs
      } yield InstrumentStatusType(key = s"$WarnKey${r.addr}", addr = r.addr, desc = r.desc, unit = "-")

    log.info("Finish probing.")
    inputRegStatusType ++ holdingRegStatusType ++ modeRegStatusType ++ warnRegStatusType
  }

  def readReg(statusTypeList: List[InstrumentStatusType]): ModelRegValue = {
    import com.serotonin.modbus4j.BatchRead
    val batch = new BatchRead[Integer]

    import com.serotonin.modbus4j.locator.BaseLocator

    for {
      st_idx <- statusTypeList.zipWithIndex
      st = st_idx._1
      idx = st_idx._2
    } {
      if (st.key.startsWith(InputKey)) {
        batch.addLocator(idx, BaseLocator.inputRegister(deviceConfig.slaveID.getOrElse(1), st.addr, modelReg.byteSwapMode))
      } else if (st.key.startsWith(HoldingKey)) {
        batch.addLocator(idx, BaseLocator.holdingRegister(deviceConfig.slaveID.getOrElse(1), st.addr, modelReg.byteSwapMode))
      } else if (st.key.startsWith(ModeKey) || st.key.startsWith(WarnKey)) {
        batch.addLocator(idx, BaseLocator.inputStatus(deviceConfig.slaveID.getOrElse(1), st.addr))
      } else {
        throw new Exception(s"Unexpected key ${st.key}")
      }
    }

    batch.setContiguousRequests(true)

    val results = masterOpt.get.send(batch)
    val inputs =
      for {
        st_idx <- statusTypeList.zipWithIndex if st_idx._1.key.startsWith(InputKey)
        idx = st_idx._2
      } yield {
        try {
          (st_idx._1, results.getFloatValue(idx).toFloat * modelReg.multiplier)
        } catch {
          case ex: Exception =>
            log.error(s"failed at ${idx}")
            throw ex
        }
      }

    val holdings =
      for {
        st_idx <- statusTypeList.zipWithIndex if st_idx._1.key.startsWith(HoldingKey)
        idx = st_idx._2
      } yield (st_idx._1, results.getFloatValue(idx).toFloat)

    val modes =
      for {
        st_idx <- statusTypeList.zipWithIndex if st_idx._1.key.startsWith(ModeKey)
        idx = st_idx._2
      } yield (st_idx._1, results.getValue(idx).asInstanceOf[Boolean])

    val warns =
      for {
        st_idx <- statusTypeList.zipWithIndex if st_idx._1.key.startsWith(WarnKey)
        idx = st_idx._2
      } yield (st_idx._1, results.getValue(idx).asInstanceOf[Boolean])

    ModelRegValue(inputs, holdings, modes, warns)
  }

  import scala.concurrent.{Future, blocking}

  def receive(): Receive = normalReceive()

  def readRegFuture(recordCalibration: Boolean): Future[Unit] =
    Future {
      blocking {
        try {
          if (instrumentStatusTypesOpt.isDefined) {
            val regValues = readReg(instrumentStatusTypesOpt.get)
            regValueReporter(regValues)(recordCalibration)
          }
          connected = true
        } catch {
          case ex: Exception =>
            log.error(s"$instId:$desc=>${ex.getMessage}", ex)
            if (connected)
              alarmOp.log(alarmOp.instrumentSrc(instId), Alarm.Level.ERR, s"${ex.getMessage}")

            connected = false
        } finally {
          import scala.concurrent.duration._
          timerOpt = if (protocol.protocol == Protocol.tcp)
            Some(context.system.scheduler.scheduleOnce(Duration(2, SECONDS), self, ReadRegister))
          else
            Some(context.system.scheduler.scheduleOnce(Duration(5, SECONDS), self, ReadRegister))
        }
      }
    }

  def executeCalibration(calibrationType: CalibrationType): Unit = {
    if (deviceConfig.monitorTypes.isEmpty)
      log.error("There is no monitor type for calibration.")
    else if (!connected)
      log.error("Cannot calibration before connected.")
    else
      startCalibration(calibrationType, deviceConfig.monitorTypes.get)
  }

  def normalReceive(): Receive = {
    case ConnectHost =>

      Future {
        blocking {
          try {
            val modbusFactory = new ModbusFactory()
            if (protocol.protocol == Protocol.tcp) {
              val ipParameters = new IpParameters()
              ipParameters.setHost(protocol.host.get);
              ipParameters.setPort(502);
              log.info(s"${self.toString()}: connect ${protocol.host.get}")
              val master = modbusFactory.createTcpMaster(ipParameters, true)
              master.setTimeout(4000)
              master.setRetries(1)
              master.setConnected(true)
              master.init();
              masterOpt = Some(master)
            } else {
              if (masterOpt.isEmpty) {
                log.info(protocol.toString)
                val serialWrapper: SerialPortWrapper = TcpModbusDrv2.getSerialWrapper(protocol)
                val master = modbusFactory.createRtuMaster(serialWrapper)
                master.init()
                masterOpt = Some(master)
              } else {
                masterOpt.get.init()
              }
            }

            connected = true
            if (instrumentStatusTypesOpt.isEmpty) {
              val statusTypeList = probeInstrumentStatusType.toList
              if (modelReg.dataRegs.forall(reg => statusTypeList.exists(statusType => statusType.addr == reg.address))) {
                // Data register must included in the list
                instrumentStatusTypesOpt = Some(probeInstrumentStatusType.toList)
                instrumentOp.updateStatusType(instId, instrumentStatusTypesOpt.get)
              } else {
                throw new Exception("Probe register failed. Data register is not in there...")
              };
            }
            import scala.concurrent.duration._
            timerOpt = Some(context.system.scheduler.scheduleOnce(Duration(3, SECONDS), self, ReadRegister))
          } catch {
            case ex: Exception =>
              log.error(s"${instId}:${desc}=>${ex.getMessage}", ex)
              alarmOp.log(alarmOp.instrumentSrc(instId), Alarm.Level.ERR, s"無法連接:${ex.getMessage}")
              import scala.concurrent.duration._

              context.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectHost)
          }
        }
      }

    case ReadRegister =>
      readRegFuture(false)

    case SetState(id, state) =>
      if (state == MonitorStatus.ZeroCalibrationStat) {
        log.error(s"Unexpected command: SetState($state)")
      } else {
        collectorState = state
        instrumentOp.setState(instId, collectorState)
      }
      log.info(s"$self => ${monitorStatusOp.map(collectorState).name}")

    case AutoCalibration(instId) =>
      executeCalibration(AutoZero)

    case ManualZeroCalibration(instId) =>
      executeCalibration(ManualZero)

    case ManualSpanCalibration(instId) =>
      executeCalibration(ManualSpan)

    case ExecuteSeq(seq, on) =>
      executeSeq(seq, on)

    case TriggerVault(zero, on) =>
      log.info(s"TriggerVault($zero, $on)")
      Future.successful(triggerVault(zero, on))
  }

  def triggerVault(zero: Boolean, on: Boolean): Unit = {
    for (reg <- modelReg.calibrationReg; master <- masterOpt) {
      val locator = if (zero)
        BaseLocator.coilStatus(deviceConfig.slaveID.getOrElse(1), reg.zeroAddress)
      else
        BaseLocator.coilStatus(deviceConfig.slaveID.getOrElse(1), reg.spanAddress)

      master.setValue(locator, on)
    }
  }

  // FIXME not implemented
  def executeSeq(str: String, bool: Boolean): Unit = ???

  def startCalibration(calibrationType: CalibrationType, monitorTypes: List[String]): Unit = {

    log.info(s"start calibrating ${monitorTypes.mkString(",")}")
    import com.github.nscala_time.time.Imports._
    val endState = collectorState

    if (!calibrationType.zero &&
      deviceConfig.calibratorPurgeTime.isDefined && deviceConfig.calibratorPurgeTime.get != 0) {
      context become calibration(calibrationType, DateTime.now, recordCalibration = false, List.empty[ReportData],
        List.empty[(String, Double)], endState, Some(purgeCalibrator))
    } else {
      context become calibration(calibrationType, DateTime.now, recordCalibration = false, List.empty[ReportData],
        List.empty[(String, Double)], endState, None)
      self ! RaiseStart
    }

  }

  import com.github.nscala_time.time.Imports._

  def calibrationErrorHandler(id: String, timer: Option[Cancellable], endState: String): PartialFunction[Throwable, Unit] = {
    case ex: Exception =>
      for (timer <- timerOpt)
        timer.cancel()

      logInstrumentError(id, s"${self.path.name}: ${ex.getMessage}. ", ex)
      resetToNormal()
      instrumentOp.setState(id, endState)
      collectorState = endState
      context become normalReceive()
  }

  def calibration(calibrationType: CalibrationType, startTime: DateTime, recordCalibration: Boolean, calibrationReadingList: List[ReportData],
                  zeroReading: List[(String, Double)],
                  endState: String, timerOpt: Option[Cancellable]): Receive = {
    case ConnectHost =>
      log.error("unexpected ConnectHost msg")

    case ReadRegister =>
      readRegFuture(recordCalibration)

    case SetState(_, targetState) =>
      if (targetState == MonitorStatus.ZeroCalibrationStat) {
        log.info("Already in calibration. Ignore it")
      } else if (targetState == MonitorStatus.NormalStat) {
        log.info("Cancel calibration.")
        for (timer <- timerOpt)
          timer.cancel()

        collectorState = targetState
        instrumentOp.setState(instId, targetState)
        resetToNormal()
        context become normalReceive()
      } else {
        log.info(s"During calibration ignore $targetState state change")
      }
      log.info(s"$self => ${monitorStatusOp.map(collectorState).name}")

    case RaiseStart =>
      collectorState =
        if (calibrationType.zero)
          MonitorStatus.ZeroCalibrationStat
        else
          MonitorStatus.SpanCalibrationStat

      instrumentOp.setState(instId, collectorState)
      log.info(s"${self.path.name} => RaiseStart")
      import scala.concurrent.duration._

      val calibrationTimer =
        for (raiseTime <- deviceConfig.raiseTime) yield
          context.system.scheduler.scheduleOnce(FiniteDuration(raiseTime, SECONDS), self, HoldStart)

      context become calibration(calibrationType, startTime, recordCalibration,
        calibrationReadingList, zeroReading, endState, calibrationTimer)

      try {
        if (calibrationType.zero)
          triggerZeroCalibration(true)
        else
          triggerSpanCalibration(true)
      } catch {
        calibrationErrorHandler(instId, timerOpt, endState)
      }

    case HoldStart => {
      log.info(s"${self.path.name} => HoldStart")
      import scala.concurrent.duration._
      val calibrationTimer = {
        for (holdTime <- deviceConfig.holdTime) yield
          context.system.scheduler.scheduleOnce(Duration(holdTime, SECONDS), self, DownStart)
      }
      context become calibration(calibrationType, startTime, recordCalibration = true, calibrationReadingList,
        zeroReading, endState, calibrationTimer)
    }

    case DownStart =>
      log.info(s"${self.path.name} => DownStart (${calibrationReadingList.length})")
      import scala.concurrent.duration._

      if (calibrationType.auto && calibrationType.zero) {
        context become calibration(calibrationType, startTime, recordCalibration = false, calibrationReadingList,
          zeroReading, endState, None)
        self ! CalibrateEnd
      } else {
        collectorState = MonitorStatus.CalibrationResume
        instrumentOp.setState(instId, collectorState)
        val calibrationTimerOpt =
          for (downTime <- deviceConfig.downTime) yield
            context.system.scheduler.scheduleOnce(FiniteDuration(downTime, SECONDS), self, CalibrateEnd)
        context become calibration(calibrationType, startTime, recordCalibration = false, calibrationReadingList,
          zeroReading, endState, calibrationTimerOpt)
      }

      try {
        if (calibrationType.zero)
          triggerZeroCalibration(false)
        else
          triggerSpanCalibration(false)
      } catch {
        calibrationErrorHandler(instId, timerOpt, endState)
      }


    case rd: ReportData =>
      if (recordCalibration)
        context become calibration(calibrationType, startTime, recordCalibration, rd :: calibrationReadingList,
          zeroReading, endState, timerOpt)

    case CalibrateEnd =>
      Future {
        blocking {
          log.info(s"$self =>$calibrationType CalibrateEnd")

          val values = for {mt <- deviceConfig.monitorTypes.get} yield {
            val calibrations = calibrationReadingList.flatMap {
              reading =>
                reading.dataList(monitorTypeDB).filter {
                  _.mt == mt
                }.map { r => r.value }
            }

            if (calibrations.isEmpty) {
              log.warning(s"No calibration data for $mt")
              (mt, 0d)
            } else
              (mt, calibrations.sum / calibrations.length)
          }

          //For auto calibration, span will be executed after zero
          if (calibrationType.auto && calibrationType.zero) {
            for (v <- values)
              log.info(s"${v._1} zero calibration end. (${v._2})")

            if (deviceConfig.calibratorPurgeTime.isDefined) {
              collectorState = MonitorStatus.NormalStat
              instrumentOp.setState(instId, collectorState)
              context become calibration(AutoSpan, startTime, recordCalibration = false, List.empty[ReportData],
                values, endState, Some(purgeCalibrator()))
            } else {
              context become calibration(AutoSpan, startTime, recordCalibration = false, List.empty[ReportData],
                values, endState, None)
              self ! RaiseStart
            }
          } else {
            val endTime = DateTime.now()

            if (calibrationType.auto) {
              val zeroMap = zeroReading.toMap
              val spanMap = values.toMap

              for (mt <- deviceConfig.monitorTypes.get) {
                val zero = zeroMap.get(mt)
                val span = spanMap.get(mt)
                val spanStd = monitorTypeDB.map(mt).span
                val cal = Calibration(mt, startTime, endTime, zero, spanStd, span)
                calibrationOp.insertFuture(cal)
              }
            } else {
              val valueMap = values.toMap
              for (mt <- deviceConfig.monitorTypes.get) {
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

            log.info("All monitorTypes are calibrated.")
            collectorState = endState
            instrumentOp.setState(instId, collectorState)
            resetToNormal()
            context become normalReceive()
            log.info(s"$self => ${monitorStatusOp.map(collectorState).name}")
          }
        }
      }
  }

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
        for (reg <- modelReg.calibrationReg; master <- masterOpt) {
          master.setValue(BaseLocator.coilStatus(
            deviceConfig.slaveID.getOrElse(1), reg.zeroAddress), false)
          master.setValue(BaseLocator.coilStatus(
            deviceConfig.slaveID.getOrElse(1), reg.spanAddress), false)
        }
      }
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  def triggerZeroCalibration(v: Boolean): Unit = {

    deviceConfig.calibrateZeoDO foreach {
      doBit =>
        context.parent ! WriteDO(doBit, v)
    }

    deviceConfig.calibrateZeoSeq foreach {
      seq =>
        context.parent ! ExecuteSeq(seq, v)

    }

    if (!deviceConfig.skipInternalVault.contains(true)) {
      for (reg <- modelReg.calibrationReg; master <- masterOpt) {
        val locator = BaseLocator.coilStatus(deviceConfig.slaveID.getOrElse(1), reg.zeroAddress)
        master.setValue(locator, v)
      }
    }
  }

  def triggerSpanCalibration(v: Boolean): Unit = {
    deviceConfig.calibrateSpanDO foreach {
      doBit =>
        context.parent ! WriteDO(doBit, v)
    }

    deviceConfig.calibrateSpanSeq foreach {
      seq =>
        context.parent ! ExecuteSeq(seq, v)
    }

    if (!deviceConfig.skipInternalVault.contains(true)) {
      for (reg <- modelReg.calibrationReg; master <- masterOpt) {
        val locator = BaseLocator.coilStatus(deviceConfig.slaveID.getOrElse(1), reg.spanAddress)
        master.setValue(locator, v)
      }
    }
  }

  def purgeCalibrator(): Cancellable = {
    import scala.concurrent.duration._

    val purgeTime = deviceConfig.calibratorPurgeTime.get
    log.info(s"Purge calibrator. Delay start of calibration $purgeTime seconds")
    triggerCalibratorPurge(true)
    context.system.scheduler.scheduleOnce(Duration(purgeTime, SECONDS), self, RaiseStart)
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

  def regValueReporter(regValue: ModelRegValue)(recordCalibration: Boolean): Unit = {
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

    //Log Instrument state
    if (DateTime.now() > nextLoggingStatusTime) {
      //log.debug("Log instrument state")
      try {
        logInstrumentStatus(regValue)
      } catch {
        case _: Throwable =>
          log.error("Log instrument status failed")
      }
      nextLoggingStatusTime = nextLoggingStatusTime + 10.minute
      //log.debug(s"next logging time = $nextLoggingStatusTime")
    }

    oldModelReg = Some(regValue)
  }

  def logInstrumentStatus(regValue: ModelRegValue): Unit = {
    val isList = regValue.inputRegs.map {
      kv =>
        val k = kv._1
        val v = kv._2
        InstrumentStatusDB.Status(k.key, v)
    }
    val instStatus = InstrumentStatusDB.InstrumentStatus(DateTime.now(), instId, isList).excludeNaN
    instrumentStatusOp.log(instStatus)
  }

  private def getDataRegValue(regValue: ModelRegValue)(addr: Int): Option[(InstrumentStatusType, Float)] = {
    val dataRegOpt = (regValue.inputRegs ++ regValue.holdingRegs).find(r_idx => r_idx._1.addr == addr)

    for (dataReg <- dataRegOpt) yield
      dataReg
  }

  def reportData(regValue: ModelRegValue): Option[ReportData] = {
    val optValues: Seq[Option[(String, (InstrumentStatusType, Float))]] = {
      for (dataReg <- modelReg.dataRegs) yield {
        def passFilter(v: Double) =
          modelReg.filterRules.forall(rule =>
            if (rule.monitorType == dataReg.monitorType)
              rule.min < v && rule.max > v
            else
              true)

        for {rawValue <- getDataRegValue(regValue)(dataReg.address)
             v = rawValue._2 * dataReg.multiplier if passFilter(v)} yield
          (dataReg.monitorType, (rawValue._1, v))
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
    if (timerOpt.isDefined)
      timerOpt.get.cancel()

    for (master <- masterOpt)
      master.destroy()

  }
}