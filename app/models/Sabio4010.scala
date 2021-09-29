package models

import akka.actor.{Actor, ActorSystem, Cancellable}
import com.github.nscala_time.time.Imports
import com.github.nscala_time.time.Imports.DateTime
import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam
import models.Sabio4010Collector._
import models.ThetaCollector.OpenComPort
import play.api.Logger
import play.api.libs.json.{JsError, Json}

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, MINUTES, SECONDS}
import scala.concurrent.{Future, blocking}

case class Sabio4010Config(address: String)

object Sabio4010 extends DriverOps {
  implicit val reads = Json.reads[Sabio4010Config]

  override def id: String = "sabio4010"

  override def description: String = "Sabio 4010 Calibrator"

  override def protocol: List[Protocol.Value] = List(Protocol.serial)

  override def verifyParam(param: String): String = {
    val ret = Json.parse(param).validate[Sabio4010Config]
    ret.fold(err => {
      Logger.error(JsError.toJson(err).toString())
      throw new Exception(JsError.toJson(err).toString())
    },
      config => param)
  }

  override def getMonitorTypes(param: String): List[String] = List.empty[String]

  override def getCalibrationTime(param: String): Option[Imports.LocalTime] = None

  override def factory(id: String, protocol: Protocol.ProtocolParam, param: String)(f: AnyRef): Actor = {
    assert(f.isInstanceOf[Factory])
    val f2 = f.asInstanceOf[Factory]
    val config = Json.parse(param).validate[Sabio4010Config].get
    f2(id, protocol, config)
  }

  override def isCalibrator: Boolean = true

  trait Factory {
    def apply(id: String, protocol: ProtocolParam, config: Sabio4010Config): Actor
  }
}

object Sabio4010Collector {
  val Dilution = "Dilution"
  val Ozone = "Ozone"
  val Lamp = "Lamp"
  val Perm = "Perm"
  val Gas = "Gas"
  val InstrumentStatusTypeList = List(
    InstrumentStatusType(Dilution + 1, 1, "稀釋MFC設定點(SCCM)", "SCCM"),
    InstrumentStatusType(Dilution + 2, 2, "已測量的MFC流速", "SCCM"),
    InstrumentStatusType(Dilution + 3, 3, "臭氧MFC設定點", "kPa"),
    InstrumentStatusType(Dilution + 4, 4, "已測量的臭氧MFC流速", "kPa"),
    InstrumentStatusType(Dilution + 5, 5, "氣源MFC 數值", "1/2"),
    InstrumentStatusType(Dilution + 6, 6, "氣源MFC 定點", "SCCM"),
    InstrumentStatusType(Dilution + 7, 7, "已測量的氣源MFC 流速", "SCCM"),
    InstrumentStatusType(Dilution + 8, 8, "系統溫度", "℃"),
    InstrumentStatusType(Ozone + 1, 1, "臭氧燈號溫度設定點", "℃"),
    InstrumentStatusType(Ozone + 2, 2, "已測量的臭氧燈號溫度", "℃"),
    InstrumentStatusType(Ozone + 3, 3, "臭氧燈號強度設定點", "V"),
    InstrumentStatusType(Ozone + 4, 4, "臭氧燈號電流", "N/A"),
    InstrumentStatusType(Ozone + 5, 5, "已測量的臭氧燈號強度", "N/A"),
    InstrumentStatusType(Ozone + 6, 6, "臭氧濃度設定點", "PB"),
    InstrumentStatusType(Ozone + 7, 7, "平均臭氧測量值", "PPB"),
    InstrumentStatusType(Lamp + 1, 1, "光度計測量值", "PPB"),
    InstrumentStatusType(Lamp + 2, 2, "光度計燈號溫度設定點", "℃"),
    InstrumentStatusType(Lamp + 3, 3, "光度計燈號強度設定點", "V"),
    InstrumentStatusType(Lamp + 4, 4, "光度計燈號電流", "N/A"),
    InstrumentStatusType(Lamp + 5, 5, "光度計燈號強度", "N/A"),
    InstrumentStatusType(Lamp + 6, 6, "光度計偵測器－樣本", "N/A"),
    InstrumentStatusType(Lamp + 7, 7, "光度計偵測器－參考值", "N/A"),
    InstrumentStatusType(Lamp + 8, 8, "樣本氣體溫度", "℃"),
    InstrumentStatusType(Lamp + 9, 9, "樣本氣體壓力", "mmHg"),
    InstrumentStatusType(Lamp + 10, 10, "樣本氣體流速", "SCCM"),
    InstrumentStatusType(Perm + 1, 1, "滲透MFC設定點", "SCCM"),
    InstrumentStatusType(Perm + 2, 2, "已測量的MFC流速", "SCCM"),
    InstrumentStatusType(Perm + 3, 3, "滲透烘箱溫度設定點", "℃"),
    InstrumentStatusType(Perm + 4, 4, "已測量的透烘箱溫度", "℃"),
    InstrumentStatusType(Gas + 1, 1, "已測量的總流速", "SCCM")
  )

