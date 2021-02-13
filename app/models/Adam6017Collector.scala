package models

import akka.actor._
import com.google.inject.assistedinject.Assisted
import models.ModelHelper._
import models.Protocol.ProtocolParam
import play.api._

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

object Adam6017Collector {

  case object ConnectHost
  case object Collect

  var count = 0
  def start(id: String, protocolParam: ProtocolParam, param: Adam6017Param)(implicit context: ActorContext) = {
    val prop = Props(classOf[Adam6017Collector], id, protocolParam, param)
    val collector = context.actorOf(prop, name = "MoxaE1212Collector" + count)
    count += 1
    assert(protocolParam.protocol == Protocol.tcp)
    collector ! ConnectHost
    collector

  }

  trait Factory {
    def apply(id: String, protocol: ProtocolParam, param: Adam6017Param): Actor
  }
}


class Adam6017Collector @Inject()
(instrumentOp: InstrumentOp, monitorTypeOp: MonitorTypeOp, system: ActorSystem)
(@Assisted id: String, @Assisted protocolParam: ProtocolParam, @Assisted param: Adam6017Param) extends Actor with ActorLogging {
  import MoxaE1212Collector._

  var cancelable: Cancellable = _

  val resetTimer = {
    import com.github.nscala_time.time.Imports._

    val resetTime = DateTime.now().withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0) + 1.hour
    val duration = new Duration(DateTime.now(), resetTime)
    import scala.concurrent.duration._
    system.scheduler.schedule(scala.concurrent.duration.Duration(duration.getStandardSeconds, SECONDS),
      scala.concurrent.duration.Duration(1, HOURS), self, ResetCounter)
  }

  def decodeAi(values: Seq[Double], collectorState: String)(param: Adam6017Param) = {
    val dataPairList =
      for {
        cfg <- param.ch.zipWithIndex
        (chCfg, idx) = cfg if chCfg.enable
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
      log.info(s"connect to Adam6017")
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
            import scala.concurrent.duration._
            cancelable = system.scheduler.scheduleOnce(Duration(3, SECONDS), self, Collect)
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
            import com.serotonin.modbus4j.code.DataType
            import com.serotonin.modbus4j.locator.BaseLocator

            //AI ...
            {
              val batch = new BatchRead[Float]

              for (idx <- 0 to 7)
                batch.addLocator(idx, BaseLocator.inputRegister(1, 1 + 2 * idx, DataType.FOUR_BYTE_FLOAT))

              batch.setContiguousRequests(true)

              val rawResult = masterOpt.get.send(batch)
              val result =
                for (idx <- 0 to 7) yield rawResult.getDoubleValue(idx).toDouble

              decodeAi(result, collectorState)(param)
            }

            import scala.concurrent.duration._
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

    case ResetCounter =>
      Logger.info("Reset counter to 0")
      try {
        import com.serotonin.modbus4j.locator.BaseLocator
        val resetRegAddr = 272

        for {
          ch_idx <- param.ch.zipWithIndex if ch_idx._1.enable && ch_idx._1.mt == Some(monitorTypeOp.RAIN)
          ch = ch_idx._1
          idx = ch_idx._2
        } {
          val locator = BaseLocator.coilStatus(1, resetRegAddr + idx)
          masterOpt.get.setValue(locator, true)
        }
      } catch {
        case ex: Exception =>
          ModelHelper.logException(ex)
      }

    case WriteDO(bit, on) =>
      Logger.info(s"Output DO $bit to $on")
      try {
        import com.serotonin.modbus4j.locator.BaseLocator
        val locator = BaseLocator.coilStatus(1, bit)
        masterOpt.get.setValue(locator, on)
      } catch {
        case ex: Exception =>
          ModelHelper.logException(ex)
      }
  }

  override def postStop(): Unit = {
    if (cancelable != null)
      cancelable.cancel()

    resetTimer.cancel()
  }
}