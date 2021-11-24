package models

import akka.actor._
import com.github.nscala_time.time.Imports._
import com.google.inject.assistedinject.Assisted
import models.AkDrv.{OpenCom, ReadRegister}
import models.Protocol.ProtocolParam
import play.api._

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

case class AkModelRegValue(inputRegs: Seq[(InstrumentStatusType, Float)],
                           modeRegs:Seq[(InstrumentStatusType, Boolean)],
                           warningRegs:Seq[(InstrumentStatusType, Boolean)])

class AkDrvCollector @Inject()(instrumentOp: InstrumentOp, monitorStatusOp: MonitorStatusOp,
                               alarmOp: AlarmOp, system: ActorSystem, monitorTypeOp: MonitorTypeOp,
                               calibrationOp: CalibrationOp, instrumentStatusOp: InstrumentStatusOp)
                              (@Assisted instId: String, @Assisted protocol: ProtocolParam, @Assisted modelReg: AkModelReg,
                               @Assisted deviceConfig: AkDeviceConfig) extends Actor {
  var timerOpt: Option[Cancellable] = None

  var (collectorState: String, instrumentStatusTypesOpt) = {
    val instList = instrumentOp.getInstrument(instId)
    if (instList.nonEmpty) {
      val inst: Instrument = instList(0)
      (inst.state, inst.statusType)
    } else
      (MonitorStatus.NormalStat, None)
  }

  val InputKey = "Input"
  val HoldingKey = "Holding"
  val ModeKey = "Mode"
  val WarnKey = "Warn"

  def probeInstrumentStatusType: Seq[InstrumentStatusType] = {
    Logger.info("Probing supported ak registers...")
    def probeReg(addr: Int, desc: String) = {
      try {
        val cmd = AkProtocol.AskRegCmd(deviceConfig.stationNo, deviceConfig.channelNum, addr).getCmd
        comm.os.write(cmd)
        comm.getAkResponse()
        true
      } catch {
        case ex: Throwable =>
          Logger.error(ex.getMessage, ex)
          Logger.info(s"$addr $desc is not supported.")
          false
      }
    }
    val inputRegStatusType =
      for { r <- modelReg.inputRegs if probeReg(r.addr, r.desc) }
        yield
          InstrumentStatusType(key = s"$InputKey${r.addr}", addr = r.addr, desc = r.desc, unit = "")

    val modeRegStatusType =
      for { r <- modelReg.modeRegs if probeReg(r.addr, r.desc) }
        yield
          InstrumentStatusType(key = s"$ModeKey${r.addr}", addr = r.addr, desc = r.desc, unit = "")

    val warningRegStatusType =
      for { r <- modelReg.warningRegs if probeReg(r.addr, r.desc) }
        yield
          InstrumentStatusType(key = s"$WarnKey${r.addr}", addr = r.addr, desc = r.desc, unit = "")

    Logger.info("Finish probing.")
    inputRegStatusType ++  modeRegStatusType ++ warningRegStatusType
  }

  def readReg(statusTypeList: List[InstrumentStatusType]) = {
    var inputs = Seq.empty[(InstrumentStatusType, Float)]
    var modes = Seq.empty[(InstrumentStatusType, Boolean)]
    var warnings = Seq.empty[(InstrumentStatusType, Boolean)]

    for {
      st_idx <- statusTypeList.zipWithIndex
      st = st_idx._1
      idx = st_idx._2
    } {
      if (st.key.startsWith(InputKey)) {
        val cmd = AkProtocol.AskRegCmd(deviceConfig.stationNo, deviceConfig.channelNum, st.addr).getCmd
        comm.os.write(cmd)
        val ret: AkProtocol.AkResponse = AkProtocol.handleAkResponse(comm.getAkResponse())
        if(ret.success)
          inputs = inputs:+((st, ret.value.toFloat))
      } else if (st.key.startsWith(HoldingKey)) {
        val cmd = AkProtocol.AskRegCmd(deviceConfig.stationNo, deviceConfig.channelNum, st.addr).getCmd
        comm.os.write(cmd)
        val ret: AkProtocol.AkResponse = AkProtocol.handleAkResponse(comm.getAkResponse())
        if(ret.success)
          modes = modes:+((st, ret.value.toBoolean))
      } else if (st.key.startsWith(ModeKey) || st.key.startsWith(WarnKey)) {
        val cmd = AkProtocol.AskRegCmd(deviceConfig.stationNo, deviceConfig.channelNum, st.addr).getCmd
        comm.os.write(cmd)
        val ret: AkProtocol.AkResponse = AkProtocol.handleAkResponse(comm.getAkResponse())
        if(ret.success)
          warnings = warnings:+((st, ret.value.toBoolean))
      } else {
        throw new Exception(s"Unexpected key ${st.key}")
      }
    }
    AkModelRegValue(inputs, modes, warnings)
  }

  var connected = false
  var oldModelReg: Option[AkModelRegValue] = None

  def receive = normalReceive

  import scala.concurrent.{Future, blocking}
  def readRegFuture(recordCalibration: Boolean) =
    Future {
      blocking {
        try {
          for(instrumentStatusTypes <- instrumentStatusTypesOpt){
            val regValues =
              if(needStatus)
                readReg(instrumentStatusTypes)
              else {
                val dataRegAddressList = modelReg.dataRegs.map(_.address)
                val readList = instrumentStatusTypes.filter(ist => dataRegAddressList.contains(ist.addr))
                readReg(readList)
              }
            regValueReporter(regValues)(recordCalibration)
          }
          connected = true
        } catch {
          case ex: Exception =>
            Logger.error(ex.getMessage, ex)
            if (connected)
              alarmOp.log(alarmOp.instStr(instId), alarmOp.Level.ERR, s"${ex.getMessage}")

            connected = false
        } finally {
          import scala.concurrent.duration._
          timerOpt = Some(system.scheduler.scheduleOnce(Duration(5, SECONDS), self, ReadRegister))
        }
      }
    }

  self ! OpenCom

  var comm: SerialComm = _

  def normalReceive(): Receive = {
    case OpenCom =>
      Future {
        blocking {
          try {
            comm = SerialComm.open(protocol.comPort.get)
            connected = true
            if (instrumentStatusTypesOpt.isEmpty) {
              val statusTypeList = probeInstrumentStatusType.toList
              if(modelReg.dataRegs.forall(reg=> statusTypeList.exists(statusType=> statusType.addr == reg.address))){
                // Data register must included in the list
                instrumentStatusTypesOpt = Some(probeInstrumentStatusType.toList)
                instrumentOp.updateStatusType(instId, instrumentStatusTypesOpt.get)
              }else
                throw new Exception("Probe register failed. Data register is not in there...");
            }
            import scala.concurrent.duration._
            timerOpt = Some(system.scheduler.scheduleOnce(Duration(10, SECONDS), self, ReadRegister))
          } catch {
            case ex: Exception =>
              Logger.error(ex.getMessage, ex)
              alarmOp.log(alarmOp.instStr(instId), alarmOp.Level.ERR, s"無法連接:${ex.getMessage}")
              import scala.concurrent.duration._

              system.scheduler.scheduleOnce(Duration(1, MINUTES), self, OpenCom)
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

    case ManualZeroCalibration(instId) =>

    case ManualSpanCalibration(instId) =>

    case ExecuteSeq(seq, on) =>
  }

  var nextLoggingStatusTime = {
    def getNextTime(period: Int) = {
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

  def needStatus: Boolean = DateTime.now() > nextLoggingStatusTime

  def regValueReporter(regValue: AkModelRegValue)(recordCalibration: Boolean) = {
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
        for(oldReg<-oldModelReg)
          if (oldReg.modeRegs(idx)._2 != enable)
            alarmOp.log(alarmOp.instStr(instId), alarmOp.Level.INFO, statusType.desc)

      }
    }

    for {
      r <- regValue.warningRegs.zipWithIndex
      statusType = r._1._1
      enable = r._1._2
      idx = r._2
    } {
      if (enable) {
        for(regValues <- oldModelReg){
          if (regValues.warningRegs(idx)._2 != enable)
            alarmOp.log(alarmOp.instStr(instId), alarmOp.Level.WARN, statusType.desc)
        }
      } else {
        for(regValues <- oldModelReg){
          if (regValues.warningRegs(idx)._2 != enable)
            alarmOp.log(alarmOp.instStr(instId), alarmOp.Level.INFO, s"${statusType.desc} 解除")
        }
      }
    }

    //Log Instrument state
    if (needStatus) {
      try {
        logInstrumentStatus(regValue)
      } catch {
        case _: Throwable =>
          Logger.error("Log instrument status failed")
      }
      nextLoggingStatusTime = nextLoggingStatusTime + 6.minute
    }

    oldModelReg = Some(regValue)
  }

  def logInstrumentStatus(regValue: AkModelRegValue) = {
    val isList = regValue.inputRegs.map {
      kv =>
        val k = kv._1
        val v = kv._2
        instrumentStatusOp.Status(k.key, v)
    }
    val instStatus = instrumentStatusOp.InstrumentStatus(DateTime.now(), instId, isList).excludeNaN
    instrumentStatusOp.log(instStatus)
  }

  def findDataRegIdx(regValue: AkModelRegValue)(addr: Int) = {
    val dataReg = regValue.inputRegs.zipWithIndex.find(r_idx => r_idx._1._1.addr == addr)
    if (dataReg.isEmpty) {
      Logger.warn("Cannot found Data register!")
      None
    } else
      Some(dataReg.get._2)
  }

  def reportData(regValue: AkModelRegValue) = {
    val optValues: Seq[Option[(String, (InstrumentStatusType, Float))]] = {
      for (dataReg <- modelReg.dataRegs) yield {
        for (idx <- findDataRegIdx(regValue)(dataReg.address)) yield
          (dataReg.monitorType, regValue.inputRegs(idx))
      }
    }

    val monitorTypeData = optValues.flatten.map(
      t=> MonitorTypeData(t._1, t._2._2.toDouble, collectorState))

    if(monitorTypeData.isEmpty)
      None
    else
      Some(ReportData(monitorTypeData.toList))
  }

  override def postStop(): Unit = {
    if (timerOpt.isDefined)
      timerOpt.get.cancel()

    if(comm != null)
      comm.close
  }
}