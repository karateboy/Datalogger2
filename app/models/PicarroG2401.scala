package models

import akka.actor.{Actor, ActorSystem}
import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam
import play.api.Logger

import javax.inject.Inject
import scala.concurrent.{Future, blocking}
import scala.concurrent.ExecutionContext.Implicits.global

object PicarroG2401 extends AbstractDrv(_id = "picarroG2401", desp = "Picarro G2401",
  protocols = List(Protocol.serial)) {
  val predefinedIST = List(
    InstrumentStatusType(key = "CavityPressure", addr = 0, desc = "Cavity Pressure", ""),
    InstrumentStatusType(key = "CavityTemp", addr = 1, desc = "Cavity Temperature", ""),
    InstrumentStatusType(key = "DasTemp", addr = 2, desc = "das temp", ""),
    InstrumentStatusType(key = "EtalonTemp", addr = 3, desc = "etalon temp", ""),
    InstrumentStatusType(key = "WarmBoxTemp", addr = 4, desc = "warm box temp", ""),
    InstrumentStatusType(key = "Species", addr = 5, desc = "species", ""),
    InstrumentStatusType(key = "MpvPosition", addr = 6, desc = "mpv position", ""),
    InstrumentStatusType(key = "OutletValve", addr = 7, desc = "Outlet Valve", ""),
    InstrumentStatusType(key = "SolenoidValve", addr = 8, desc = "Solenoid Valve", ""),
    InstrumentStatusType(key = "N2O", addr = 9, desc = "N2O", ""),
    InstrumentStatusType(key = "N2O_dry", addr = 10, desc = "N2O dry", ""),
    InstrumentStatusType(key = "N2O_dry_30s", addr = 11, desc = "N2O dry 30s", ""),
    InstrumentStatusType(key = "N2O_dry_1min", addr = 12, desc = "N2O dry 1min", ""),
    InstrumentStatusType(key = "N2O_dry_5min", addr = 13, desc = "N2O dry 5min", ""),
    InstrumentStatusType(key = "CO2", addr = 14, desc = "CO2", ""),
    InstrumentStatusType(key = "CO2_dry", addr = 15, desc = "CO2 dry", ""),
    InstrumentStatusType(key = "CH4", addr = 16, desc = "CH4", ""),
    InstrumentStatusType(key = "CH4_dry", addr = 17, desc = "CH4 dry", ""),
    InstrumentStatusType(key = "H2O", addr = 18, desc = "H2O", ""),
    InstrumentStatusType(key = "NH3", addr = 19, desc = "NH3", ""),
    InstrumentStatusType(key = "Chemdetect", addr = 20, desc = "Chemdetect", "")
  )

  val dataAddress = List(9, 10) ++ Range(14, 20)

  override def getMonitorTypes(param: String): List[String] = {
    val ret =
    for(i<-dataAddress) yield
      predefinedIST(i).key

    ret.toList
  }


  override def getDataRegList: List[DataReg] =
    predefinedIST.filter(p => dataAddress.contains(p.addr)).map {
      ist =>
        DataReg(monitorType = ist.key, ist.addr, multiplier = 1)
    }

  trait Factory {
    def apply(@Assisted("instId") instId: String, @Assisted("desc") desc: String, @Assisted("config") config: DeviceConfig,
              @Assisted("protocolParam") protocol: ProtocolParam): Actor
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef): Actor = {
    val f2 = f.asInstanceOf[PicarroG2401.Factory]
    val config = validateParam(param)
    f2(id, desc = super.description, config, protocol)
  }
}

class PicarroG2401Collector @Inject()(instrumentOp: InstrumentOp, monitorStatusOp: MonitorStatusOp,
                                      alarmOp: AlarmOp, system: ActorSystem, monitorTypeOp: MonitorTypeOp,
                                      calibrationOp: CalibrationOp, instrumentStatusOp: InstrumentStatusOp)
                                     (@Assisted("instId") instId: String, @Assisted("desc") desc: String,
                                      @Assisted("config") deviceConfig: DeviceConfig,
                                      @Assisted("protocolParam") protocolParam: ProtocolParam)
  extends AbstractCollector(instrumentOp: InstrumentOp, monitorStatusOp: MonitorStatusOp,
    alarmOp: AlarmOp, system: ActorSystem, monitorTypeOp: MonitorTypeOp,
    calibrationOp: CalibrationOp, instrumentStatusOp: InstrumentStatusOp)(instId, desc, deviceConfig, protocolParam) {

  import PicarroG2401._

  override def probeInstrumentStatusType: Seq[InstrumentStatusType] = predefinedIST

  override def readReg(statusTypeList: List[InstrumentStatusType]): Future[Option[ModelRegValue2]] =
    Future {
      blocking {
        val ret = {
          for (serial <- serialOpt) yield {
            val cmd = "_Meas_GetConc\r"
            val bytes = cmd.getBytes("UTF-8")
            serial.port.writeBytes(bytes)
            val resp = serial.getMessageUntilCrWithTimeout(2)
            if (resp.nonEmpty) {
              val tokens = resp(0).split(";")
              if (tokens.length != predefinedIST.length)
                Logger.error(s"Data length ${tokens.length} != ${predefinedIST.length}")

              val inputs =
                for (ist <- predefinedIST if ist.addr < tokens.length) yield {
                  val v = try {
                    tokens(ist.addr).toDouble
                  } catch {
                    case _: Throwable =>
                      0d
                  }
                  (ist, v)
                }
              Some(ModelRegValue2(inputRegs = inputs,
                modeRegs = List.empty[(InstrumentStatusType, Boolean)],
                warnRegs = List.empty[(InstrumentStatusType, Boolean)]))
            } else
              None
          }
        }
        ret.flatten
      }
    }


  var serialOpt: Option[SerialComm] = None

  override def connectHost: Unit = {
    serialOpt =
      Some(SerialComm.open(protocolParam.comPort.get, protocolParam.speed.getOrElse(19200)))
  }

  override def getDataRegList: Seq[DataReg] = PicarroG2401.getDataRegList

  override def getCalibrationReg: Option[CalibrationReg] = Some(CalibrationReg(0, 1))

  override def setCalibrationReg(address: Int, on: Boolean): Unit = {
    for (serial <- serialOpt) {
      if (on) {
        if(address == 0){
          val cmd = "_valves_seq_setstate 9\r"
          serial.port.writeBytes(cmd.getBytes)
        }else{
          val cmd = "_valves_seq_setstate 10\r"
          serial.port.writeBytes(cmd.getBytes)
        }

        val resp = serial.getMessageUntilCrWithTimeout(2)
        if(!resp.contains("OK"))
          Logger.error(resp(0))
      } else {
        val cmd = "_valves_seq_setstate 0\r"
        serial.port.writeBytes(cmd.getBytes)
        val resp = serial.getMessageUntilCrWithTimeout(2)
        if(!resp.contains("OK"))
          Logger.error(resp(0))
      }
    }
  }

  override def postStop(): Unit = {
    for (serial <- serialOpt)
      serial.port.closePort()

    super.postStop()
  }
}
