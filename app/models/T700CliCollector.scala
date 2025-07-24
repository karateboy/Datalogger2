package models

import com.google.inject.assistedinject.Assisted
import jssc.SerialPort
import models.Protocol.ProtocolParam
import play.api.Logger

import java.io.{BufferedReader, InputStreamReader, OutputStream}
import java.net.Socket
import javax.inject.Inject
import scala.concurrent.{Future, blocking}

class T700CliCollector @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                                 alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                                 calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                                (@Assisted("instId") instId: String, @Assisted("desc") desc: String,
                                 @Assisted("config") deviceConfig: DeviceConfig,
                                 @Assisted("protocolParam") protocolParam: ProtocolParam)
  extends AbstractCollector(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
    alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
    calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)(instId, desc, deviceConfig, protocolParam) {
  val logger: Logger = Logger(this.getClass)
  import context.dispatcher

  logger.info(s"$instId T700CliCollector start")
  import com.github.nscala_time.time.Imports._
  val dataInstrumentTypes = List.empty[InstrumentStatusType]
  var socketOpt: Option[Socket] = None
  var outOpt: Option[OutputStream] = None
  var inOpt: Option[BufferedReader] = None
  var serialCommOpt: Option[SerialComm] = Option.empty
  var lastSeqNo = ""
  var lastSeqOp = true
  var lastSeqTime = DateTime.now

  override def probeInstrumentStatusType: Seq[InstrumentStatusType] = Seq.empty[InstrumentStatusType]

  override def readReg(statusTypeList: List[InstrumentStatusType], full:Boolean): Future[Option[ModelRegValue2]] = Future {
    blocking {
      None
    }
  }

  override def connectHost(): Unit = {
    if (protocolParam.protocol == Protocol.tcpCli) {
      val socket = new Socket(protocolParam.host.get, 3000)
      socket.setSoTimeout(1000)
      socketOpt = Some(socket)
      outOpt = Some(socket.getOutputStream)
      inOpt = Some(new BufferedReader(new InputStreamReader(socket.getInputStream)))
    } else if (protocolParam.protocol == Protocol.serialCli) {
      val comm = SerialComm.open(protocolParam.comPort.get,
        protocolParam.speed.getOrElse(SerialPort.BAUDRATE_9600))
      serialCommOpt = Some(comm)
    } else {
      throw new IllegalArgumentException(s"Unsupported protocol: ${protocolParam.protocol}")
    }
  }


  override def getDataRegList: Seq[DataReg] = Seq.empty[DataReg]

  override def getCalibrationReg: Option[CalibrationReg] = None

  override def setCalibrationReg(address: Int, on: Boolean): Unit = {}

  override def executeSeq(seq: String, on: Boolean): Unit = {
    if ((seq == lastSeqNo && lastSeqOp == on) && (DateTime.now() < lastSeqTime + 5.second)) {
      logger.info(s"T700 in cold period, ignore same seq operation")
    } else {
      lastSeqTime = DateTime.now
      lastSeqOp = on
      lastSeqNo = seq
      if(protocolParam.protocol == Protocol.tcpCli) {
        for (out <- outOpt) {
          if (on) {
            val cmd = "C EXECSEQ \"" + seq + "\"\r\n"
            out.write(cmd.getBytes)
          } else
            out.write("C STANDBY\r\n".getBytes)
        }
      }else if(protocolParam.protocol == Protocol.serialCli) {
        for (serial <- serialCommOpt) {
          if (on) {
            val cmd = "C EXECSEQ \"" + seq + "\"\r\n"
            serial.port.writeBytes(cmd.getBytes())
          } else
            serial.port.writeBytes("C STANDBY\r\n".getBytes())
        }
      } else {
        throw new IllegalArgumentException(s"Unsupported protocol: ${protocolParam.protocol}")
      }
    }
  }

  override def postStop(): Unit = {
    for (comm <- serialCommOpt)
      SerialComm.close(comm)

    for (in <- inOpt)
      in.close()

    for (out <- outOpt)
      out.close()

    for (socket <- socketOpt)
      socket.close()

    super.postStop()
  }

  override def triggerVault(zero: Boolean, on: Boolean): Unit = {}
}
