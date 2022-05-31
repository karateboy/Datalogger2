package models

import com.google.inject.assistedinject.Assisted
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

  import context.dispatcher

  assert(protocolParam.protocol == Protocol.tcpCli)
  Logger.info(s"$instId T700CliCollector start")
  import com.github.nscala_time.time.Imports._
  val dataInstrumentTypes = List.empty[InstrumentStatusType]
  var socketOpt: Option[Socket] = None
  var outOpt: Option[OutputStream] = None
  var inOpt: Option[BufferedReader] = None
  var lastSeqNo = ""
  var lastSeqOp = true
  var lastSeqTime = DateTime.now

  override def probeInstrumentStatusType: Seq[InstrumentStatusType] = Seq.empty[InstrumentStatusType]

  override def readReg(statusTypeList: List[InstrumentStatusType], full:Boolean): Future[Option[ModelRegValue2]] = Future {
    blocking {
      None
    }
  }

  override def connectHost: Unit = {
    val socket = new Socket(protocolParam.host.get, 3000)
    socket.setSoTimeout(1000)
    socketOpt = Some(socket)
    outOpt = Some(socket.getOutputStream())
    inOpt = Some(new BufferedReader(new InputStreamReader(socket.getInputStream())))
  }


  override def getDataRegList: Seq[DataReg] = Seq.empty[DataReg]

  override def getCalibrationReg: Option[CalibrationReg] = None

  override def setCalibrationReg(address: Int, on: Boolean): Unit = {}

  override def executeSeq(seq: String, on: Boolean): Unit = {
    if ((seq == lastSeqNo && lastSeqOp == on) && (DateTime.now() < lastSeqTime + 5.second)) {
      Logger.info(s"T700 in cold period, ignore same seq operation")
    } else {
      lastSeqTime = DateTime.now
      lastSeqOp = on
      lastSeqNo = seq
      for (out <- outOpt) {
        if (on) {
          val cmd = "C EXECSEQ \"" + seq + "\"\r\n"
          out.write(cmd.getBytes)
        } else
          out.write("C STANDBY\r\n".getBytes)
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
