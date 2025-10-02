package models

import akka.actor.Actor
import com.github.nscala_time.time.Imports.LocalTime
import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam

import javax.inject.Inject
import scala.concurrent.Future

object PseudoDevice3 extends AbstractDrv(_id = "PseudoDevice3", desp = "Pseudo Device 3 - Wind Speed, Direction",
  protocols = List(Protocol.tcp)) {

  private val predefinedIST: List[InstrumentStatusType] =
    List(InstrumentStatusType(MonitorType.WIN_SPEED, 0, "Wind Speed", "m/s"),
      InstrumentStatusType(MonitorType.WIN_DIRECTION, 1, "Wind Direction", "degree")
    )

  val map: Map[Int, InstrumentStatusType] = predefinedIST.map(p => p.addr -> p).toMap


  val dataAddress: List[Int] = List(0, 1)

  override def getMonitorTypes(param: String): List[String] = List(MonitorType.WIN_SPEED, MonitorType.WIN_DIRECTION)

  override def verifyParam(json: String): String = json

  override def getDataRegList: List[DataReg] =
    predefinedIST.filter(p => dataAddress.contains(p.addr)).map {
      ist =>
        DataReg(monitorType = ist.key, ist.addr, multiplier = 1)
    }

  override def getCalibrationTime(param: String): Option[LocalTime] = None

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt: Option[AnyRef]): Actor = {
    val f2 = f.asInstanceOf[PseudoDevice3.Factory]
    val config = DeviceConfig.default
    f2(id, desc = super.description, config, protocol)
  }

  trait Factory {
    def apply(@Assisted("instId") instId: String, @Assisted("desc") desc: String, @Assisted("config") config: DeviceConfig,
              @Assisted("protocolParam") protocol: ProtocolParam): Actor
  }
}

class PseudoDevice3 @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                              alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                              calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                             (@Assisted("instId") instId: String, @Assisted("desc") desc: String,
                              @Assisted("config") deviceConfig: DeviceConfig,
                              @Assisted("protocolParam") protocolParam: ProtocolParam)
  extends AbstractCollector(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
    alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
    calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)(instId, desc, deviceConfig, protocolParam) {


  override def probeInstrumentStatusType: Seq[InstrumentStatusType] = PseudoDevice3.predefinedIST

  var currentDirection: Double = 0.0
  var currentSpeed: Double = 0.0

  override def readReg(statusTypeList: List[InstrumentStatusType], full: Boolean): Future[Option[ModelRegValue2]] =
    Future.successful({
      val values = statusTypeList.map { statusType =>
        val value = statusType.key match {
          case MonitorType.WIN_SPEED =>
            currentSpeed += 0.5
            currentSpeed
          case MonitorType.WIN_DIRECTION =>
            if (currentDirection >= 360.0)
              currentDirection = 0.0
            else
              currentDirection += 1.0
            currentDirection
        }

        (statusType, value)
      }
      Some(ModelRegValue2(values,
        List.empty[(InstrumentStatusType, Boolean)],
        List.empty[(InstrumentStatusType, Boolean)]))
    })

  override def connectHost: Unit = {}

  override def getDataRegList: Seq[DataReg] = PseudoDevice3.getDataRegList

  // zero is 0, span is 1
  override def getCalibrationReg: Option[CalibrationReg] = Some(CalibrationReg(0, 1))

  override def setCalibrationReg(address: Int, on: Boolean): Unit = {}

  override def postStop(): Unit = {
    super.postStop()
  }

  override def triggerVault(zero: Boolean, on: Boolean): Unit = {
    log.info(s"Pseudo triggerVault zero=$zero on=$on")
  }
}
