package models

import akka.actor.{Actor, ActorRef, _}
import akka.util.ByteString
import com.github.nscala_time.time.Imports._
import com.google.inject.assistedinject.Assisted
import models.Protocol.{ProtocolParam, tcp}
import play.api._
import play.api.libs.json.{JsError, Json, OWrites, Reads}
import akka.io._

import scala.concurrent.ExecutionContext.Implicits.global

case class Horiba370Config(calibrationTime: Option[LocalTime],
                           raiseTime: Option[Int], downTime: Option[Int], holdTime: Option[Int],
                           calibrateZeoSeq: Option[String], calibrateSpanSeq: Option[String],
                           calibratorPurgeSeq: Option[String], calibratorPurgeTime: Option[Int],
                           calibrateZeoDO: Option[Int], calibrateSpanDO: Option[Int], skipInternalVault: Option[Boolean])

object Horiba370Collector extends DriverOps {
  val logger: Logger = Logger(this.getClass)
  val FlameStatus = "FlameStatus"
  val Press = "Press"
  val Flow = "Flow"
  val Temp = "Temp"
  val InstrumentStatusTypeList: List[InstrumentStatusType] = List(
    InstrumentStatusType(FlameStatus, 10, "Flame Status", "0:Extinguishing/1:Ignition sequence/2:Ignition"),
    InstrumentStatusType(Press + 0, 37, "Presssure 0", "kPa"),
    InstrumentStatusType(Press + 1, 37, "Presssure 1", "kPa"),
    InstrumentStatusType(Press + 2, 37, "Presssure 2", "kPa"),
    InstrumentStatusType(Flow + 0, 38, "Flow 0", "L/min"),
    InstrumentStatusType(Flow + 1, 38, "Flow 0", "L/min"),
    InstrumentStatusType(Flow + 2, 38, "Flow 0", "L/min"),
    InstrumentStatusType(Flow + 3, 38, "Flow 0", "L/min"),
    InstrumentStatusType(Flow + 4, 38, "Flow 0", "L/min"),
    InstrumentStatusType(Flow + 5, 38, "Flow 0", "L/min"),
    InstrumentStatusType(Flow + 6, 38, "Flow 0", "L/min"),
    InstrumentStatusType(Flow + 7, 38, "Flow 0", "L/min"),
    InstrumentStatusType(Flow + 8, 38, "Flow 0", "L/min"),
    InstrumentStatusType(Flow + 9, 38, "Flow 0", "L/min"),
    InstrumentStatusType(Temp + 0, 39, "Temperature 0", "C"),
    InstrumentStatusType(Temp + 1, 39, "Temperature 1", "C"),
    InstrumentStatusType(Temp + 2, 39, "Temperature 2", "C"),
    InstrumentStatusType(Temp + 3, 39, "Temperature 3", "C"),
    InstrumentStatusType(Temp + 4, 39, "Temperature 4", "C"),
    InstrumentStatusType(Temp + 5, 39, "Temperature 5", "C"),
    InstrumentStatusType(Temp + 6, 39, "Temperature 6", "C"),
    InstrumentStatusType(Temp + 7, 39, "Temperature 7", "C"),
    InstrumentStatusType(Temp + 8, 39, "Temperature 8", "C"),
    InstrumentStatusType(Temp + 9, 39, "Temperature 9", "C"))

  import ModelHelper._
  implicit val cfgRead: Reads[Horiba370Config] = Json.reads[Horiba370Config]
  implicit val cfgWrite: OWrites[Horiba370Config] = Json.writes[Horiba370Config]

  private def getNextLoggingStatusTime = {
    import com.github.nscala_time.time.Imports._
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
    //logger.debug(s"$instId next logging time= $nextTime")
    nextTime
  }

