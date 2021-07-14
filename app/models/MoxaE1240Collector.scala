package models
import akka.actor._
import com.google.inject.assistedinject.Assisted
import models.ModelHelper._
import models.Protocol.ProtocolParam
import play.api.Play.current
import play.api._
import play.api.libs.concurrent.Akka

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object MoxaE1240Collector {

  case object ConnectHost
  case object Collect

  var count = 0
  def start(id: String, protocolParam: ProtocolParam, param: MoxaE1240Param)(implicit context: ActorContext) = {
    val prop = Props(classOf[MoxaE1240Collector], id, protocolParam, param)
    val collector = context.actorOf(prop, name = "MoxaE1240Collector" + count)
    count += 1
    assert(protocolParam.protocol == Protocol.Tcp())
    collector ! ConnectHost
    collector
  }

  trait Factory {
    def apply(id: String, protocol: ProtocolParam, param: MoxaE1240Param): Actor
  }

}

import javax.inject._

class MoxaE1240Collector @Inject()
(monitorTypeOp: MonitorTypeOp, instrumentOp: InstrumentOp, system: ActorSystem)
(@Assisted id: String, @Assisted protocolParam: ProtocolParam, @Assisted param: MoxaE1240Param) extends Actor with ActorLogging {
  import MoxaE1240Collector._

  var cancelable: Cancellable = _

  def decode(values: Seq[Float], collectorState: String) = {
    val dataPairList =
      for {
        cfg <- param.ch.zipWithIndex
        chCfg = cfg._1 if chCfg.enable
        idx = cfg._2
        rawValue = values(idx)
        mt <- chCfg.mt
        mtMin <- chCfg.mtMin
        mtMax <- chCfg.mtMax
        max <- chCfg.max
        min <- chCfg.min
      } yield {
        val v = mtMin + (mtMax - mtMin) / (max - min) * (values(idx) - min)
        val status = if (MonitorTypeCollectorStatus.map.contains(mt))
          MonitorTypeCollectorStatus.map(mt)
        else {
          if (chCfg.repairMode.getOrElse(false))
            MonitorStatus.MaintainStat
          else
            collectorState
        }
        val rawMt = monitorTypeOp.getRawMonitorType(mt)
        List(MonitorTypeData(mt, v, status), MonitorTypeData(rawMt, rawValue, status))
      }
    val dataList = dataPairList.flatMap { x => x }
    context.parent ! ReportData(dataList.toList)
  }

  import com.serotonin.modbus4j._
  import com.serotonin.modbus4j.ip.IpParameters

  import scala.concurrent.{Future, blocking}

  def receive = handler(MonitorStatus.NormalStat, None)

  def handler(collectorState: String, masterOpt: Option[ModbusMaster]): Receive = {
    case ConnectHost =>
      log.info(s"connect to E1240")
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
            cancelable = system.scheduler.scheduleOnce(Duration(3, SECONDS), self, Collect)
          } catch {
            case ex: Exception =>
              Logger.error(ex.getMessage, ex)
              Logger.info("Try again 1 min later...")
              //Try again
              cancelable = system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectHost)
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

            for (idx <- 0 to 7)
              batch.addLocator(idx, BaseLocator.inputRegister(1, 8 + 2 * idx, DataType.FOUR_BYTE_FLOAT_SWAPPED))

            batch.setContiguousRequests(true)

            assert(masterOpt.isDefined)

            val rawResult = masterOpt.get.send(batch)
            val result =
              for (idx <- 0 to 7) yield rawResult.getFloatValue(idx).toFloat

            decode(result.toSeq, collectorState)
            cancelable = system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(3, SECONDS), self, Collect)
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