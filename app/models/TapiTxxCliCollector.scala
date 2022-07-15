package models

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

  import context.dispatcher

  val model: String

  assert(protocolParam.protocol == Protocol.tcpCli)
  Logger.info(s"$instId : ${this.getClass.toString} start")
  @volatile var calibrating = false

  val dataInstrumentTypes: List[InstrumentStatusType]

  @volatile private var socketOpt: Option[Socket] = None
  @volatile private var outOpt: Option[OutputStream] = None
  @volatile private var inOpt: Option[BufferedReader] = None

  override def probeInstrumentStatusType: Seq[InstrumentStatusType] = {
    Logger.info(s"Probe $model")
    var istList = dataInstrumentTypes
    var addr = dataInstrumentTypes.length

    val statusTypeList: Option[List[InstrumentStatusType]] =
      for {
        out <- outOpt
        in <- inOpt
      } yield {
        out.write("T LIST\r\n".getBytes)
        Thread.sleep(1500)
        val resp = readTillTimeout(in)

        val ret =
          for {line <- resp} yield {
            addr = addr + 1
            for ((key, unit, _) <- getKeyUnitValue(line) if dataInstrumentTypes.forall(ist => ist.key != key)) yield
              InstrumentStatusType(key, addr, key, unit)
          }
        ret.flatten
      }

    Logger.info(s"Finished Probe $model")
    istList = istList ++ statusTypeList.getOrElse(List.empty[InstrumentStatusType])
    istList
  }

  def readDataReg(in: BufferedReader, out: OutputStream): List[(InstrumentStatusType, Double)]

  override def readReg(statusTypeList: List[InstrumentStatusType], full: Boolean): Future[Option[ModelRegValue2]] = Future {
    blocking {
      try{
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
              val resp = readTillTimeout(in)
              resp.flatMap(line => {
                for {(key, _, value) <- getKeyUnitValue(line) if dataInstrumentTypes.forall(ist => ist.key != key)
                     st <- statusTypeList.find(st => st.key == key)} yield
                  (st, value)
              })
            } else
              List.empty[(InstrumentStatusType, Double)]

          ModelRegValue2(inputRegs = data ++ statusList,
            modeRegs = List.empty[(InstrumentStatusType, Boolean)],
            warnRegs = List.empty[(InstrumentStatusType, Boolean)])
        }
      }catch{
        case ex:Exception=>
          if(!calibrating)
            reset()

          throw ex
      }
    }
  }

  override def onCalibrationStart(): Unit = {
    calibrating =true
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
          Logger.error(s"$instId readline null. close socket stream")
          reset()
        }
        if (line != null && !line.isEmpty)
          resp = resp :+ line.trim
      } while (line != null && !line.isEmpty && (!expectOneLine || resp.isEmpty))
    } catch {
      case _: SocketTimeoutException =>
        if (resp.isEmpty && !calibrating) {
          Logger.error("no response after timeout!")
          reset()
        }
    }
    resp
  }

  private def reset(): Unit = {
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
