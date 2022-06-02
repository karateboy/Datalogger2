package models

import com.google.inject.assistedinject.Assisted
import models.AbstractCollector.ResetConnection
import models.Protocol.ProtocolParam
import play.api.Logger

import java.io.{BufferedReader, InputStreamReader, OutputStream}
import java.net.{Socket, SocketTimeoutException}
import javax.inject.Inject
import scala.concurrent.{Future, blocking}

class T300CliCollector @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
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
  Logger.info(s"$instId T300CliCollector start")

  val dataInstrumentTypes = List(
    InstrumentStatusType(MonitorType.CO, 0, "CO", "PPM")
  )
  var socketOpt: Option[Socket] = None
  var outOpt: Option[OutputStream] = None
  var inOpt: Option[BufferedReader] = None

  override def probeInstrumentStatusType: Seq[InstrumentStatusType] = {
    Logger.info("Probe T300")
    var istList = List.empty[InstrumentStatusType]
    var addr = dataInstrumentTypes.length
    istList = istList ++ dataInstrumentTypes

    val statusTypeList: Option[List[InstrumentStatusType]] =
      for {
        out <- outOpt
        in <- inOpt
      } yield {
        out.write("T LIST\r\n".getBytes)
        val resp = readTillTimeout(in)

        val ret =
          for {line <- resp} yield {
            addr = addr + 1
            for ((key, unit, _) <- getKeyUnitValue(line)) yield
              InstrumentStatusType(key, addr, key, unit)
          }
        ret.flatten
      }

    Logger.info("Finished Probe T300")
    istList = istList ++ statusTypeList.getOrElse(List.empty[InstrumentStatusType])
    istList
  }

  def getKeyUnitValue(line: String): Option[(String, String, Double)] = {
    try {
      val part1 = line.split("\\s+").drop(3).mkString(" ")
      val tokens = part1.split("=")
      val key = tokens(0)
      val valueAndUnit = tokens(1).split("\\s+")
      val unit = if (valueAndUnit.length >= 2)
        valueAndUnit.last
      else
        ""
      val value = valueAndUnit(0).toDouble
      Some((key, unit, value))
    } catch {
      case _: Throwable =>
        None
    }

  }

  def readTillTimeout(in: BufferedReader, expectOneLine: Boolean = false): List[String] = {
    var resp = List.empty[String]
    var line = ""
    try {
      do {
        line = in.readLine()
        if (line == null) {
          Logger.error(s"$instId readline null. close socket stream")
          for (in <- inOpt) {
            in.close()
            inOpt = None
          }

          for (out <- outOpt) {
            out.close()
            outOpt = None
          }

          for (sock <- socketOpt) {
            sock.close()
            socketOpt = None
          }

          self ! ResetConnection
        }
        if (line != null && !line.isEmpty)
          resp = resp :+ line.trim
      } while (line != null && !line.isEmpty && (!expectOneLine || resp.isEmpty))
    } catch {
      case _: SocketTimeoutException =>
        if (resp.isEmpty) {
          Logger.error("no response after timeout!")
          for (in <- inOpt) {
            in.close()
            inOpt = None
          }

          for (out <- outOpt) {
            out.close()
            outOpt = None
          }

          for (sock <- socketOpt) {
            sock.close()
            socketOpt = None
          }

          self ! ResetConnection
        }
    }
    resp
  }

  override def readReg(statusTypeList: List[InstrumentStatusType], full:Boolean): Future[Option[ModelRegValue2]] = Future {
    blocking {
      val ret = {
        for {
          in <- inOpt
          out <- outOpt
        } yield {
          out.write("T CO\r\n".getBytes())
          val data: List[(InstrumentStatusType, Double)] = readTillTimeout(in, expectOneLine = true).zipWithIndex.flatMap(line_idx => {
            val (line, idx) = line_idx
            for {(_, _, value) <- getKeyUnitValue(line)
                 st <- statusTypeList.find(st => st.addr == idx)} yield
              (st, value)
          })

          val statusList =
            if(full){
              out.write("T LIST\r\n".getBytes)
              val resp = readTillTimeout(in, true)
              resp.flatMap(line => {
                for {(key, _, value) <- getKeyUnitValue(line) if dataInstrumentTypes.forall(ist => ist.key != key)
                     st <- statusTypeList.find(st => st.key == key)} yield
                  (st, value)
              })
            }else
              List.empty[(InstrumentStatusType, Double)]

          ModelRegValue2(inputRegs = data ++ statusList,
            modeRegs = List.empty[(InstrumentStatusType, Boolean)],
            warnRegs = List.empty[(InstrumentStatusType, Boolean)])
        }
      }
      ret
    }
  }

  override def connectHost: Unit = {
    val socket = new Socket(protocolParam.host.get, 3000)
    socket.setSoTimeout(1000)
    socketOpt = Some(socket)
    outOpt = Some(socket.getOutputStream())
    inOpt = Some(new BufferedReader(new InputStreamReader(socket.getInputStream())))
  }

  override def getDataRegList: Seq[DataReg] = dataInstrumentTypes.map(it => DataReg(monitorType = it.key, it.addr, multiplier = 1))

  override def getCalibrationReg: Option[CalibrationReg] = Some(CalibrationReg(0, 1))

  override def setCalibrationReg(address: Int, on: Boolean): Unit = {
    for (out <- outOpt) {
      if (on) {
        if (address == 0)
          out.write("C ZERO\r\n".getBytes)
        else
          out.write("C SPAN\r\n".getBytes)
      } else {
        out.write("C EXIT\r\n".getBytes)
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
