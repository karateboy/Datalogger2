package models

import akka.actor._
import com.google.inject.assistedinject.Assisted
import com.serotonin.modbus4j.locator.BaseLocator
import com.serotonin.modbus4j.serial.SerialPortWrapper
import models.ModelHelper._
import models.Protocol.ProtocolParam
import models.mongodb.{AlarmOp, CalibrationOp, InstrumentStatusOp}
import play.api._

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

class TcpModbusCollector @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                                   alarmOp: AlarmDB, system: ActorSystem, monitorTypeOp: MonitorTypeDB,
                                   calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                                  (@Assisted("instId") instId: String, @Assisted("desc") desc: String, @Assisted modelReg: TcpModelReg,
                                   @Assisted deviceConfig: DeviceConfig, @Assisted("protocol") protocol: ProtocolParam) extends Actor {
  val InputKey = "Input"

  import TapiTxxCollector._
  import com.serotonin.modbus4j._
  import com.serotonin.modbus4j.ip.IpParameters
  val HoldingKey = "Holding"
  val ModeKey = "Mode"

  self ! ConnectHost
  val WarnKey = "Warn"
  var timerOpt: Option[Cancellable] = None
  var masterOpt: Option[ModbusMaster] = None
  var (collectorState: String, instrumentStatusTypesOpt) = {
    val instList = instrumentOp.getInstrument(instId)
    if (instList.nonEmpty) {
      val inst: Instrument = instList(0)
      (inst.state, inst.statusType)
    } else
      (MonitorStatus.NormalStat, None)
  }
  var connected = false
  var oldModelReg: Option[ModelRegValue] = None
  var nextLoggingStatusTime = {
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
    //Logger.debug(s"$instId next logging time= $nextTime")
    nextTime
  }

  def probeInstrumentStatusType: Seq[InstrumentStatusType] = {
    Logger.info("Probing supported modbus registers...")
    import com.serotonin.modbus4j.code.DataType
    import com.serotonin.modbus4j.locator.BaseLocator

    def probeInputReg(addr: Int, desc: String) = {
      try {
        val locator = if (protocol.protocol == Protocol.tcp)
          BaseLocator.inputRegister(deviceConfig.slaveID, addr, DataType.FOUR_BYTE_FLOAT)
        else
          BaseLocator.inputRegister(deviceConfig.slaveID, addr, DataType.FOUR_BYTE_FLOAT_SWAPPED)
        masterOpt.get.getValue(locator)
        true
      } catch {
        case ex: Throwable =>
          Logger.error(s"${instId}:${desc}=> ${ex.getMessage}", ex)
          Logger.info(s"$addr $desc is not supported.")
          false
      }
    }

    def probeHoldingReg(addr: Int, desc: String) = {
      try {
        val locator = BaseLocator.holdingRegister(deviceConfig.slaveID, addr, DataType.FOUR_BYTE_FLOAT)
        masterOpt.get.getValue(locator)
        true
      } catch {
        case _: Throwable =>
          Logger.info(s"${instId}:${desc}=>$addr $desc is not supported.")
          false
      }
    }

    def probeInputStatus(addr: Int, desc: String) = {
      try {
        val locator = BaseLocator.inputStatus(deviceConfig.slaveID, addr)
        masterOpt.get.getValue(locator)
        true
      } catch {
        case _: Throwable =>
          Logger.info(s"${instId}:${desc}=>$addr $desc is not supported.")
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

    Logger.info("Finish probing.")
    inputRegStatusType ++ holdingRegStatusType ++ modeRegStatusType ++ warnRegStatusType
  }

  def readReg(statusTypeList: List[InstrumentStatusType]) = {
    import com.serotonin.modbus4j.BatchRead
    val batch = new BatchRead[Integer]

    import com.serotonin.modbus4j.locator.BaseLocator

    for {
      st_idx <- statusTypeList.zipWithIndex
      st = st_idx._1
      idx = st_idx._2
    } {
      if (st.key.startsWith(InputKey)) {
        batch.addLocator(idx, BaseLocator.inputRegister(deviceConfig.slaveID, st.addr, modelReg.byteSwapMode))
      } else if (st.key.startsWith(HoldingKey)) {
        batch.addLocator(idx, BaseLocator.holdingRegister(deviceConfig.slaveID, st.addr, modelReg.byteSwapMode))
      } else if (st.key.startsWith(ModeKey) || st.key.startsWith(WarnKey)) {
        batch.addLocator(idx, BaseLocator.inputStatus(deviceConfig.slaveID, st.addr))
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
            Logger.error(s"failed at ${idx}")
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

  def receive(): Receive = normalReceive

  def readRegFuture(recordCalibration: Boolean) =
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
            Logger.error(s"${instId}:${desc}=>${ex.getMessage}", ex)
            if (connected)
              alarmOp.log(alarmOp.instrumentSrc(instId), alarmOp.Level.ERR, s"${ex.getMessage}")

            connected = false
        } finally {
          import scala.concurrent.duration._
          timerOpt = if (protocol.protocol == Protocol.tcp)
            Some(system.scheduler.scheduleOnce(Duration(2, SECONDS), self, ReadRegister))
          else
            Some(system.scheduler.scheduleOnce(Duration(3, SECONDS), self, ReadRegister))
        }
      }
    }

  def executeCalibration(calibrationType: CalibrationType) {
    if (deviceConfig.monitorTypes.isEmpty)
      Logger.error("There is no monitor type for calibration.")
    else if (!connected)
      Logger.error("Cannot calibration before connected.")
    else {
      Future {
        blocking {
          startCalibration(calibrationType, deviceConfig.monitorTypes.get)
        }
      } onFailure ({
        case ex: Throwable =>
          ModelHelper.logInstrumentError(instId, s"${self.path.name}: ${ex.getMessage}. ", ex)
      })
    }
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
              Logger.info(s"${self.toString()}: connect ${protocol.host.get}")
              val master = modbusFactory.createTcpMaster(ipParameters, true)
              master.setTimeout(4000)
              master.setRetries(1)
              master.setConnected(true)
              master.init();
              masterOpt = Some(master)
            } else {
              if (masterOpt.isEmpty) {
                Logger.info(protocol.toString)
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
            timerOpt = Some(system.scheduler.scheduleOnce(Duration(3, SECONDS), self, ReadRegister))
          } catch {
            case ex: Exception =>
              Logger.error(s"${instId}:${desc}=>${ex.getMessage}", ex)
              alarmOp.log(alarmOp.instrumentSrc(instId), alarmOp.Level.ERR, s"無法連接:${ex.getMessage}")
              import scala.concurrent.duration._

              system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectHost)
          }
        }
      }

    case ReadRegister =>
      readRegFuture(false)

    case SetState(id, state) =>
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
  }

  // FIXME not implemented
  def executeSeq(str: String, bool: Boolean): Unit = ???

  def startCalibration(calibrationType: CalibrationType, monitorTypes: List[String]) {
    import scala.concurrent.duration._

    Logger.info(s"start calibrating ${monitorTypes.mkString(",")}")
    val timer = if (!calibrationType.zero &&
      deviceConfig.calibratorPurgeTime.isDefined && deviceConfig.calibratorPurgeTime.get != 0)
      purgeCalibrator
    else
      system.scheduler.scheduleOnce(Duration(1, SECONDS), self, RaiseStart)

    import com.github.nscala_time.time.Imports._
    val endState = collectorState
    context become calibration(calibrationType, DateTime.now, false, List.empty[ReportData],
      List.empty[(String, Double)], endState, timer)
  }

  import com.github.nscala_time.time.Imports._

  def calibrationErrorHandler(id: String, timer: Cancellable, endState: String): PartialFunction[Throwable, Unit] = {
    case ex: Exception =>
      timer.cancel()
      logInstrumentError(id, s"${self.path.name}: ${ex.getMessage}. ", ex)
      resetToNormal
      instrumentOp.setState(id, endState)
      collectorState = endState
      context become normalReceive
  }

  def calibration(calibrationType: CalibrationType, startTime: DateTime, recordCalibration: Boolean, calibrationReadingList: List[ReportData],
                  zeroReading: List[(String, Double)],
                  endState: String, timer: Cancellable): Receive = {
    case ConnectHost =>
      Logger.error("unexpected ConnectHost msg")

    case ReadRegister =>
      readRegFuture(recordCalibration)

    case SetState(id, targetState) =>
      if (targetState == MonitorStatus.ZeroCalibrationStat) {
        Logger.info("Already in calibration. Ignore it")
      } else if (targetState == MonitorStatus.NormalStat) {
        Logger.info("Cancel calibration.")
        timer.cancel()
        collectorState = targetState
        instrumentOp.setState(instId, targetState)
        resetToNormal
        context become normalReceive
      }
      Logger.info(s"$self => ${monitorStatusOp.map(collectorState).desp}")

    case RaiseStart =>
      Future {
        blocking {
          collectorState =
            if (calibrationType.zero)
              MonitorStatus.ZeroCalibrationStat
            else
              MonitorStatus.SpanCalibrationStat

          instrumentOp.setState(instId, collectorState)

          Logger.info(s"${self.path.name} => RaiseStart")
          import scala.concurrent.duration._
          if (calibrationType.zero) {
            triggerZeroCalibration(true)
          } else {
            triggerSpanCalibration(true)
          }
          val calibrationTimer = system.scheduler.scheduleOnce(Duration(deviceConfig.raiseTime.get, SECONDS), self, HoldStart)
          context become calibration(calibrationType, startTime, recordCalibration,
            calibrationReadingList, zeroReading, endState, calibrationTimer)
        }
      } onFailure (calibrationErrorHandler(instId, timer, endState))

    case HoldStart => {
      Logger.info(s"${self.path.name} => HoldStart")
      import scala.concurrent.duration._
      val calibrationTimer = system.scheduler.scheduleOnce(Duration(deviceConfig.holdTime.get, SECONDS), self, DownStart)
      context become calibration(calibrationType, startTime, true, calibrationReadingList,
        zeroReading, endState, calibrationTimer)
    }

    case DownStart =>
      Future {
        blocking {
          Logger.info(s"${self.path.name} => DownStart (${calibrationReadingList.length})")
          import scala.concurrent.duration._
          if (calibrationType.zero) {
            triggerZeroCalibration(false)
          } else {
            triggerSpanCalibration(false)
          }

          val calibrationTimer =
            if (calibrationType.auto && calibrationType.zero) {
              // Auto zero calibration will jump to end immediately
              system.scheduler.scheduleOnce(Duration(1, SECONDS), self, CalibrateEnd)
            } else {
              collectorState = MonitorStatus.CalibrationResume
              instrumentOp.setState(instId, collectorState)
              system.scheduler.scheduleOnce(Duration(deviceConfig.downTime.get, SECONDS), self, CalibrateEnd)
            }
          context become calibration(calibrationType, startTime, false, calibrationReadingList,
            zeroReading, endState, calibrationTimer)
        }
      } onFailure (calibrationErrorHandler(instId, timer, endState))

    case rd: ReportData =>
      //Logger.debug(s"calibrationReadingList #=${calibrationReadingList.length}")
      context become calibration(calibrationType, startTime, recordCalibration, rd :: calibrationReadingList,
        zeroReading, endState, timer)

    case CalibrateEnd =>
      Future {
        blocking {
          Logger.info(s"$self =>$calibrationType CalibrateEnd")

          val values = for {mt <- deviceConfig.monitorTypes.get} yield {
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

            val raiseStartTimer = if (deviceConfig.calibratorPurgeTime.isDefined) {
              collectorState = MonitorStatus.NormalStat
              instrumentOp.setState(instId, collectorState)
              purgeCalibrator
            } else {
              import scala.concurrent.duration._
              system.scheduler.scheduleOnce(Duration(1, SECONDS), self, RaiseStart)
            }

            context become calibration(AutoSpan, startTime, false, List.empty[ReportData],
              values.toList, endState, raiseStartTimer)
          } else {
            val endTime = DateTime.now()
            val duration = new Duration(startTime, endTime)

            if (calibrationType.auto) {
              val zeroMap = zeroReading.toMap
              val spanMap = values.toMap

              for (mt <- deviceConfig.monitorTypes.get) {
                val zero = zeroMap.get(mt)
                val span = spanMap.get(mt)
                val spanStd = monitorTypeOp.map(mt).span
                val cal = Calibration(mt, startTime, endTime, zero, spanStd, span)
                calibrationOp.insert(cal)
              }
            } else {
              val valueMap = values.toMap
              for (mt <- deviceConfig.monitorTypes.get) {
                val values = valueMap.get(mt)
                val cal =
                  if (calibrationType.zero)
                    Calibration(mt, startTime, endTime, values, None, None)
                  else {
                    val spanStd = monitorTypeOp.map(mt).span
                    Calibration(mt, startTime, endTime, None, spanStd, values)
                  }
                calibrationOp.insert(cal)
              }
            }

            Logger.info("All monitorTypes are calibrated.")
            collectorState = endState
            instrumentOp.setState(instId, collectorState)
            resetToNormal
            context become normalReceive
            Logger.info(s"$self => ${monitorStatusOp.map(collectorState).desp}")
          }
        }
      }
  }

  def resetToNormal {
    try {
      deviceConfig.calibrateZeoDO map {
        doBit =>
          context.parent ! WriteDO(doBit, false)
      }

      deviceConfig.calibrateSpanSeq map {
        seq =>
          context.parent ! ExecuteSeq(seq, false)
      }

      if (deviceConfig.skipInternalVault != Some(true)) {
        for (reg <- modelReg.calibrationReg) {
          masterOpt.get.setValue(BaseLocator.coilStatus(
            deviceConfig.slaveID, reg.zeroAddress), false)
          masterOpt.get.setValue(BaseLocator.coilStatus(
            deviceConfig.slaveID, reg.spanAddress), false)
        }
      }
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  def triggerZeroCalibration(v: Boolean) {
    try {
      deviceConfig.calibrateZeoDO map {
        doBit =>
          context.parent ! WriteDO(doBit, v)
      }

      deviceConfig.calibrateZeoSeq map {
        seq =>
          context.parent ! ExecuteSeq(seq, v)

      }

      if (deviceConfig.skipInternalVault != Some(true)) {
        for (reg <- modelReg.calibrationReg) {
          val locator = BaseLocator.coilStatus(deviceConfig.slaveID, reg.zeroAddress)
          masterOpt.get.setValue(locator, v)
        }
      }
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  def triggerSpanCalibration(v: Boolean) {
    try {
      deviceConfig.calibrateSpanDO map {
        doBit =>
          context.parent ! WriteDO(doBit, v)
      }

      deviceConfig.calibrateSpanSeq map {
        seq =>
          context.parent ! ExecuteSeq(seq, v)
      }

      if (deviceConfig.skipInternalVault != Some(true)) {
        for (reg <- modelReg.calibrationReg) {
          val locator = BaseLocator.coilStatus(deviceConfig.slaveID, reg.spanAddress)
          masterOpt.get.setValue(locator, v)
        }

      }
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  def purgeCalibrator() = {
    import scala.concurrent.duration._

    val purgeTime = deviceConfig.calibratorPurgeTime.get
    Logger.info(s"Purge calibrator. Delay start of calibration $purgeTime seconds")
    triggerCalibratorPurge(true)
    system.scheduler.scheduleOnce(Duration(purgeTime + 1, SECONDS), self, RaiseStart)
  }

  def triggerCalibratorPurge(v: Boolean) {
    try {
      for (seq <- deviceConfig.calibratorPurgeSeq) {
        context.parent ! ExecuteSeq(seq, v)
      }
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  def regValueReporter(regValue: ModelRegValue)(recordCalibration: Boolean) = {
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

    //Log Instrument state
    if (DateTime.now() > nextLoggingStatusTime) {
      //Logger.debug("Log instrument state")
      try {
        logInstrumentStatus(regValue)
      } catch {
        case _: Throwable =>
          Logger.error("Log instrument status failed")
      }
      nextLoggingStatusTime = nextLoggingStatusTime + 10.minute
      //Logger.debug(s"next logging time = $nextLoggingStatusTime")
    }

    oldModelReg = Some(regValue)
  }

  def logInstrumentStatus(regValue: ModelRegValue) = {
    val isList = regValue.inputRegs.map {
      kv =>
        val k = kv._1
        val v = kv._2
        instrumentStatusOp.Status(k.key, v)
    }
    val instStatus = instrumentStatusOp.InstrumentStatus(DateTime.now(), instId, isList).excludeNaN
    instrumentStatusOp.log(instStatus)
  }

  def getDataRegValue(regValue: ModelRegValue)(addr: Int): Option[(InstrumentStatusType, Float)] = {
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

    if (masterOpt.isDefined)
      masterOpt.get.destroy()
  }
}