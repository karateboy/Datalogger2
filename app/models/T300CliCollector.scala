package models

import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam

import java.io.{BufferedReader, OutputStream}
import javax.inject.Inject

class T300CliCollector @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                                 alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                                 calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                                (@Assisted("instId") instId: String, @Assisted("desc") desc: String,
                                 @Assisted("config") deviceConfig: DeviceConfig,
                                 @Assisted("protocolParam") protocolParam: ProtocolParam)
  extends TapiTxxCliCollector(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
    alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
    calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)(instId, desc, deviceConfig, protocolParam){

  override val model = "T300"

  val dataInstrumentTypes = List(
    InstrumentStatusType(MonitorType.CO, 0, "CO", "PPM")
  )

  override def readDataReg(in: BufferedReader, out: OutputStream): List[(InstrumentStatusType, Double)] = {
    out.write("T CO\r\n".getBytes())
    Thread.sleep(500)
    readTillTimeout(in, expectOneLine = true).flatMap(line => {
      for ((_, _, value) <- getKeyUnitValue(line)) yield
        (dataInstrumentTypes.head, value)
    })
  }

  override def triggerVault(zero: Boolean, on: Boolean): Unit = {}

  override def readDataRegSerial(serial: SerialComm): List[(InstrumentStatusType, Double)] = {
    serial.port.writeBytes("T CO\r\n".getBytes())
    Thread.sleep(500)
    serial.getLine().flatMap(line => {
      for ((_, _, value) <- getKeyUnitValue(line)) yield
        (dataInstrumentTypes.head, value)
    })
  }
}
