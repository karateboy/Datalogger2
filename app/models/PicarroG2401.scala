package models

import akka.actor.Actor
import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam
import models.mongodb.{AlarmOp, CalibrationOp, InstrumentStatusOp}
import play.api.Logger

import java.io.{BufferedReader, InputStreamReader, OutputStream}
import java.net.Socket
import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}

object PicarroG2401 extends AbstractDrv(_id = "picarroG2401", desp = "Picarro G2401",
  protocols = List(Protocol.tcp)) {
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
    for (i <- dataAddress) yield
      predefinedIST(i).key
  }


  override def getDataRegList: List[DataReg] =
    predefinedIST.filter(p => dataAddress.contains(p.addr)).map {
      ist =>
        DataReg(monitorType = ist.key, ist.addr, multiplier = 1)
    }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt:Option[AnyRef]): Actor = {
    val f2 = f.asInstanceOf[PicarroG2401.Factory]
    val config = validateParam(param)
    f2(id, desc = super.description, config, protocol)
  }

  trait Factory {
    def apply(@Assisted("instId") instId: String, @Assisted("desc") desc: String, @Assisted("config") config: DeviceConfig,
              @Assisted("protocolParam") protocol: ProtocolParam): Actor
  }
}

class PicarroG2401Collector @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                                      alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                                      calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                                     (@Assisted("instId") instId: String, @Assisted("desc") desc: String,
                                      @Assisted("config") deviceConfig: DeviceConfig,
                                      @Assisted("protocolParam") protocolParam: ProtocolParam)
  extends AbstractCollector(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
    alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
    calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)(instId, desc, deviceConfig, protocolParam) {

  import PicarroG2401._

  var socketOpt: Option[Socket] = None
  var outOpt: Option[OutputStream] = None
  var inOpt: Option[BufferedReader] = None

  override def probeInstrumentStatusType: Seq[InstrumentStatusType] = predefinedIST

  override def readReg(statusTypeList: List[InstrumentStatusType], full:Boolean): Future[Option[ModelRegValue2]] =
    Future {
      blocking {
        val ret = {
          for {
            in <- inOpt
            out <- outOpt
          } yield {
            def readUntileNonEmpty(): String ={
              var resp: String = null
              do{
                resp = in.readLine()
              }while(resp.isEmpty)
              resp
            }

            val cmd = "_Meas_GetConc\r"
            Logger.debug(s"DAS=>Picarro ${cmd}")
            out.write(cmd.getBytes())
            val resp = readUntileNonEmpty()
            Logger.debug(s"Picarro=>DAS $resp")
            val tokens = resp.split(";")
            if (tokens.length != predefinedIST.length) {
              Logger.error(s"Data length ${tokens.length} != ${predefinedIST.length}")
              Logger.error(resp)
            }

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
          }
        }
        ret.flatten
      }
    }

  override def connectHost: Unit = {
    val socket = new Socket(protocolParam.host.get, 51020)
    socketOpt = Some(socket)
    outOpt = Some(socket.getOutputStream())
    inOpt = Some(new BufferedReader(new InputStreamReader(socket.getInputStream())))
  }

  override def getDataRegList: Seq[DataReg] = PicarroG2401.getDataRegList

  override def getCalibrationReg: Option[CalibrationReg] = Some(CalibrationReg(0, 1))

  override def setCalibrationReg(address: Int, on: Boolean): Unit = {

    for {in <- inOpt
         out <- outOpt} {
      def readUntileNonEmpty(): String ={
        var resp: String = null
        do{
          resp = in.readLine()
        }while(resp.isEmpty)
        resp
      }

        if (on) {
          if (address == 0) {
            val cmd = "_valves_seq_setstate 9\r"
            Logger.debug(s"DAS=>Picarro $cmd")
            out.write(cmd.getBytes())
            val resp = readUntileNonEmpty()
            Logger.debug(s"Picarro=>DAS $resp")
          } else {
            val cmd = "_valves_seq_setstate 10\r"
            Logger.debug(s"DAS=>Picarro $cmd")
            out.write(cmd.getBytes())
            val resp = readUntileNonEmpty()
            Logger.debug(s"Picarro=>DAS $resp")
          }
        } else {
          val cmd = "_valves_seq_setstate 0\r"
          Logger.debug(s"DAS=>Picarro $cmd")
          out.write(cmd.getBytes())
          val resp = readUntileNonEmpty()
          Logger.debug(s"Picarro=>DAS $resp")
        }
    }
  }

  override def postStop(): Unit = {
    for (in <- inOpt)
      in.close()

    for (out <- outOpt)
      out.close()

    for (socket <- socketOpt)
      socket.close()

    super.postStop()
  }
}
