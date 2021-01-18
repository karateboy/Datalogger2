package models

object ModbusCollector {
  import com.serotonin.modbus4j._
  import com.serotonin.modbus4j.ip.IpParameters
  System.setProperty("com.serotonin.modbus4j.ip.tcp.TcpMaster.level",
                         "INFO")
                         
  def main(args: Array[String]) {
    
    val ipParameters = new IpParameters()
    ipParameters.setHost("localhost");
    ipParameters.setPort(502);
    val modbusFactory = new ModbusFactory()
    val master = modbusFactory.createTcpMaster(ipParameters, true)
    master.setTimeout(4000)
    master.setRetries(1)
    import com.serotonin.modbus4j.BatchRead
    val batch = new BatchRead[Integer]

    import com.serotonin.modbus4j.code.DataType
    import com.serotonin.modbus4j.locator.BaseLocator
    batch.addLocator(0, BaseLocator.holdingRegister(100, 0, DataType.TWO_BYTE_INT_SIGNED));
    batch.addLocator(1, BaseLocator.holdingRegister(100, 1, DataType.TWO_BYTE_INT_SIGNED));
    batch.addLocator(2, BaseLocator.holdingRegister(100, 10, DataType.EIGHT_BYTE_FLOAT));
    try {
      master.init();

      for(count <- 1 to 2) {
        batch.setContiguousRequests(false)
        val results = master.send(batch)
        System.out.println(results.getValue(0))
        System.out.println(results.getValue(1))
        System.out.println(results.getValue(2))
        //Thread.sleep(2000);
      }
    } catch {
      case ex: com.serotonin.modbus4j.exception.ErrorResponseException =>
        System.out.println(ex.getErrorResponse().getExceptionMessage());
    } finally {
      master.destroy();
    }
  }
}