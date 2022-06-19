package models

import akka.actor.{Actor, ActorSystem}
import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam
import models.mongodb.{AlarmOp, CalibrationOp, InstrumentStatusOp}
import org.joda.time.Period
import play.api.Logger

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}
object Tca08Drv extends AbstractDrv(_id = "tca08", desp = "Total Carbon Analyzer TCA08",
  protocols = List(Protocol.serial)) {
  val predefinedIST = List(
    InstrumentStatusType(key = "TCconc", addr = 0, desc = "TC mass divided by a volume of sampled air", ""),
    InstrumentStatusType(key = "Volume", addr = 6, desc = "Volume of sampled air", "")
  )

  val dataAddress = List(0)

  override def getMonitorTypes(param: String): List[String] = {
    List(predefinedIST(0).key)
  }

  override def verifyParam(json: String) = json

  override def getDataRegList: List[DataReg] =
    predefinedIST.filter(p => dataAddress.contains(p.addr)).map {
      ist =>
        DataReg(monitorType = ist.key, ist.addr, multiplier = 1)
    }

  override def getCalibrationTime(param: String) = None

  trait Factory {
    def apply(@Assisted("instId") instId: String, @Assisted("desc") desc: String, @Assisted("config") config: DeviceConfig,
              @Assisted("protocolParam") protocol: ProtocolParam): Actor
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt:Option[AnyRef]): Actor = {
    val f2 = f.asInstanceOf[Tca08Drv.Factory]
    val config = DeviceConfig.default
    f2(id, desc = super.description, config, protocol)
  }

}

class Tca08Collector @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                               alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                               calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                              (@Assisted("instId") instId: String, @Assisted("desc") desc: String,
                               @Assisted("config") deviceConfig: DeviceConfig,
                               @Assisted("protocolParam") protocolParam: ProtocolParam)
  extends AbstractCollector(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
    alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
    calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)(instId, desc, deviceConfig, protocolParam) {


  override def probeInstrumentStatusType: Seq[InstrumentStatusType] = Tca08Drv.predefinedIST

  override def readReg(statusTypeList: List[InstrumentStatusType], full:Boolean): Future[Option[ModelRegValue2]] =
    Future{
      blocking{
        val ret = {
          for (serial <- serialOpt) yield {
            val cmd = "\u0002DA\u0003"
            val bytes = cmd.getBytes("UTF-8")
            serial.port.writeBytes(bytes)
            val resp = serial.getMessageByCrWithTimeout(1)
            if (resp.nonEmpty) {
              val tokens = resp(0).split(" ")
              val inputs =
                for (ist <- Tca08Drv.predefinedIST) yield {
                  val valueStr = tokens(2 + 6 * ist.addr)
                  val keys = valueStr.split("\\u002b")
                  val v = try{
                    if(keys.length == 3) {
                      val v = keys(1).toInt.toDouble
                      val mantissaExp = Math.log10(v).toInt
                      val exp = keys(2).toInt
                      v * Math.pow(10, exp - mantissaExp)
                    } else if (keys.length == 2) { //minus case
                      val v = keys(0).toInt.toDouble
                      val mantissaExp = Math.log10(v).toInt
                      val exp = keys(1).toInt
                      v * Math.pow(10, exp - mantissaExp)
                    }else
                      0d
                  }catch{
                    case _:Throwable=>
                      0d
                  }
                  (ist, v)
                }
              Some(ModelRegValue2(inputRegs = inputs,
                modeRegs = List.empty[(InstrumentStatusType, Boolean)],
                warnRegs = List.empty[(InstrumentStatusType, Boolean)]))
            }else
              None
          }
        }
        ret.flatten
      }
    }


  var serialOpt: Option[SerialComm] = None

  override def connectHost: Unit = {
    serialOpt =
      Some(SerialComm.open(protocolParam.comPort.get, protocolParam.speed.getOrElse(115200)))
  }

  override def getDataRegList: Seq[DataReg] = Tca08Drv.getDataRegList

  override def getCalibrationReg: Option[CalibrationReg] = None

  override def setCalibrationReg(address: Int, on: Boolean): Unit = {}

  override def postStop(): Unit = {
    for (serial <- serialOpt)
      serial.port.closePort()

    super.postStop()
  }
}

