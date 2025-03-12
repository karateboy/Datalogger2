package models

import akka.actor.Actor
import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam
import play.api.Logger

import java.io.{BufferedReader, InputStreamReader, OutputStream}
import java.net.Socket
import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}

object PicarroG2307 extends AbstractDrv(_id = "picarroG2307", desp = "Picarro G2307",
  protocols = List(Protocol.tcp)) {
  override val logger: Logger = Logger(this.getClass)
  val predefinedIST = List(
    InstrumentStatusType(key = "H2CO", addr = 0, desc = "H2CO", ""),
    InstrumentStatusType(key = "H2CO_30s", addr = 1, desc = "H2CO_30s", ""),
    InstrumentStatusType(key = "H2CO_2min", addr = 2, desc = "H2CO_2min", ""),
    InstrumentStatusType(key = "H2CO_5min", addr = 3, desc = "H2CO_5min", ""),
    InstrumentStatusType(key = "H2O_2307", addr = 4, desc = "H2O_2307", ""),
    InstrumentStatusType(key = "CH4_2307", addr = 5, desc = "CH4_2307", ""),
    InstrumentStatusType(key = "Outlet_Valve", addr = 7, desc = "Outlet_Valve", ""),
    InstrumentStatusType(key = "MPVPosition", addr = 9, desc = "MPVPosition", ""),
    InstrumentStatusType(key = "DasTemp", addr = 10, desc = "DasTemp", ""),
    InstrumentStatusType(key = "CavityPressure", addr = 11, desc = "CavityPressure", ""),
    InstrumentStatusType(key = "CavityTemp", addr = 12, desc = "CavityTemp", "")
  )

  val dataAddress: List[Int] = List(0, 4, 5)

  override def getMonitorTypes(param: String): List[String] = {
    for (i <- dataAddress) yield
      predefinedIST(i).key
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt:Option[AnyRef]): Actor = {
    val f2 = f.asInstanceOf[PicarroG2307.Factory]
    val config = validateParam(param)
    f2(id, desc = super.description, config, protocol)
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

class PicarroG2307Collector @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                                       alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                                       calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                                      (@Assisted("instId") instId: String, @Assisted("desc") desc: String,
                                       @Assisted("config") deviceConfig: DeviceConfig,
                                       @Assisted("protocolParam") protocolParam: ProtocolParam)
  extends AbstractCollector(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
    alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
    calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)(instId, desc, deviceConfig, protocolParam) {

  import PicarroG2307._

  @volatile var socketOpt: Option[Socket] = None
  @volatile var outOpt: Option[OutputStream] = None
  @volatile var inOpt: Option[BufferedReader] = None

  override def probeInstrumentStatusType: Seq[InstrumentStatusType] = predefinedIST

  override val readPeriod: Int = 1

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
            logger.debug(s"DAS=>Picarro $cmd")
            out.write(cmd.getBytes())
            val resp = readUntileNonEmpty()
            logger.debug(s"Picarro=>DAS $resp")
            val tokens = resp.split(";")

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

  override def connectHost(): Unit = {
    val socket = new Socket(protocolParam.host.get, 51020)
    socketOpt = Some(socket)
    outOpt = Some(socket.getOutputStream)
    inOpt = Some(new BufferedReader(new InputStreamReader(socket.getInputStream)))
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
          logger.debug(s"DAS=>Picarro $cmd")
          out.write(cmd.getBytes())
          val resp = readUntileNonEmpty()
          logger.debug(s"Picarro=>DAS $resp")
        } else {
          val cmd = "_valves_seq_setstate 10\r"
          logger.debug(s"DAS=>Picarro $cmd")
          out.write(cmd.getBytes())
          val resp = readUntileNonEmpty()
          logger.debug(s"Picarro=>DAS $resp")
        }
      } else {
        val cmd = "_valves_seq_setstate 0\r"
        logger.debug(s"DAS=>Picarro $cmd")
        out.write(cmd.getBytes())
        val resp = readUntileNonEmpty()
        logger.debug(s"Picarro=>DAS $resp")
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

  override def triggerVault(zero: Boolean, on: Boolean): Unit = {
    if(zero)
      setCalibrationReg(0, on)
    else
      setCalibrationReg(1, on)
  }
}