  override def verifyParam(json: String): String = {
    val ret = Json.parse(json).validate[Horiba370Config]
    ret.fold(
      error => {
        logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => {
        Json.toJson(param).toString()
      })
  }

  override def getMonitorTypes(param: String): List[String] = {
    List("CH4", "NMHC", "THC")
  }


  import Protocol.ProtocolParam
  import akka.actor._

  override def getCalibrationTime(param: String): Option[LocalTime] = {
    val config = validateParam(param)
    config.calibrationTime
  }

  def validateParam(json: String): Horiba370Config = {
    val ret = Json.parse(json).validate[Horiba370Config]
    ret.fold(
      error => {
        logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => param)
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt: Option[AnyRef]): Actor = {
    assert(f.isInstanceOf[Horiba370Collector.Factory])
    val f2 = f.asInstanceOf[Horiba370Collector.Factory]
    val driverParam = validateParam(param)
    f2(id, protocol, driverParam)
  }

  override def id: String = "horiba370"

  override def description: String = "Horiba APXX-370"

  override def protocol: List[String] = List(tcp)

  trait Factory {
    def apply(id: String, protocol: ProtocolParam, param: Horiba370Config): Actor
  }

  case object ReadData

  case object CheckStatus
}

import javax.inject._

class Horiba370Collector @Inject()
(instrumentOp: InstrumentDB, instrumentStatusOp: InstrumentStatusDB,
 calibrationOp: CalibrationDB, monitorTypeOp: MonitorTypeDB)
(@Assisted id: String, @Assisted protocol: ProtocolParam, @Assisted config: Horiba370Config) extends Actor {
  val logger: Logger = Logger(this.getClass)
  import Horiba370Collector._
  import TapiTxx._
  import DataCollectManager._

  import scala.concurrent.duration._
  import scala.concurrent.{Future, blocking}

  logger.info(s"Horiba370Collector created $id:${protocol} ${config}")
  val timer: Cancellable = context.system.scheduler.scheduleAtFixedRate(FiniteDuration(1, SECONDS), Duration(2, SECONDS), self, ReadData)
  private val statusTimer = context.system.scheduler.scheduleAtFixedRate(FiniteDuration(30, SECONDS), Duration(1, MINUTES), self, CheckStatus)
  val mtCH4 = "CH4"
  val mtNMHC = "NMHC"
  val mtTHC = "THC"

  @volatile var (collectorState, instrumentStatusTypesOpt) = {
    val instrument = instrumentOp.getInstrument(id)
    val inst = instrument(0)
    (inst.state, inst.statusType)
  }
  if (instrumentStatusTypesOpt.isEmpty) {
    instrumentStatusTypesOpt = Some(InstrumentStatusTypeList)
    instrumentOp.updateStatusType(id, InstrumentStatusTypeList)
  } else {
    val instrumentStatusTypes = instrumentStatusTypesOpt.get
    if (instrumentStatusTypes.length != InstrumentStatusTypeList.length) {
      instrumentOp.updateStatusType(id, InstrumentStatusTypeList)
    }
  }

  @volatile var nextLoggingStatusTime: DateTime = getNextLoggingStatusTime
  @volatile var statusMap = Map.empty[String, Double]
  @volatile var raiseStartTimerOpt: Option[Cancellable] = None
  @volatile var calibrateTimerOpt: Option[Cancellable] = None

  def logStatus(): Unit = {
    import com.github.nscala_time.time.Imports._
    //Log Instrument state
    if (DateTime.now() > nextLoggingStatusTime) {
      try {
        val statusList = statusMap map { kv => InstrumentStatusDB.Status(kv._1, kv._2) }
        val is = InstrumentStatusDB.InstrumentStatus(DateTime.now().toDate, id, statusList.toList)
        instrumentStatusOp.log(is)
      } catch {
        case _: Throwable =>
          logger.error("Log instrument status failed")
      }
      nextLoggingStatusTime = getNextLoggingStatusTime
    }
  }

  // override postRestart so we don't call preStart and schedule a new message
  override def postRestart(reason: Throwable): Unit = {}

  private def processResponse(data: ByteString)(implicit calibrateRecordStart: Boolean): Unit = {
    def getResponse = {
      assert(data(0) == 0x1)
      assert(data(data.length - 1) == 0x3)

      val cmd = data.slice(7, 11).decodeString("US-ASCII")
      val prmStr = data.slice(12, data.length - 3).decodeString("US-ASCII")
      (cmd, prmStr)
    }

    val (cmd, prmStr) = getResponse
    cmd match {
      case "R001" =>
        val result = prmStr.split(",")
        assert(result.length == 8)

        val ch4Value = result(2).substring(5).toDouble
        val nmhcValue = result(3).substring(5).toDouble
        val thcValue = result(4).substring(5).toDouble
        val ch4 = MonitorTypeData(mtCH4, ch4Value, collectorState)
        val nmhc = MonitorTypeData(mtNMHC, nmhcValue, collectorState)
        val thc = MonitorTypeData(mtTHC, thcValue, collectorState)

        if (calibrateRecordStart)
          self ! ReportData(List(ch4, nmhc, thc))

        context.parent ! ReportData(List(ch4, nmhc, thc))

      case "A024" =>
        logger.info("Response from line change (A024)")
        logger.info(prmStr)

      case "A029" =>
        logger.info("Response from user zero (A029)")
        logger.info(prmStr)

      case "A030" =>
        logger.info("Response from user span (A030)")
        logger.info(prmStr)

      case "R010" =>
        val result = prmStr.split(",")
        val value = result(1).toDouble
        statusMap += (FlameStatus -> value)

      case "R037" =>
        val ret = prmStr.split(",")
        if (ret.length == 31 && ret(0) == "00") {
          for (idx <- 0 to 2) {
            val cc = ret(1 + idx * 3)
            val dp = ret(1 + idx * 3 + 1)
            val value = ret(1 + idx * 3 + 2).toDouble
            statusMap += (Press + idx -> value)
          }
        }

      case "R038" =>
      //logger.info("R038")
      //logger.info(prmStr)
      //val ret = prmStr.split(",")
      //logger.info("#=" + ret.length)
    }
  }

  def receive = {
    case UdpConnected.Connected =>
      logger.info("UDP connected...")
      context become connectionReady(sender())(false)
  }

  def reqData(connection: ActorRef) = {
    val reqCmd = Array[Byte](0x1, '0', '2', '0', '2', '0', '0',
      'R', '0', '0', '1', 0x2)
    val FCS = reqCmd.foldLeft(0x0)((a, b) => a ^ b)
    val fcsStr = "%x".format(FCS.toByte)
    val reqFrame = reqCmd ++ (fcsStr.getBytes("UTF-8")).:+(0x3.toByte)
    connection ! UdpConnected.Send(ByteString(reqFrame))
  }

  import context.system

  import java.net._

  IO(UdpConnected) ! UdpConnected.Connect(self, new InetSocketAddress(protocol.host.get, 53700))

  def reqFlameStatus(connection: ActorRef) = {
    val reqCmd = Array[Byte](0x1, '0', '2', '0', '2', '0', '0',
      'R', '0', '1', '0', 0x2)
    val FCS = reqCmd.foldLeft(0x0)((a, b) => a ^ b.toByte)
    val fcsStr = "%x".format(FCS.toByte)
    val reqFrame = reqCmd ++ (fcsStr.getBytes("UTF-8")).:+(0x3.toByte)
    connection ! UdpConnected.Send(ByteString(reqFrame))
  }

  def reqPressure(connection: ActorRef) = {
    val reqCmd = Array[Byte](0x1, '0', '2', '0', '2', '0', '0',
      'R', '0', '3', '7', 0x2)
    val FCS = reqCmd.foldLeft(0x0)((a, b) => a ^ b.toByte)
    val fcsStr = "%x".format(FCS.toByte)
    val reqFrame = reqCmd ++ (fcsStr.getBytes("UTF-8")).:+(0x3.toByte)
    connection ! UdpConnected.Send(ByteString(reqFrame))
  }

  def reqFlow(connection: ActorRef) = {
    val reqCmd = Array[Byte](0x1, '0', '2', '0', '2', '0', '0',
      'R', '0', '3', '8', 0x2)
    val FCS = reqCmd.foldLeft(0x0)((a, b) => a ^ b.toByte)
    val fcsStr = "%x".format(FCS.toByte)
    val reqFrame = reqCmd ++ (fcsStr.getBytes("UTF-8")).:+(0x3.toByte)
    connection ! UdpConnected.Send(ByteString(reqFrame))
  }

  def reqZeroCalibration(connection: ActorRef) = {
    reqZero(connection)

    //    val componentNo = if (mt == mtCH4)
    //      '0'.toByte
    //    else if (mt == mtTHC)
    //      '2'.toByte
    //    else {
    //      throw new Exception(s"Invalid monitorType ${mt.toString}")
    //    }
    //
    //    val reqCmd = Array[Byte](0x1, '0', '2', '0', '2', '0', '0',
    //      'A', '0', '2', '9', 0x2, componentNo)
    //    val FCS = reqCmd.foldLeft(0x0)((a, b) => a ^ b.toByte)
    //    val fcsStr = "%x".format(FCS.toByte)
    //    val reqFrame = reqCmd ++ (fcsStr.getBytes("UTF-8")).:+(0x3.toByte)
    //    connection ! UdpConnected.Send(ByteString(reqFrame))
  }

  def reqSpanCalibration(connection: ActorRef) = {
    reqSpan(connection)

    //    val componentNo = if (mt == mtCH4)
    //      '0'.toByte
    //    else if (mt == mtTHC)
    //      '2'.toByte
    //    else {
    //      throw new Exception(s"Invalid monitorType ${mt.toString}")
    //    }
    //
    //    val reqCmd = Array[Byte](0x1, '0', '2', '0', '2', '0', '0',
    //      'A', '0', '3', '0', 0x2, componentNo)
    //    val FCS = reqCmd.foldLeft(0x0)((a, b) => a ^ b.toByte)
    //    val fcsStr = "%x".format(FCS.toByte)
    //    val reqFrame = reqCmd ++ (fcsStr.getBytes("UTF-8")).:+(0x3.toByte)
    //    connection ! UdpConnected.Send(ByteString(reqFrame))
  }

  def reqNormal(connection: ActorRef) = {
    val reqCmd = Array[Byte](0x1, '0', '2', '0', '2', '0', '0',
      'A', '0', '2', '4', 0x2, '0')
    val FCS = reqCmd.foldLeft(0x0)((a, b) => a ^ b.toByte)
    val fcsStr = "%x".format(FCS.toByte)
    val reqFrame = reqCmd ++ (fcsStr.getBytes("UTF-8")).:+(0x3.toByte)
    connection ! UdpConnected.Send(ByteString(reqFrame))
  }

  def reqZero(connection: ActorRef) = {
    val reqCmd = Array[Byte](0x1, '0', '2', '0', '2', '0', '0',
      'A', '0', '2', '4', 0x2, '1')
    val FCS = reqCmd.foldLeft(0x0)((a, b) => a ^ b.toByte)
    val fcsStr = "%x".format(FCS.toByte)
    val reqFrame = reqCmd ++ (fcsStr.getBytes("UTF-8")).:+(0x3.toByte)
    connection ! UdpConnected.Send(ByteString(reqFrame))
  }

  def reqSpan(connection: ActorRef) = {
    val reqCmd = Array[Byte](0x1, '0', '2', '0', '2', '0', '0',
      'A', '0', '2', '4', 0x2, '2')
    val FCS = reqCmd.foldLeft(0x0)((a, b) => a ^ b.toByte)
    val fcsStr = "%x".format(FCS.toByte)
    val reqFrame = reqCmd ++ (fcsStr.getBytes("UTF-8")).:+(0x3.toByte)
    connection ! UdpConnected.Send(ByteString(reqFrame))
  }

  def setupSpanRaiseStartTimer(connection: ActorRef) {
    raiseStartTimerOpt =
      if (config.calibratorPurgeTime.isDefined && config.calibratorPurgeTime.get != 0) {
        collectorState = MonitorStatus.NormalStat
        reqNormal(connection)
        instrumentOp.setState(id, collectorState)

        Some(purgeCalibrator)
      } else
        Some(system.scheduler.scheduleOnce(Duration(1, SECONDS), self, RaiseStart))
  }

  def purgeCalibrator() = {
    import scala.concurrent.duration._
    def triggerCalibratorPurge(v: Boolean) {
      try {
        if (v && config.calibratorPurgeSeq.isDefined)
          context.parent ! ExecuteSeq(config.calibratorPurgeSeq.get, v)
        else
          context.parent ! ExecuteSeq(T700_STANDBY_SEQ, true)
      } catch {
        case ex: Exception =>
          ModelHelper.logException(ex)
      }
    }

    val purgeTime = config.calibratorPurgeTime.get
    logger.info(s"Purge calibrator. Delay start of calibration $purgeTime seconds")
    triggerCalibratorPurge(true)
    system.scheduler.scheduleOnce(Duration(purgeTime + 1, SECONDS), self, RaiseStart)
  }

  def connectionReady(connection: ActorRef)(implicit calibrateRecordStart: Boolean): Receive = {

    case UdpConnected.Received(data) =>
      processResponse(data)

    case UdpConnected.Disconnect =>
      connection ! UdpConnected.Disconnect

    case UdpConnected.Disconnected => context.stop(self)

    case ReadData =>
      reqData(connection)
      logStatus()

    case CheckStatus =>
      reqFlameStatus(connection)
      reqPressure(connection)
      reqFlow(connection)

    case SetState(id, state) =>
      Future {
        blocking {
          collectorState = state
          instrumentOp.setState(id, state)
          if (state == MonitorStatus.NormalStat) {
            reqNormal(connection)
            raiseStartTimerOpt map {
              timer => timer.cancel()
            }
          }
        }
      }

    case AutoCalibration(instId) =>
      assert(instId == id)
      collectorState = MonitorStatus.ZeroCalibrationStat
      instrumentOp.setState(id, collectorState)
      context become calibrationHandler(connection, AutoZero,
        com.github.nscala_time.time.Imports.DateTime.now, false, List.empty[MonitorTypeData],
        Map.empty[String, Option[Double]])
      self ! RaiseStart

    case ManualZeroCalibration(instId) =>
      assert(instId == id)
      collectorState = MonitorStatus.ZeroCalibrationStat
      instrumentOp.setState(id, collectorState)
      context become calibrationHandler(connection, ManualZero,
        com.github.nscala_time.time.Imports.DateTime.now, false, List.empty[MonitorTypeData],
        Map.empty[String, Option[Double]])
      self ! RaiseStart

    case ManualSpanCalibration(instId) =>
      assert(instId == id)
      context become calibrationHandler(connection, ManualSpan,
        com.github.nscala_time.time.Imports.DateTime.now, false, List.empty[MonitorTypeData],
        Map.empty[String, Option[Double]])

      setupSpanRaiseStartTimer(connection)
  }

  def calibrationHandler(connection: ActorRef, calibrationType: CalibrationType,
                         startTime: com.github.nscala_time.time.Imports.DateTime,
                         recording: Boolean,
                         calibrationDataList: List[MonitorTypeData],
                         zeroMap: Map[String, Option[Double]]): Receive = {

    case UdpConnected.Received(data) =>
      processResponse(data)(recording)

    case UdpConnected.Disconnect =>
      connection ! UdpConnected.Disconnect

    case UdpConnected.Disconnected => context.stop(self)

    case ReadData =>
      reqData(connection)
      logStatus()

    case RaiseStart =>
      Future {
        blocking {
          if (calibrationType.zero)
            collectorState = MonitorStatus.ZeroCalibrationStat
          else
            collectorState = MonitorStatus.SpanCalibrationStat

          instrumentOp.setState(id, collectorState)

          logger.info(s"${calibrationType} RaiseStart")

          if (calibrationType.zero) {
            for (seqNo <- config.calibrateZeoSeq)
              context.parent ! ExecuteSeq(seqNo, true)

            if (!config.skipInternalVault.getOrElse(false))
              reqZeroCalibration(connection)
          } else {
            for (seqNo <- config.calibrateSpanSeq)
              context.parent ! ExecuteSeq(seqNo, true)

            if (!config.skipInternalVault.getOrElse(false))
              reqSpanCalibration(connection)
          }
          for (raiseTime <- config.raiseTime)
            calibrateTimerOpt = Some(context.system.scheduler.scheduleOnce(Duration(raiseTime, SECONDS), self, HoldStart))
        }
      }

    case reportData:ReportData =>
      if (recording) {
        val data = reportData.dataList(monitorTypeOp)
        context become calibrationHandler(connection, calibrationType, startTime, recording,
          data ::: calibrationDataList, zeroMap)
      }

    case HoldStart =>
      logger.debug(s"${calibrationType} HoldStart")
      context become calibrationHandler(connection, calibrationType, startTime, true,
        calibrationDataList, zeroMap)
      for (holdTime <- config.holdTime)
        calibrateTimerOpt = Some(system.scheduler.scheduleOnce(Duration(holdTime, SECONDS), self, DownStart))

    case DownStart =>
      logger.debug(s"${calibrationType} DownStart")
      context become calibrationHandler(connection, calibrationType, startTime, false,
        calibrationDataList, zeroMap)

      context.parent ! ExecuteSeq(T700_STANDBY_SEQ, true)

      calibrateTimerOpt = if (calibrationType.auto && calibrationType.zero) {
        self ! CalibrateEnd
        None
      } else {
        collectorState = MonitorStatus.CalibrationResume
        instrumentOp.setState(id, collectorState)
        for (downTime <- config.downTime) yield
          system.scheduler.scheduleOnce(Duration(downTime, SECONDS), self, CalibrateEnd)
      }

    case CalibrateEnd =>
      import scala.collection.mutable.Map
      val mtValueMap = Map.empty[String, List[Double]]
      for {
        record <- calibrationDataList
      } {
        val dataList = mtValueMap.getOrElseUpdate(record.mt, List.empty[Double])
        mtValueMap.put(record.mt, record.value :: dataList)
      }

      val mtAvgMap = mtValueMap map {
        mt_values =>
          val mt = mt_values._1
          val values = mt_values._2
          val avg = if (values.length == 0)
            None
          else
            Some(values.sum / values.length)
          mt -> avg
      }

      if (calibrationType.auto && calibrationType.zero) {
        logger.info(s"zero calibration end.")
        context become calibrationHandler(connection, AutoSpan, startTime, false, List.empty[MonitorTypeData], mtAvgMap.toMap)

        setupSpanRaiseStartTimer(connection)
      } else {
        logger.info(s"calibration end.")
        val monitorTypes = mtAvgMap.keySet.toList
        val calibrationList =
          if (calibrationType.auto) {
            for {
              mt <- monitorTypes
              zeroValue = zeroMap(mt)
              avg = mtAvgMap(mt)
            } yield Calibration(mt, startTime.toDate, com.github.nscala_time.time.Imports.DateTime.now.toDate, zeroValue, monitorTypeOp.map(mt).span, avg)
          } else {
            for {
              mt <- monitorTypes
              avg = mtAvgMap(mt)
            } yield {
              if (calibrationType.zero) {
                Calibration(mt, startTime.toDate, com.github.nscala_time.time.Imports.DateTime.now.toDate, avg, None, None)
              } else {
                Calibration(mt, startTime.toDate, com.github.nscala_time.time.Imports.DateTime.now.toDate, None, monitorTypeOp.map(mt).span, avg)
              }
            }
          }
        for (cal <- calibrationList)
          calibrationOp.insertFuture(cal)

        self ! SetState(id, MonitorStatus.NormalStat)
      }

    case CheckStatus =>
      reqFlameStatus(connection)
      reqPressure(connection)
      reqFlow(connection)

    case SetState(id, state) =>
      Future {
        blocking {
          if (state == MonitorStatus.NormalStat) {
            collectorState = state
            instrumentOp.setState(id, state)

            reqNormal(connection)
            calibrateTimerOpt map {
              timer => timer.cancel()
            }
            context.parent ! ExecuteSeq(T700_STANDBY_SEQ, true)
            context become connectionReady(connection)(false)
          } else {
            logger.info(s"Ignore setState $state during calibration")
          }
        }
      }
  }

  override def postStop() = {
    timer.cancel()
    statusTimer.cancel()
  }

  case object RaiseStart

  case object HoldStart

  case object DownStart

  case object CalibrateEnd


}