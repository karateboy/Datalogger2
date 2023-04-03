package models

import akka.actor.Actor
import com.github.nscala_time.time.Imports.LocalTime
import com.google.inject.assistedinject.Assisted
import com.typesafe.config.ConfigFactory
import models.Protocol.ProtocolParam
import play.api.Logger
import play.api.libs.json.{JsError, Json}

import java.io.File

case class AkReg(addr:Int, desc:String)
case class AkModelReg(dataRegs: Seq[DataReg], inputRegs: Seq[AkReg], modeRegs: Seq[AkReg], warningRegs: Seq[AkReg])
case class AkDeviceModel(id: String, description: String, akModelReg: AkModelReg)
case class AkDeviceConfig(stationNo: String, channelNum:String, calibrationTime: Option[LocalTime], monitorTypes: Option[Seq[String]],
                        raiseTime: Option[Int], downTime: Option[Int], holdTime: Option[Int],
                        calibrateZeoSeq: Option[String], calibrateSpanSeq: Option[String],
                        calibratorPurgeSeq: Option[String], calibratorPurgeTime: Option[Int],
                        calibrateZeoDO: Option[Int], calibrateSpanDO: Option[Int], skipInternalVault: Option[Boolean])

class AkDrv(_id:String, desp:String, protocols:List[String], tcpModelReg: AkModelReg) extends DriverOps {
  implicit val cfgReads = Json.reads[AkDeviceConfig]
  implicit val cfgWrites = Json.writes[AkDeviceConfig]

  override def verifyParam(json: String) = {
    val ret = Json.parse(json).validate[AkDeviceConfig]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => {
        val mt = tcpModelReg.dataRegs.map(_.monitorType)
        assert(param.stationNo.length == 1)
        assert(param.channelNum.length == 2)
        val newParam = AkDeviceConfig(param.stationNo, param.channelNum, param.calibrationTime, Some(mt),
          param.raiseTime, param.downTime, param.holdTime,
          param.calibrateZeoSeq, param.calibrateSpanSeq,
          param.calibratorPurgeSeq, param.calibratorPurgeTime,
          param.calibrateZeoDO, param.calibrateSpanDO,
          param.skipInternalVault)
        Json.toJson(newParam).toString()
      })
  }

  override def getMonitorTypes(param: String): List[String] = {
    val config = validateParam(param)
    config.monitorTypes.getOrElse(List.empty[String]).toList
  }

  def validateParam(json: String): AkDeviceConfig = {
    val ret = Json.parse(json).validate[AkDeviceConfig]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => param)
  }

  override def getCalibrationTime(param: String) = {
    val config = validateParam(param)
    config.calibrationTime
  }

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt:Option[AnyRef]): Actor = {
    assert(f.isInstanceOf[AkDrv.Factory])
    val f2 = f.asInstanceOf[AkDrv.Factory]
    val config = validateParam(param)
    f2(id, protocol, tcpModelReg, config)
  }

  override def id: String = _id

  override def description: String = desp

  override def protocol: List[String] = protocols
}

object AkDrv {
  val deviceTypeHead = "AkProtocol."
  def getInstrumentTypeList(environment: play.api.Environment, factory: AkDrv.Factory, monitorTypeOp: MonitorTypeDB): Array[InstrumentType] = {
    val docRoot = environment.rootPath + "/conf/AkProtocol/"
    val files = new File(docRoot).listFiles()
    for (file <- files) yield {
      val device: AkDeviceModel = getDeviceModel(file)

      InstrumentType(
        new AkDrv(s"${deviceTypeHead}${device.id}", device.description, List(Protocol.serial), device.akModelReg), factory)
    }
  }

  def getDeviceModel(modelFile: File): AkDeviceModel = {
    val driverConfig = ConfigFactory.parseFile(modelFile)
    import java.util.ArrayList

    val id = driverConfig.getString("ID")
    val description = driverConfig.getString("description")
    def getRegList(name: String) = {
      val regList = driverConfig.getAnyRefList(name)
      for {
        i <- 0 to regList.size() - 1
        reg = regList.get(i)
        v = reg.asInstanceOf[ArrayList[Any]]
      } yield
        v
    }
    def getAkRegList(name:String) =
      getRegList(name) map {
        v=>
        AkReg(v.get(0).asInstanceOf[Int], v.get(1).asInstanceOf[String])
      }

    val inputRegs = getAkRegList("inputRegs")
    val modeRegs = getAkRegList("modeRegs")
    val warningRegs = getAkRegList("warningRegs")
    val dataRegs =
      getRegList("Data") map {
        v=>
          val multiplier = if(v.size() <3 )
            1f
          else
            v.get(2).asInstanceOf[Double].toFloat
        DataReg(v.get(0).asInstanceOf[String], v.get(1).asInstanceOf[Int], multiplier)
      }

    AkDeviceModel(id, description,
      AkModelReg(dataRegs = dataRegs, inputRegs=inputRegs, modeRegs = modeRegs, warningRegs = warningRegs))
  }

  trait Factory {
    def apply(@Assisted instId: String, protocolParam: ProtocolParam, modelReg: AkModelReg, config: AkDeviceConfig): Actor
  }

  case object OpenCom
  case object ReadRegister
}
