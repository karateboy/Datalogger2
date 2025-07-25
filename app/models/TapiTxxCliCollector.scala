package models

import jssc.SerialPort
import models.AbstractCollector.ResetConnection
import models.Protocol.ProtocolParam
import play.api.Logger

import java.io.{BufferedReader, InputStreamReader, OutputStream}
import java.net.{Socket, SocketTimeoutException}
import scala.concurrent.{Future, blocking}

abstract class TapiTxxCliCollector(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                                   alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                                   calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                                  (instId: String, desc: String,
                                   deviceConfig: DeviceConfig,
                                   protocolParam: ProtocolParam)
  extends AbstractCollector(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
    alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
    calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)(instId, desc, deviceConfig, protocolParam) {

  val logger: Logger = Logger(this.getClass)

  import context.dispatcher

  val model: String

  logger.info(s"$instId : ${this.getClass.toString} start")
  @volatile var calibrating = false

  val dataInstrumentTypes: List[InstrumentStatusType]

  @volatile private var socketOpt: Option[Socket] = None
  @volatile private var outOpt: Option[OutputStream] = None
  @volatile private var inOpt: Option[BufferedReader] = None
  @volatile private var serialCommOpt: Option[SerialComm] = Option.empty

  override def probeInstrumentStatusType: Seq[InstrumentStatusType] = {
    logger.info(s"Probe $model")
    var istList = dataInstrumentTypes
    var addr = dataInstrumentTypes.length

    val statusTypeList: Option[List[InstrumentStatusType]] =
      for {
        out <- outOpt
        in <- inOpt
      } yield {
        if (protocolParam.protocol == Protocol.tcpCli)
          out.write("T LIST\r\n".getBytes)
        else {
          for (serial <- serialCommOpt) {
            serial.port.writeBytes("T LIST\n".getBytes)
          }
        }

        Thread.sleep(1500)

        if (protocolParam.protocol == Protocol.tcpCli) {
          val resp = readTillTimeout(in)

          val ret =
            for {line <- resp} yield {
              addr = addr + 1
              for ((key, unit, _) <- getKeyUnitValue(line) if dataInstrumentTypes.forall(ist => ist.key != key)) yield
                InstrumentStatusType(key, addr, key, unit)
            }
          ret.flatten
        } else {
          val serial = serialCommOpt.get
          val ret =
            for {line <- serial.getLine3()} yield {
              addr = addr + 1
              for ((key, unit, _) <- getKeyUnitValue(line) if dataInstrumentTypes.forall(ist => ist.key != key)) yield
                InstrumentStatusType(key, addr, key, unit)
            }
          ret.flatten
        }
      }

    logger.info(s"Finished Probe $model")
    istList = istList ++ statusTypeList.getOrElse(List.empty[InstrumentStatusType])
    istList
  }

  def readDataReg(in: BufferedReader, out: OutputStream): List[(InstrumentStatusType, Double)]

  def readDataRegSerial(serial: SerialComm): List[(InstrumentStatusType, Double)]

  override def readReg(statusTypeList: List[InstrumentStatusType], full: Boolean): Future[Option[ModelRegValue2]] = Future {
    blocking {
      try {
        def getReg(resp: List[String]): List[(InstrumentStatusType, Double)] =
          resp.flatMap(line => {
            for {(key, _, value) <- getKeyUnitValue(line) if dataInstrumentTypes.forall(ist => ist.key != key)
                 st <- statusTypeList.find(st => st.key == key)} yield
              (st, value)
          })

        if (protocolParam.protocol == Protocol.tcpCli) {
          for {
            in <- inOpt
            out <- outOpt
          } yield {
            val data = readDataReg(in, out)

            if (data.isEmpty)
              throw new Exception("no data")

            val statusList =
              if (full) {
                out.write("T LIST\r\n".getBytes)
                Thread.sleep(1500)
                getReg(readTillTimeout(in))
              } else
                List.empty[(InstrumentStatusType, Double)]

            ModelRegValue2(inputRegs = data ++ statusList,
              modeRegs = List.empty[(InstrumentStatusType, Boolean)],
              warnRegs = List.empty[(InstrumentStatusType, Boolean)])
          }
        } else
          for (serial <- serialCommOpt) yield {
            val data = readDataRegSerial(serial)
            val statusList =
              if (full) {
                val serial = serialCommOpt.get
                serial.port.writeBytes("T LIST\r\n".getBytes)
                Thread.sleep(1500)
                getReg(serial.getLine())
              } else
                List.empty[(InstrumentStatusType, Double)]

            ModelRegValue2(inputRegs = data ++ statusList,
              modeRegs = List.empty[(InstrumentStatusType, Boolean)],
              warnRegs = List.empty[(InstrumentStatusType, Boolean)])
          }
      } catch {
        case ex: Exception =>
          logger.error(s"$instId : ${this.getClass.toString} readReg error: ${ex.getMessage}", ex)
          if (!calibrating)
            reset()

          throw ex
      }
    }
  }

  override def onCalibrationStart(): Unit = {
    calibrating = true
  }

  override def onCalibrationEnd(): Unit = {
    calibrating = false
  }

  def getKeyUnitValue(line: String): Option[(String, String, Double)] = {
    try {
      if (!line.startsWith("T"))
        return None

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
          logger.error(s"$instId readline null. close socket stream")
          reset()
        }
        if (line != null && line.nonEmpty)
          resp = resp :+ line.trim
      } while (line != null && line.nonEmpty && (!expectOneLine || resp.isEmpty))
    } catch {
      case _: SocketTimeoutException =>
        if (resp.isEmpty && !calibrating) {
          logger.error("no response after timeout!")
          reset()
        }
    }
    resp
  }

  private def reset(): Unit = {
    if (protocolParam.protocol == Protocol.serialCli) {
      serialCommOpt.foreach(SerialComm.close)
      serialCommOpt = None
    } else if (protocolParam.protocol == Protocol.tcpCli) {
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
    }

    self ! ResetConnection
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
      outOpt = Some(comm.os)
      inOpt = Some(new BufferedReader(new InputStreamReader(comm.is)))
    } else {
      throw new IllegalArgumentException(s"Unsupported protocol: ${protocolParam.protocol}")
    }
  }

  override def getDataRegList: Seq[DataReg] = dataInstrumentTypes.map(it => DataReg(monitorType = it.key, it.addr, multiplier = 1))

  override def getCalibrationReg: Option[CalibrationReg] = Some(CalibrationReg(0, 1))

  override def setCalibrationReg(address: Int, on: Boolean): Unit = {
    if (protocolParam.protocol == Protocol.tcpCli) {
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
    } else if (protocolParam.protocol == Protocol.serialCli) {
      for (serial <- serialCommOpt) {
        if (on) {
          if (address == 0)
            serial.port.writeBytes("C ZERO\r\n".getBytes())
          else
            serial.port.writeBytes("C SPAN\r\n".getBytes())
        } else {
          serial.port.writeBytes("C EXIT\r\n".getBytes())
        }
      }
    } else {
      throw new IllegalArgumentException(s"Unsupported protocol: ${protocolParam.protocol}")
    }

  }

  override def postStop(): Unit = {
    for (in <- inOpt)
      in.close()

    for (out <- outOpt)
      out.close()

    for (socket <- socketOpt)
      socket.close()

    for (comm <- serialCommOpt)
      SerialComm.close(comm)

    logger.info(s"$instId : ${this.getClass.toString} stop")
    super.postStop()
  }
}
