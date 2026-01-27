package models

import akka.actor._
import com.github.nscala_time.time.Imports
import com.google.inject.assistedinject.Assisted
import com.serotonin.modbus4j.locator.BaseLocator
import com.serotonin.modbus4j.serial.SerialPortWrapper
import models.ModelHelper._
import models.MultiCalibrator.TriggerVault
import models.Protocol.ProtocolParam
import models.TapiTxx.T700_STANDBY_SEQ
import play.api.Logger

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
                                   @Assisted("protocol") protocol: ProtocolParam) extends Actor {

  import DataCollectManager._
  import TapiTxxCollector._
  import com.serotonin.modbus4j._
  import com.serotonin.modbus4j.ip.IpParameters

  private val InputKey = "Input"
  private val Input64Key = "64Input"
  private val HoldingKey = "Holding"
  private val Holding64Key = "64Holding"
  private val ModeKey = "Mode"
  private val WarnKey = "Warn"


  self ! ConnectHost

  @volatile var timerOpt: Option[Cancellable] = None
  @volatile var masterOpt: Option[ModbusMaster] = None
  @volatile var (collectorState: String, instrumentStatusTypesOpt) = {
    val instList = instrumentOp.getInstrument(instId)
    if (instList.nonEmpty) {
      val inst: Instrument = instList.head
      (inst.state, inst.statusType)
    } else
      (MonitorStatus.NormalStat, None)
  }
  @volatile var connected = false
  @volatile var oldModelReg: Option[RegValueSet] = None
  @volatile var nextLoggingStatusTime: Imports.DateTime = getNextTime(30)

  val logger = Logger(this.getClass)

  logger.info(s"TcpModbusCollector $instId started.")


  def probeInstrumentStatusType: Seq[InstrumentStatusType] = {
    logger.info("Probing supported modbus registers...")
    import com.serotonin.modbus4j.code.DataType
    import com.serotonin.modbus4j.locator.BaseLocator

    def probeValueReg(addr: Int, desc: String, input: Boolean, count: Int): Boolean = {
      try {
        val locator =
          (input, count) match {
            case (true, 2) =>
              BaseLocator.inputRegister(deviceConfig.slaveID.getOrElse(1), addr, DataType.FOUR_BYTE_INT_SIGNED)
            case (true, 4) =>
              BaseLocator.inputRegister(deviceConfig.slaveID.getOrElse(1), addr, DataType.EIGHT_BYTE_INT_SIGNED)
            case (false, 2) =>
              BaseLocator.holdingRegister(deviceConfig.slaveID.getOrElse(1), addr, DataType.FOUR_BYTE_INT_SIGNED)
            case (false, 4) =>
              BaseLocator.holdingRegister(deviceConfig.slaveID.getOrElse(1), addr, DataType.EIGHT_BYTE_INT_SIGNED)

            case _ =>
              throw new Exception("Unsupported data type")
          }

        for (master <- masterOpt)
          master.getValue(locator)

        true
      } catch {
        case _: Throwable =>
          logger.info(s"Value $addr $desc is not supported.")
          false
      }
    }

    def probeModeReg(addr: Int, desc: String): Boolean = {
      try {
        val locator = BaseLocator.inputStatus(deviceConfig.slaveID.getOrElse(1), addr)
        masterOpt.get.getValue(locator)
        true
      } catch {
        case _: Throwable =>
          logger.info(s"Mode $addr $desc is not supported.")
          false
      }
    }


    val input =
      for (r <- modelReg.inputs if probeValueReg(r.addr, r.desc, input = true, count = 2)) yield
        InstrumentStatusType(key = s"$InputKey${r.addr}", addr = r.addr, desc = r.desc, unit = r.unit)

    val input64 =
      for (r <- modelReg.input64 if probeValueReg(r.addr, r.desc, input = true, count = 4))
        yield InstrumentStatusType(key = s"$Input64Key${r.addr}", addr = r.addr, desc = r.desc, unit = r.unit)

    val holding =
      for (r <- modelReg.holding if probeValueReg(r.addr, r.desc, input = false, count = 2))
        yield InstrumentStatusType(key = s"$HoldingKey${r.addr}", addr = r.addr, desc = r.desc, unit = r.unit)

    val holding64 =
      for (r <- modelReg.holding64 if probeValueReg(r.addr, r.desc, input = false, count = 2))
        yield InstrumentStatusType(key = s"$Holding64Key${r.addr}", addr = r.addr, desc = r.desc, unit = r.unit)

    val modes =
      for (r <- modelReg.modes if probeModeReg(r.addr, r.desc))
        yield InstrumentStatusType(key = s"$ModeKey${r.addr}", addr = r.addr, desc = r.desc, unit = "-")

    val warns =
      for (r <- modelReg.warnings if probeModeReg(r.addr, r.desc))
        yield InstrumentStatusType(key = s"$WarnKey${r.addr}", addr = r.addr, desc = r.desc, unit = "-")

    logger.info("Finish probing.")
    input ++ input64 ++ holding ++ holding64 ++ modes ++ warns
  }

  def readReg(statusTypeList: List[InstrumentStatusType]): RegValueSet = {
    import com.serotonin.modbus4j.BatchRead
    val batch = new BatchRead[Integer]

    import com.serotonin.modbus4j.locator.BaseLocator

    for ((st, idx) <- statusTypeList.zipWithIndex) {
      if (st.key.startsWith(Input64Key)) {
        logger.info(s"sending input64 request ${st.addr}")
        batch.addLocator(idx, BaseLocator.inputRegister(deviceConfig.slaveID.getOrElse(1), st.addr, modelReg.byteSwapMode64))
      } else if (st.key.startsWith(InputKey)) {
        batch.addLocator(idx, BaseLocator.inputRegister(deviceConfig.slaveID.getOrElse(1), st.addr, modelReg.byteSwapMode))
      } else if (st.key.startsWith(Holding64Key)) {
        logger.info(s"sending holding64 request ${st.addr}")
        batch.addLocator(idx, BaseLocator.holdingRegister(deviceConfig.slaveID.getOrElse(1), st.addr, modelReg.byteSwapMode64))
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

    def getRegValues(key: String): List[RegDouble] =
      for ((ist, idx) <- statusTypeList.zipWithIndex if ist.key.startsWith(key)) yield {
        try {
          RegDouble(ist, results.getFloatValue(idx) * modelReg.multiplier)
        } catch {
          case ex: Exception =>
            logger.error(s"failed at $idx")
            throw ex
        }
      }

    val inputs = getRegValues(InputKey)
    val holdings = getRegValues(HoldingKey)

    def getModeValues(key: String): List[RegBool] =
      for {
        st_idx <- statusTypeList.zipWithIndex if st_idx._1.key.startsWith(key)
        idx = st_idx._2
      } yield RegBool(st_idx._1, results.getValue(idx).asInstanceOf[Boolean])

    val modes = getModeValues(ModeKey)
    val warns = getModeValues(WarnKey)

    def getReg64Values(key: String): List[RegDouble] =
      for {
        st_idx <- statusTypeList.zipWithIndex if st_idx._1.key.startsWith(key)
        idx = st_idx._2
      } yield {
        try {
          RegDouble(st_idx._1, results.getDoubleValue(idx) * modelReg.multiplier)
        } catch {
          case ex: Exception =>
            logger.error(s"failed at $idx")
            throw ex
        }
      }

    val input64s = getReg64Values(Input64Key)
    val holding64s = getReg64Values(Holding64Key)

    RegValueSet(inputs, holdings, modes, warns, input64s, holding64s)
  }

  import scala.concurrent.{Future, blocking}

  def receive(): Receive = normalReceive()

  def readRegFuture(recordCalibration: Boolean): Future[Unit] =
    Future {
      blocking {
        try {
          for (instStatusTypes <- instrumentStatusTypesOpt)
            regValueReporter(readReg(instStatusTypes))(recordCalibration)

          connected = true
        } catch {
          case ex: Exception =>
            logger.error(s"$instId:$desc=>${ex.getMessage}", ex)
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
      logger.error("There is no monitor type for calibration.")
    else if (!connected)
      logger.error("Cannot calibration before connected.")
    else
      startCalibration(calibrationType, deviceConfig.monitorTypes.get)
  }

  def normalReceive(): Receive = {
    case ConnectHost =>
      Future {
        blocking {
          var master: ModbusMaster = null
          try {
            val modbusFactory = new ModbusFactory()
            if (protocol.protocol == Protocol.tcp) {
              val ipParameters = new IpParameters()
              ipParameters.setHost(protocol.host.get);
              ipParameters.setPort(502);
              logger.info(s"${self.toString()}: connect ${protocol.host.get}")
              master = modbusFactory.createTcpMaster(ipParameters, true)
              master.setRetries(1)
              master.setConnected(true)
              master.init();
              masterOpt = Some(master)
            } else {
              if (masterOpt.isEmpty) {
                logger.info(protocol.toString)
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
              if (modelReg.data.forall(reg => statusTypeList.exists(statusType => statusType.addr == reg.address))) {
                // Data register must include it in the list
                instrumentStatusTypesOpt = Some(probeInstrumentStatusType.toList)
                instrumentOp.updateStatusType(instId, instrumentStatusTypesOpt.get)
              } else
                throw new Exception("Probe register failed. Data register is not in there...")
            }
            import scala.concurrent.duration._
            timerOpt = Some(context.system.scheduler.scheduleOnce(Duration(3, SECONDS), self, ReadRegister))
          } catch {
            case ex: Exception =>
              logger.error(s"$instId:${desc}=>${ex.getMessage}", ex)
              alarmOp.log(alarmOp.instrumentSrc(instId), Alarm.Level.ERR, s"無法連接:${ex.getMessage}")

              if (master != null)
                master.destroy()

              import scala.concurrent.duration._

              context.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectHost)
          }
        }
      }

    case ReadRegister =>
      readRegFuture(false)

    case SetState(id, state) =>
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
      logger.warn(s"ExecuteSeq($seq, $on) is not implemented.")

    case TriggerVault(zero, on) =>
      logger.info(s"TriggerVault($zero, $on)")
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

  def startCalibration(calibrationType: CalibrationType, monitorTypes: List[String]): Unit = {
    logger.info(s"start calibrating ${monitorTypes.mkString(",")}")
    import com.github.nscala_time.time.Imports._
    val endState = collectorState

    if (!calibrationType.zero &&
      deviceConfig.calibratorPurgeTime.isDefined && deviceConfig.calibratorPurgeTime.get != 0) {
      context become calibration(calibrationType, DateTime.now, recordCalibration = false, List.empty[ReportData],
        List.empty[(String, Double)], endState, Some(purgeCalibrator()))
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
      logger.error("unexpected ConnectHost msg")

    case ReadRegister =>
      readRegFuture(recordCalibration)

    case SetState(_, targetState) =>
      if (targetState == MonitorStatus.ZeroCalibrationStat) {
        logger.info("Already in calibration. Ignore it")
      } else if (targetState == MonitorStatus.NormalStat) {
        logger.info("Cancel calibration.")
        for (timer <- timerOpt)
          timer.cancel()

        collectorState = targetState
        instrumentOp.setState(instId, targetState)
        resetToNormal()
        context become normalReceive()
      } else {
        logger.info(s"During calibration ignore $targetState state change")
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
      logger.info(s"${self.path.name} => HoldStart")
      import scala.concurrent.duration._
      val calibrationTimer = {
        for (holdTime <- deviceConfig.holdTime) yield
          context.system.scheduler.scheduleOnce(Duration(holdTime, SECONDS), self, DownStart)
      }
      context become calibration(calibrationType, startTime, recordCalibration = true, calibrationReadingList,
        zeroReading, endState, calibrationTimer)
    }

    case DownStart =>
      logger.info(s"${self.path.name} => DownStart (${calibrationReadingList.length})")
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
          logger.info(s"$self =>$calibrationType CalibrateEnd")

          val values = for {mt <- deviceConfig.monitorTypes.get} yield {
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

            logger.info("All monitorTypes are calibrated.")
            collectorState = endState
            instrumentOp.setState(instId, collectorState)
            resetToNormal()
            context become normalReceive()
            logger.info(s"$self => ${monitorStatusOp.map(collectorState).name}")
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
    logger.info(s"Purge calibrator. Delay start of calibration $purgeTime seconds")
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

  def regValueReporter(regValue: RegValueSet)(recordCalibration: Boolean): Unit = {
    for (report <- reportData(regValue)) {
      context.parent ! report
      if (recordCalibration)
        self ! report
    }

    for ((r, idx) <- regValue.modes.zipWithIndex if r.value) {
      if (oldModelReg.isEmpty || oldModelReg.get.modes(idx).value != r.value)
        alarmOp.log(alarmOp.instrumentSrc(instId), Alarm.Level.INFO, r.ist.desc)
    }

    for ((r, idx) <- regValue.warnings.zipWithIndex) {
      if (r.value) {
        if (oldModelReg.isEmpty || oldModelReg.get.warnings(idx).value != r.value)
          alarmOp.log(alarmOp.instrumentSrc(instId), Alarm.Level.WARN, r.ist.desc)
      } else {
        if (oldModelReg.isDefined && oldModelReg.get.warnings(idx).value != r.value)
          alarmOp.log(alarmOp.instrumentSrc(instId), Alarm.Level.INFO, s"${r.ist.desc} 解除")
      }
    }

    //Log Instrument state
    if (DateTime.now() > nextLoggingStatusTime) {
      //log.debug("Log instrument state")
      try {
        logInstrumentStatus(regValue)
      } catch {
        case _: Throwable =>
          logger.error("Log instrument status failed")
      }
      nextLoggingStatusTime = nextLoggingStatusTime + 30.minute
      //log.debug(s"next logging time = $nextLoggingStatusTime")
    }

    oldModelReg = Some(regValue)
  }

  def logInstrumentStatus(regValue: RegValueSet): Unit = {
    val isList = regValue.inputs.map {
      reg =>
        InstrumentStatusDB.Status(reg.ist.key, reg.value)
    }
    val instStatus = InstrumentStatusDB.InstrumentStatus(DateTime.now(), instId, isList).excludeNaN
    instrumentStatusOp.log(instStatus)
  }

  private def getDataRegValue(regValue: RegValueSet)(addr: Int): Option[RegDouble] =
    regValue.dataList.find(reg => reg.ist.addr == addr)

  def reportData(regValue: RegValueSet): Option[ReportData] = {
    val monitorTypeData = modelReg.data flatMap {
      dataReg =>
        def passFilter(v: Double): Boolean =
          modelReg.filterRules.forall(rule =>
            if (rule.monitorType == dataReg.monitorType)
              rule.min < v && rule.max > v
            else
              true)

        for {rawValue <- getDataRegValue(regValue)(dataReg.address)
             v = rawValue.value * dataReg.multiplier if passFilter(v)} yield
          MonitorTypeData(dataReg.monitorType, v, collectorState)
    }

    if (monitorTypeData.isEmpty)
      None
    else
      Some(ReportData(monitorTypeData))
  }

  override def postStop(): Unit = {
    if (timerOpt.isDefined)
      timerOpt.get.cancel()

    for (master <- masterOpt)
      master.destroy()

  }
}