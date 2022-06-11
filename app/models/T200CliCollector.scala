package models

import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam

import java.io.{BufferedReader, OutputStream}
import javax.inject.Inject

class T200CliCollector @Inject()(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
                                 alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
                                 calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)
                                (@Assisted("instId") instId: String, @Assisted("desc") desc: String,
                                 @Assisted("config") deviceConfig: DeviceConfig,
                                 @Assisted("protocolParam") protocolParam: ProtocolParam)
  extends TapiTxxCliCollector(instrumentOp: InstrumentDB, monitorStatusOp: MonitorStatusDB,
  alarmOp: AlarmDB, monitorTypeOp: MonitorTypeDB,
  calibrationOp: CalibrationDB, instrumentStatusOp: InstrumentStatusDB)(instId, desc, deviceConfig, protocolParam) {

  override val model = "T200"

  val dataInstrumentTypes = List(
    InstrumentStatusType(MonitorType.NOX, 0, "NOX", "ppb"),
    InstrumentStatusType(MonitorType.NO, 1, "NO", "ppb"),
    InstrumentStatusType(MonitorType.NO2, 2, "NO2", "ppb")
  )

  override def readDataReg(in: BufferedReader, out: OutputStream): List[(InstrumentStatusType, Double)] = {
    out.write("T NOX\r\n".getBytes())
    Thread.sleep(500)
    val data0: List[(InstrumentStatusType, Double)] = readTillTimeout(in, expectOneLine = true).flatMap(line => {
      for ((_, _, value) <- getKeyUnitValue(line)) yield
        (dataInstrumentTypes(0), value)
    })
    if (data0.isEmpty)
      throw new Exception("no data")

    out.write("T NO\r\n".getBytes())
    Thread.sleep(500)
    val data1: List[(InstrumentStatusType, Double)] = readTillTimeout(in, expectOneLine = true).flatMap(line => {
      for ((_, _, value) <- getKeyUnitValue(line)) yield
        (dataInstrumentTypes(1), value)
    })
    if (data1.isEmpty)
      throw new Exception("no data")

    val data2 = List((dataInstrumentTypes(2), data0(0)._2 - data1(0)._2))
    data0 ++ data1 ++ data2
  }
}
