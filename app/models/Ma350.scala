package models

import akka.actor.{Actor, ActorSystem}
import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam
import models.mongodb.{AlarmOp, CalibrationOp, InstrumentStatusOp}
import play.api.Logger

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}

object Ma350Drv extends AbstractDrv(_id = "MA350", desp = "microAeth MA350",
  protocols = List(Protocol.serial)) {
  // tape position,flow total,UV BCc,Blue BCc,Green BCc,Red BCc, IR BCc
  val predefinedIST = List(
    InstrumentStatusType(key = "UV BCc", addr = 1, desc = "UV BCc", ""),
    InstrumentStatusType(key = "Blue BCc", addr = 2, desc = "Blue BCc", ""),
    InstrumentStatusType(key = "Green BCc", addr = 3, desc = "Green BCc", ""),
    InstrumentStatusType(key = "Red BCc", addr = 4, desc = "Red BCc", ""),
    InstrumentStatusType(key = "IR BCc", addr = 5, desc = "IR BCc", ""),
    InstrumentStatusType(key = "flow total", addr = 6, desc = "flow total", ""),
    InstrumentStatusType(key = "tape position", addr = 7, desc = "tape position", "")
  )

  val map: Map[Int, InstrumentStatusType] = predefinedIST.map(p=>p.addr->p).toMap


  val dataAddress = List(5)

  override def getMonitorTypes(param: String): List[String] = {
    predefinedIST.filter(p=>dataAddress.contains(p.addr)).map(_.key)
  }

  override def verifyParam(json: String) = json

  override def getDataRegList: List[DataReg] =
    predefinedIST.filter(p => dataAddress.contains(p.addr)).map {
      ist =>
        DataReg(monitorType = ist.key, ist.addr, multiplier = 1)
    }

  override def getCalibrationTime(param: String) = None

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt:Option[AnyRef]): Actor = {
    val f2 = f.asInstanceOf[Ma350Drv.Factory]
    val config = DeviceConfig.default
    f2(id, desc = super.description, config, protocol)
  }

  trait Factory {
    def apply(@Assisted("instId") instId: String, @Assisted("desc") desc: String, @Assisted("config") config: DeviceConfig,
              @Assisted("protocolParam") protocol: ProtocolParam): Actor
  }
}

class Ma350Collector @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                               alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                               calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                              (@Assisted("instId") instId: String, @Assisted("desc") desc: String,
                               @Assisted("config") deviceConfig: DeviceConfig,
                               @Assisted("protocolParam") protocolParam: ProtocolParam)
  extends AbstractCollector(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
    alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
    calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)(instId, desc, deviceConfig, protocolParam) {


  var serialOpt: Option[SerialComm] = None

  override def probeInstrumentStatusType: Seq[InstrumentStatusType] = Ma350Drv.predefinedIST

  override def readReg(statusTypeList: List[InstrumentStatusType], full:Boolean): Future[Option[ModelRegValue2]] =
    Future {
      blocking {
        try{
          val ret =
            for (serial <- serialOpt) yield {
              val cmd = HessenProtocol.dataQuery()
              serial.port.writeBytes(cmd)
              val replies = serial.getMessageByLfWithTimeout(timeout = 2)
              val inputs =
                for (reply <- replies.filter(_.startsWith("MD"))) yield {
                  val measureList = HessenProtocol.decode(reply)
                  for {
                    (measure, addr) <- measureList.zipWithIndex
                  } yield{
                    val ist = Ma350Drv.map(addr+1)
                    (ist, measure.value)
                  }
                }
              Some(ModelRegValue2(inputRegs = inputs.flatten,
                modeRegs = List.empty[(InstrumentStatusType, Boolean)],
                warnRegs = List.empty[(InstrumentStatusType, Boolean)]))
            }
          ret.flatten
        }catch{
          case ex:Throwable=>
            Logger.error("MA350 readReg error", ex)
            None
        }
      }
    }

  override def connectHost: Unit = {
    serialOpt =
      Some(SerialComm.open(protocolParam.comPort.get, protocolParam.speed.getOrElse(115200)))
  }

  override def getDataRegList: Seq[DataReg] = Ma350Drv.getDataRegList

  override def getCalibrationReg: Option[CalibrationReg] = None

  override def setCalibrationReg(address: Int, on: Boolean): Unit = {}

  override def postStop(): Unit = {
    for (serial <- serialOpt)
      serial.port.closePort()

    super.postStop()
  }
}
