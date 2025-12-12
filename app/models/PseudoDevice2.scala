package models

import akka.actor.Actor
import com.github.nscala_time.time.Imports.LocalTime
import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam
import play.api.Logger

import javax.inject.Inject
import scala.concurrent.Future

object PseudoDevice2 extends AbstractDrv(_id = "PseudoDevice2", desp = "Pseudo Device 2 - LeqA, LeqZ",
  protocols = List(Protocol.tcp)) {

  private val predefinedIST: List[InstrumentStatusType] =
    List(InstrumentStatusType(MonitorType.LEQA, 0, "LeqA", "dB"),
      InstrumentStatusType(MonitorType.LEQZ, 1, "LeqZ", "dB")
    )

  val map: Map[Int, InstrumentStatusType] = predefinedIST.map(p => p.addr -> p).toMap


  val dataAddress: List[Int] = List(0, 1)

  override def getMonitorTypes(param: String): List[String] = List(MonitorType.LEQA, MonitorType.LEQZ)

  override def verifyParam(json: String): String = json

  override def getDataRegList: List[DataReg] =
    predefinedIST.filter(p => dataAddress.contains(p.addr)).map {
      ist =>
        DataReg(monitorType = ist.key, ist.addr, multiplier = 1)
    }

  override def getCalibrationTime(param: String): Option[LocalTime] = None

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt: Option[AnyRef]): Actor = {
    val f2 = f.asInstanceOf[PseudoDevice2.Factory]
    val config = DeviceConfig.default
    f2(id, desc = super.description, config, protocol)
  }

  trait Factory {
    def apply(@Assisted("instId") instId: String, @Assisted("desc") desc: String, @Assisted("config") config: DeviceConfig,
              @Assisted("protocolParam") protocol: ProtocolParam): Actor
  }
}

class PseudoDevice2 @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                              alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                              calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                             (@Assisted("instId") instId: String, @Assisted("desc") desc: String,
                                      @Assisted("config") deviceConfig: DeviceConfig,
                                      @Assisted("protocolParam") protocolParam: ProtocolParam)
  extends AbstractCollector(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
    alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
    calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)(instId, desc, deviceConfig, protocolParam) {


  override def probeInstrumentStatusType: Seq[InstrumentStatusType] = PseudoDevice2.predefinedIST

  override def readReg(statusTypeList: List[InstrumentStatusType], full: Boolean): Future[Option[ModelRegValue2]] =
    Future.successful({
      val values = statusTypeList.map { statusType =>
        val value = statusType.key match {
          case MonitorType.LEQA =>
            70.0
          case MonitorType.LEQZ =>
            60.0
        }

        (statusType, value)
      }
      Some(ModelRegValue2(values,
        List.empty[(InstrumentStatusType, Boolean)],
        List.empty[(InstrumentStatusType, Boolean)]))
    })

  override def connectHost: Unit = {}

  override def getDataRegList: Seq[DataReg] = PseudoDevice2.getDataRegList

  // zero is 0, span is 1
  override def getCalibrationReg: Option[CalibrationReg] = Some(CalibrationReg(0, 1))

  override def setCalibrationReg(address: Int, on: Boolean): Unit = {}

  override def postStop(): Unit = {
    super.postStop()
  }

  override def triggerVault(zero: Boolean, on: Boolean): Unit = {
    logger.info(s"Pseudo triggerVault zero=$zero on=$on")
  }
  override val logger: Logger = Logger(this.getClass)
}
