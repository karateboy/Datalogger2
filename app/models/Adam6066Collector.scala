package models

import akka.actor._
import com.google.inject.assistedinject.Assisted
import models.ModelHelper._
import models.Protocol.ProtocolParam
import play.api._

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

case class Adam6066ChannelCfg(enable: Boolean, mt: Option[String])

case class Adam6066Param(chs: Seq[Adam6066ChannelCfg])

object Adam6066Collector {

  var count = 0

  def start(id: String, protocolParam: ProtocolParam, param: Adam6017Param)(implicit context: ActorContext) = {
    val prop = Props(classOf[Adam6066Collector], id, protocolParam, param)
    val collector = context.actorOf(prop, name = "Adam6077Collector" + count)
    count += 1
    assert(protocolParam.protocol == Protocol.Tcp())
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
(instrumentOp: InstrumentOp, monitorTypeOp: MonitorTypeOp, alarmOp: AlarmOp, groupOp: GroupOp)
(@Assisted id: String, @Assisted protocolParam: ProtocolParam, @Assisted param: Adam6066Param) extends Actor with ActorLogging {

  import MoxaE1212Collector._

  self ! ConnectHost

  val me = instrumentOp.getInstrument(id)(0)

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
            cancelable = context.system.scheduler.scheduleOnce(Duration(3, SECONDS), self, Collect)
          } catch {
            case ex: Exception =>
              Logger.error(s"$id ${ex.getMessage}")
              for(groupID <- me.group)
                alarmOp.log(alarmOp.Src(groupID), alarmOp.Level.WARN, s"${groupOp.map(groupID).name}> 灑水設備斷線", 10)
              //Try again
              import scala.concurrent.duration._
              cancelable = context.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectHost)
          }

        }
      } onFailure errorHandler
    case IsConnected =>
      sender ! masterOpt.nonEmpty

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

            for(master<- masterOpt){
              val rawResult = master.send(batch)
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
                val groupID = me.group.getOrElse(Group.PLATFORM_ADMIN)
                monitorTypeOp.updateSignalValueMap(groupID, mt, v)
                // Log on difference
                if (!diValueMap.contains(idx) || diValueMap(idx) != v) {
                  // FIXME hot code invert
                  monitorTypeOp.logDiMonitorType(mt, !v, groupID)
                }
              }
              context become handler(collectorState, masterOpt, newDiValueMap)
              import scala.concurrent.duration._
              cancelable = context.system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(3, SECONDS), self, Collect)
            }
          } catch {
            case ex: Throwable =>
              Logger.error("Read reg failed", ex)
              masterOpt map { _.destroy() }
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
    case WriteMonitorTypeDO(mtID, on) =>
      for {
        cfg <- param.chs.zipWithIndex
        chCfg = cfg._1 if chCfg.enable && chCfg.mt.isDefined
        mt = chCfg.mt.get if mt == mtID
        idx = cfg._2
      } yield {
        Logger.info(s"WriteMonitorTypeDO $mtID $idx $on")
        self ! WriteDO(16 + idx, on)
      }
  }

  override def postStop(): Unit = {
    if (cancelable != null)
      cancelable.cancel()
  }
}