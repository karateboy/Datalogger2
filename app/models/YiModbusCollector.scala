package models

import akka.actor._
import com.google.inject.assistedinject.Assisted
import models.ModelHelper._
import models.Protocol.ProtocolParam
import play.api._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object YiModbusCollector {

  case object ConnectHost

  case object Collect

  var count = 0

  trait Factory {
    def apply(id: String, protocol: ProtocolParam): Actor
  }

}

import javax.inject._

class YiModbusCollector @Inject()
(instrumentOp: InstrumentDB)
(@Assisted id: String, @Assisted protocolParam: ProtocolParam) extends Actor with ActorLogging {

  import MoxaE1240Collector._

  @volatile var cancelable: Cancellable = _

  self ! ConnectHost

  def decode(value: Int): Unit = {
    val v = value.toDouble / 65535 * 971
    context.parent ! ReportData(
      List(
        MonitorTypeData(MonitorType.WIND_VOLUME_RAW, value.toDouble, MonitorStatus.NormalStat),
      MonitorTypeData(MonitorType.WIND_VOLUME, v, MonitorStatus.NormalStat)))
  }

  import com.serotonin.modbus4j._
  import com.serotonin.modbus4j.ip.IpParameters

  import scala.concurrent.{Future, blocking}

  def receive = handler(MonitorStatus.NormalStat, None)

  def handler(collectorState: String, masterOpt: Option[ModbusMaster]): Receive = {
    case ConnectHost =>
      log.info(s"connect to YiModbus")
      Future {
        blocking {
          try {
            val ipParameters = new IpParameters()
            ipParameters.setHost(protocolParam.host.get);
            ipParameters.setPort(502);
            val modbusFactory = new ModbusFactory()

            val master = modbusFactory.createTcpMaster(ipParameters, true)
            master.setTimeout(4000)
            master.setRetries(1)
            master.setConnected(true)
            master.init();
            context become handler(collectorState, Some(master))
            cancelable = context.system.scheduler.scheduleOnce(Duration(3, SECONDS), self, Collect)
          } catch {
            case ex: Exception =>
              Logger.error(ex.getMessage, ex)
              Logger.info("Try again 1 min later...")
              //Try again
              cancelable = context.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectHost)
          }

        }
      } onFailure errorHandler

    case Collect =>
      Future {
        blocking {
          try {
            import com.serotonin.modbus4j.BatchRead
            val batch = new BatchRead[Integer]

            import com.serotonin.modbus4j.code.DataType
            import com.serotonin.modbus4j.locator.BaseLocator


            batch.addLocator(0, BaseLocator.holdingRegister(1, 0x2025, DataType.TWO_BYTE_INT_UNSIGNED))

            batch.setContiguousRequests(true)

            assert(masterOpt.isDefined)

            val rawResult = masterOpt.get.send(batch)

            decode(rawResult.getIntValue(0))
            cancelable = context.system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(3, SECONDS), self, Collect)
          } catch {
            case ex: Throwable =>
              Logger.error("Read reg failed", ex)
              masterOpt.get.destroy()
              context become handler(collectorState, None)
              self ! ConnectHost
          }
        }
      } onFailure errorHandler

    case SetState(id, state) =>
      Logger.info(s"$self => $state")
      instrumentOp.setState(id, state)
      context become handler(state, masterOpt)
  }

  override def postStop(): Unit = {
    if (cancelable != null)
      cancelable.cancel()

  }
}