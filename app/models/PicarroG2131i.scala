package models

import akka.actor.Actor
import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam
import models.mongodb.{AlarmOp, CalibrationOp, InstrumentStatusOp}
import play.api.Logger
import play.api.libs.json.Json

import java.io.{BufferedReader, InputStreamReader, OutputStream}
import java.net.Socket
import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}

object PicarroG2131i extends AbstractDrv(_id = "picarroG2131i", desp = "Picarro G2131i",
  protocols = List(Protocol.tcp)) {
  val predefinedIST = List(
    InstrumentStatusType(key = "Delta_Raw", addr = 0, desc = "Delta_Raw", ""),
    InstrumentStatusType(key = "Delta_30s", addr = 1, desc = "Delta_30s", ""),
    InstrumentStatusType(key = "Delta_2min", addr = 2, desc = "Delta_2min", ""),
    InstrumentStatusType(key = "Delta_5min", addr = 3, desc = "Delta_5min", ""),
    InstrumentStatusType(key = "12CO2", addr = 4, desc = "12CO2", ""),
    InstrumentStatusType(key = "12CO2_dry", addr = 5, desc = "12CO2_dry", ""),
    InstrumentStatusType(key = "13CO2", addr = 6, desc = "13CO2", ""),
    InstrumentStatusType(key = "13CO2_dry", addr = 7, desc = "13CO2_dry", ""),
    InstrumentStatusType(key = "Ratio_Raw", addr = 8, desc = "Ratio_Raw", ""),
    InstrumentStatusType(key = "H2O_2131", addr = 9, desc = "H2O_2131", "")
  )

  val dataAddress: List[Int] = List(0, 4)

  override def getMonitorTypes(param: String): List[String] = {
    for (i <- dataAddress) yield
      predefinedIST(i).key
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt:Option[AnyRef]): Actor = {
    val f2 = f.asInstanceOf[PicarroG2131i.Factory]
    val config = validateParam(param)
    f2(id, desc = super.description, config, protocol)
  }

  override def verifyParam(json: String) = {
    val mt = getDataRegList.map(_.monitorType)
    val newParam = DeviceConfig(Some(1), None, Some(mt),
      None, None, None,
      None, None,
      None, None,
      None, None,
      None)

    Json.toJson(newParam).toString()
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
}

class PicarroG2131iCollector @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                                       alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                                       calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                                      (@Assisted("instId") instId: String, @Assisted("desc") desc: String,
                                       @Assisted("config") deviceConfig: DeviceConfig,
                                       @Assisted("protocolParam") protocolParam: ProtocolParam)
  extends AbstractCollector(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
    alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
    calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)(instId, desc, deviceConfig, protocolParam) {

  import PicarroG2131i._

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
            def readUntileNonEmpty(): String = {
              var resp: String = null
              do {
                resp = in.readLine()
              } while (resp.isEmpty)
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

  override def getDataRegList: Seq[DataReg] = predefinedIST.filter(p => dataAddress.contains(p.addr)).map {
    ist =>
      DataReg(monitorType = ist.key, ist.addr, multiplier = 1)
  }

  override def getCalibrationReg: Option[CalibrationReg] = Some(CalibrationReg(0, 1))

  override def setCalibrationReg(address: Int, on: Boolean): Unit = {

    for {in <- inOpt
         out <- outOpt} {
      def readUntileNonEmpty(): String = {
        var resp: String = null
        do {
          resp = in.readLine()
        } while (resp.isEmpty)
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
