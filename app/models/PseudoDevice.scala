package models

import akka.actor.Actor
import com.github.nscala_time.time.Imports.LocalTime
import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam
import play.api.Logger

import javax.inject.Inject
import scala.concurrent.Future

object PseudoDevice extends AbstractDrv(_id = "PseudoDevice", name = "Pseudo Device - NO, NO2, NOX equal to 法規值",
  protocols = List(Protocol.tcp)) {

  val predefinedIST: List[InstrumentStatusType] =
    List(InstrumentStatusType(MonitorType.NO, 0, "NO", "ppm"),
      InstrumentStatusType(MonitorType.NO2, 1, "NO2", "ppm"),
      InstrumentStatusType(MonitorType.NOX, 2, "NOX", "ppm")
    )

  val map: Map[Int, InstrumentStatusType] = predefinedIST.map(p => p.addr -> p).toMap


  val dataAddress: List[Int] = List(0, 1, 2)

  override def getMonitorTypes(param: String): List[String] = List(MonitorType.NO, MonitorType.NO2, MonitorType.NOX)

  override def verifyParam(json: String): String = json

  override def getDataRegList: List[DataReg] =
    predefinedIST.filter(p => dataAddress.contains(p.addr)).map {
      ist =>
        DataReg(monitorType = ist.key, ist.addr, multiplier = 1)
    }

  override def getCalibrationTime(param: String): Option[LocalTime] = None

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt: Option[AnyRef]): Actor = {
    val f2 = f.asInstanceOf[PseudoDevice.Factory]
    val config = DeviceConfig.default
    f2(id, desc = super.description, config, protocol)
  }

  trait Factory {
    def apply(@Assisted("instId") instId: String, @Assisted("desc") desc: String, @Assisted("config") config: DeviceConfig,
              @Assisted("protocolParam") protocol: ProtocolParam): Actor
  }
}

class PseudoDeviceCollector @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                                      alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                                      calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                                     (@Assisted("instId") instId: String, @Assisted("desc") desc: String,
                                      @Assisted("config") deviceConfig: DeviceConfig,
                                      @Assisted("protocolParam") protocolParam: ProtocolParam)
  extends AbstractCollector(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
    alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
    calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)(instId, desc, deviceConfig, protocolParam) {


  override def probeInstrumentStatusType: Seq[InstrumentStatusType] = PseudoDevice.predefinedIST

  override def readReg(statusTypeList: List[InstrumentStatusType], full: Boolean): Future[Option[ModelRegValue2]] =
    Future.successful({
      val values = statusTypeList.map { statusType =>
        val value = statusType.key match {
          case MonitorType.NO =>
            if (zeroGate)
              0.0
            else {
              val mtCase = monitorTypeOp.map(MonitorType.NO)
              if (mtCase.std_law.isDefined)
                mtCase.std_law.get
              else
                1.0
            }
          case MonitorType.NO2 =>
            if (spanGate) {
              1.0
            } else {
              val mtCase = monitorTypeOp.map(MonitorType.NO2)
              if (mtCase.std_law.isDefined)
                mtCase.std_law.get
              else
                0.0
            }
          case MonitorType.NOX =>
            if (zeroGate)
              0.0
            else {
              val mtCase = monitorTypeOp.map(MonitorType.NOX)
              if (mtCase.std_law.isDefined)
                mtCase.std_law.get
              else
                1.0
            }
        }

        (statusType, value)
      }
      Some(ModelRegValue2(values,
        List.empty[(InstrumentStatusType, Boolean)],
        List.empty[(InstrumentStatusType, Boolean)]))
    })

  override def connectHost(): Unit = {}

  override def getDataRegList: Seq[DataReg] = PseudoDevice.getDataRegList

  @volatile private var zeroGate: Boolean = false
  @volatile private var spanGate: Boolean = false

  // zero is 0, span is 1
  override def getCalibrationReg: Option[CalibrationReg] = Some(CalibrationReg(0, 1))

  override def setCalibrationReg(address: Int, on: Boolean): Unit = {
    if (address == 0)
      zeroGate = on
    else if (address == 1)
      spanGate = on
  }

  override def postStop(): Unit = {
    super.postStop()
  }

  override def triggerVault(zero: Boolean, on: Boolean): Unit = {
    logger.info(s"Pseudo triggerVault zero=$zero on=$on")
    if (zero)
      setCalibrationReg(0, on)
    else
      setCalibrationReg(1, on)
  }

  override val logger: Logger = Logger(this.getClass)
}
