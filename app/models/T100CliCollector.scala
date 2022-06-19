package models

import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam

import java.io.{BufferedReader, OutputStream}
import javax.inject.Inject

class T100CliCollector @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                                 alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                                 calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                                (@Assisted("instId") instId: String, @Assisted("desc") desc: String,
                                 @Assisted("config") deviceConfig: DeviceConfig,
                                 @Assisted("protocolParam") protocolParam: ProtocolParam)
  extends TapiTxxCliCollector(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
    alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
    calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)(instId, desc, deviceConfig, protocolParam) {

  override val model: String = "T100"
  val dataInstrumentTypes = List(
    InstrumentStatusType(MonitorType.SO2, 0, "SO2", "ppb")
  )

  override def readDataReg(in: BufferedReader, out: OutputStream): List[(InstrumentStatusType, Double)] = {
    out.write("T SO2\r\n".getBytes())
    Thread.sleep(500)
    readTillTimeout(in, expectOneLine = true).flatMap(line => {
      for ((_, _, value) <- getKeyUnitValue(line)) yield
        (dataInstrumentTypes(0), value)
    })
  }
}