  case object OpenComPort

  case object CollectStatus
}

class Sabio4010Collector @Inject()(system: ActorSystem, instrumentOp: InstrumentOp, instrumentStatusOp: InstrumentStatusOp)
                                  (@Assisted id: String, @Assisted protocolParam: ProtocolParam, @Assisted config: Sabio4010Config) extends Actor {
  val statusTimerOpt: Option[Cancellable] = Some(system.scheduler.schedule(Duration(10, SECONDS), Duration(10, MINUTES),
    self, CollectStatus))
  var cancelable: Cancellable = _
  var comm: SerialComm = _
  var (collectorState, instrumentStatusTypesOpt) = {
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

  self ! OpenComPort

  override def receive: Receive = {
    case OpenComPort =>
      try {
        comm = SerialComm.open(protocolParam.comPort.get)
      } catch {
        case ex: Exception =>
          Logger.error(ex.getMessage, ex)
          Logger.info("Try again 1 min later...")
          //Try again
          cancelable = system.scheduler.scheduleOnce(Duration(1, MINUTES), self, OpenComPort)
      }

    case ExecuteSeq(seq, on) =>
      Future {
        blocking {
          def readResponse(): Unit = {
            val char = comm.is.read()
            if (char == 0x6)
              Logger.info(s"Execute $seq successfully.")
            else {
              Logger.error(s"Execute $seq failed")
            }
          }

          if (on) {
            if (seq.toUpperCase == "PURGE") {
              val cmd = getCmdString("P", "")
              comm.os.write(cmd.getBytes)
              readResponse()
            } else {
              val cmd = getCmdString("MS", seq)
              comm.os.write(cmd.getBytes)
              readResponse()
            }
          } else {
            val cmd = getCmdString("S", "")
            comm.os.write(cmd.getBytes)
            readResponse()
          }
        }
      }
    case CollectStatus =>
      Future {
        blocking {
          import instrumentStatusOp._
          var statusList = Seq.empty[Status]
          Logger.info("Collect Sabio status")

          def getStatus(subType: String, statusType: String, limit: Int): Unit = {
            comm.os.write(getCmdString("GS", subType).getBytes)
            var lines = comm.getLine3(timeout = 1)
            if (lines.size == 1)
              lines = comm.getLine3(timeout = 1)
            else
              lines = lines.drop(1)

            val tokens = lines(0).split(",")
            val dilutionStatuses =
              for ((token, idx) <- tokens.zipWithIndex if idx < limit) yield
                Status(s"$statusType${idx + 1}", token.toDouble)
            statusList = statusList ++ dilutionStatuses
          }

          try {
            getStatus("D", Dilution, 8)
            Logger.info(s"Collect D status ${statusList.size}")
            getStatus("O", Ozone, 7)
            Logger.info(s"Collect O status ${statusList.size}")
            getStatus("P", Lamp, 10)
            Logger.info(s"Collect P status ${statusList.size}")
            getStatus("V", Perm, 4)
            Logger.info(s"Collect V status ${statusList.size}")
            getStatus("G", Gas, 1)
            Logger.info(s"Collect G status ${statusList.size}")
            val is = instrumentStatusOp.InstrumentStatus(DateTime.now(), id, statusList.toList)
            instrumentStatusOp.log(is)
          } catch {
            case ex: Exception =>
              Logger.error("failed to get status", ex)
          }

        }
      }
  }

  def getCmdString(cmd: String, param: String): String = {
    val cmdStr =
      if (param.isEmpty)
        s"@$cmd,${config.address}"
      else
        s"@$cmd,${config.address},${param}"

    assert(!cmdStr.endsWith(","))
    s"${cmdStr}\r"
  }

  override def postStop() {
    super.postStop()
    statusTimerOpt map {
      _.cancel()
    }
    if (comm != null)
      comm.close
  }
}