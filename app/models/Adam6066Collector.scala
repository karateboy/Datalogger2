package models

import akka.actor._
import com.google.inject.assistedinject.Assisted
import models.ModelHelper._
import models.Protocol.ProtocolParam
import play.api._

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

case class Adam6066ChannelCfg(enable: Boolean, mt: Option[String], repairMode: Option[Boolean])

case class Adam6066Param(chs: Seq[Adam6066ChannelCfg])

object Adam6066Collector {

  var count = 0

  def start(id: String, protocolParam: ProtocolParam, param: Adam6017Param)(implicit context: ActorContext) = {
    val prop = Props(classOf[Adam6066Collector], id, protocolParam, param)
    val collector = context.actorOf(prop, name = "Adam6077Collector" + count)
    count += 1
    assert(protocolParam.protocol == Protocol.tcp)
    collector ! ConnectHost
    collector

  }

  trait Factory {
    def apply(id: String, protocol: ProtocolParam, param: Adam6066Param): Actor
  }

  case object ConnectHost

  case object Collect

}

class Adam6066Collector @Inject()
(instrumentOp: InstrumentOp, monitorTypeOp: MonitorTypeOp, system: ActorSystem)
(@Assisted id: String, @Assisted protocolParam: ProtocolParam, @Assisted param: Adam6066Param) extends Actor with ActorLogging {

  import MoxaE1212Collector._

  self ! ConnectHost

  import com.serotonin.modbus4j._
  import com.serotonin.modbus4j.ip.IpParameters

  import scala.concurrent.{Future, blocking}
  var cancelable: Cancellable = _

  def receive = handler(MonitorStatus.NormalStat, None, Map.empty[Int, Boolean])

  def handler(collectorState: String, masterOpt: Option[ModbusMaster], diValueMap: Map[Int, Boolean]): Receive = {
    case ConnectHost =>
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
            context become handler(collectorState, Some(master), diValueMap)
            import scala.concurrent.duration._
            cancelable = system.scheduler.scheduleOnce(Duration(3, SECONDS), self, Collect)
            self ! WriteDO(bit = 16, on = false)
          } catch {
            case ex: Exception =>
              Logger.error(ex.getMessage, ex)
              Logger.info("Try again 1 min later...")
              //Try again
              import scala.concurrent.duration._
              cancelable = system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectHost)
          }

        }
      } onFailure errorHandler

    case Collect =>
      Future {
        blocking {
          try {
            import com.serotonin.modbus4j.BatchRead
            import com.serotonin.modbus4j.locator.BaseLocator

            // DI Value ...
            var newDiValueMap = diValueMap

            val batch = new BatchRead[Integer]
            for (idx <- 0 to 7)
              batch.addLocator(idx, BaseLocator.inputStatus(1, idx))

            batch.setContiguousRequests(true)

            val rawResult = masterOpt.get.send(batch)
            val result =
              for (idx <- 0 to 7) yield rawResult.getValue(idx).asInstanceOf[Boolean]

            for {
              cfg <- param.chs.zipWithIndex
              chCfg = cfg._1 if chCfg.enable && chCfg.mt.isDefined
              mt = chCfg.mt.get
              idx = cfg._2
              v = result(idx)
            } yield {
              newDiValueMap = newDiValueMap + (idx -> v)
              // Log on difference
              if (!diValueMap.contains(idx) || diValueMap(idx) != v)
                monitorTypeOp.logDiMonitorType(mt, v)
            }
            context become handler(collectorState, masterOpt, newDiValueMap)


            import scala.concurrent.duration._
            cancelable = system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(3, SECONDS), self, Collect)
          } catch {
            case ex: Throwable =>
              Logger.error("Read reg failed", ex)
              masterOpt.get.destroy()
              context become handler(collectorState, None, diValueMap)
              self ! ConnectHost
          }
        }
      } onFailure errorHandler

    case SetState(id, state) =>
      Logger.info(s"$self => $state")
      instrumentOp.setState(id, state)
      context become handler(state, masterOpt, diValueMap)

    case WriteDO(bit, on) =>
      Logger.info(s"Output DO $bit to $on")
      try {
        import com.serotonin.modbus4j.locator.BaseLocator
        val locator = BaseLocator.coilStatus(1, bit)
        masterOpt map {
          master => master.setValue(locator, on)
        }
        // Refresh status
        self ! Collect
      } catch {
        case ex: Exception =>
          ModelHelper.logException(ex)
      }
  }

  override def postStop(): Unit = {
    if (cancelable != null)
      cancelable.cancel()
  }
}