package models

import akka.actor.{Actor, Cancellable}
import com.github.nscala_time.time.Imports
import com.github.nscala_time.time.Imports.DateTime
import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam
import models.Sabio4010Collector._
import models.ThetaCollector.OpenComPort
import play.api.Logger
import play.api.libs.json.{JsError, Json, Reads}

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, MINUTES}
import scala.concurrent.{Future, blocking}

case class Sabio4010Config(address: String)

object Sabio4010 extends DriverOps {
  implicit val reads: Reads[Sabio4010Config] = Json.reads[Sabio4010Config]

  override def id: String = "sabio4010"

  override def description: String = "Sabio 4010 Calibrator"

  override def protocol: List[String] = List(Protocol.serial)

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

  override def factory(id: String, protocol: Protocol.ProtocolParam, param: String)(f: AnyRef, fOpt:Option[AnyRef]): Actor = {
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
  val InstrumentStatusTypeList: List[InstrumentStatusType] = List(
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

class Sabio4010Collector @Inject()(instrumentOp: InstrumentDB, instrumentStatusOp: InstrumentStatusDB)
                                  (@Assisted id: String, @Assisted protocolParam: ProtocolParam, @Assisted config: Sabio4010Config) extends Actor {
  import DataCollectManager._
  private val statusTimerOpt: Option[Cancellable] = None
  //val statusTimerOpt: Option[Cancellable] = Some(system.scheduler.schedule(Duration(5, MINUTES), Duration(10, MINUTES),
  //  self, CollectStatus))
  @volatile var cancelable: Cancellable = _
  @volatile var comm: SerialComm = _
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
          cancelable = context.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, OpenComPort)
      }

    case ExecuteSeq(seq, on) =>
      Future {
        blocking {
          def readResponse(): Unit = {
            var ret = Array.empty[Byte]
            do {
              ret = comm.port.readBytes()
            } while (ret == null)

            val char = ret(0)
            if (char == 0x6) {
              if(on)
                Logger.info(s"Execute $seq successfully.")
              else
                Logger.info(s"Execute STOP successfully.")
            } else {
              Logger.error(s"Execute $seq failed")
            }
          }

          try {
            if (on) {
              if (seq.toUpperCase == "PURGE") {
                val cmd = getCmdString("P", "")
                comm.os.write(cmd.getBytes)
                readResponse()
              } else {
                val cmd = getCmdString("TS", seq)
                comm.os.write(cmd.getBytes)
                readResponse()
              }
            } else {
              val cmd = getCmdString("S", "")
              comm.os.write(cmd.getBytes)
              readResponse()
            }
          } catch {
            case ex: Throwable =>
              Logger.error(s"failed at ${seq}, ${on}", ex)
          }
        }
      }

    case CollectStatus =>
      import instrumentStatusOp._
      var statusList = Seq.empty[InstrumentStatusDB.Status]
      Logger.info("Collect Sabio status")

      def extractStatus(subType: String, statusType: String, limit: Int): Unit = {
        comm.os.write(getCmdString("GS", subType).getBytes)

        var lines = comm.getMessageByCrWithTimeout(timeout = 3)
        if (lines.size == 1)
          lines = comm.getMessageByCrWithTimeout(timeout = 3)
        else
          lines = lines.drop(1)

        val tokens = lines.head.split(",")
        val dilutionStatuses =
          for ((token, idx) <- tokens.zipWithIndex if idx < limit) yield
            InstrumentStatusDB.Status(s"$statusType${idx + 1}", token.toDouble)
        statusList = statusList ++ dilutionStatuses
      }

      try {
        extractStatus("D", Dilution, 8)
        Logger.info(s"Collect D status ${statusList.size}")
        extractStatus("O", Ozone, 7)
        Logger.info(s"Collect O status ${statusList.size}")
        extractStatus("P", Lamp, 10)
        Logger.info(s"Collect P status ${statusList.size}")
        extractStatus("V", Perm, 4)
        Logger.info(s"Collect V status ${statusList.size}")
        extractStatus("G", Gas, 1)
        Logger.info(s"Collect G status ${statusList.size}")
        val is = InstrumentStatusDB.InstrumentStatus(DateTime.now().toDate, id, statusList.toList)
        instrumentStatusOp.log(is)
      } catch {
        case ex: Exception =>
          Logger.error("failed to get status", ex)
      }

  }

  private def getCmdString(cmd: String, param: String): String = {
    val cmdStr =
      if (param.isEmpty)
        s"@$cmd,${config.address}"
      else
        s"@$cmd,${config.address},${param}"

    assert(!cmdStr.endsWith(","))
    s"${cmdStr}\r"
  }

  override def postStop(): Unit = {
    super.postStop()
    statusTimerOpt map {
      _.cancel()
    }
    if (comm != null)
      comm.close
  }
}
