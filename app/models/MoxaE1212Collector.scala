package models

import akka.actor._
import com.google.inject.assistedinject.Assisted
import models.ModelHelper._
import models.Protocol.ProtocolParam
import play.api._

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

object MoxaE1212Collector {

  var count = 0

  def start(id: String, protocolParam: ProtocolParam, param: MoxaE1212Param)(implicit context: ActorContext) = {
    val prop = Props(classOf[MoxaE1212Collector], id, protocolParam, param)
    val collector = context.actorOf(prop, name = "MoxaE1212Collector" + count)
    count += 1
    assert(protocolParam.protocol == Protocol.tcp)
    val host = protocolParam.host.get
    collector ! ConnectHost
    collector

  }

  trait Factory {
    def apply(id: String, protocol: ProtocolParam, param: MoxaE1212Param): Actor
  }

  case object ConnectHost

  case object Collect
}

class MoxaE1212Collector @Inject()
(instrumentOp: InstrumentDB)
(@Assisted id: String, @Assisted protocolParam: ProtocolParam, @Assisted param: MoxaE1212Param) extends Actor with ActorLogging {

  import MoxaE1212Collector._

  val resetTimer = {
    import com.github.nscala_time.time.Imports._

    val resetTime = DateTime.now().withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0) + 1.hour
    val duration = new Duration(DateTime.now(), resetTime)
    import scala.concurrent.duration._
    context.system.scheduler.schedule(FiniteDuration(duration.getStandardSeconds, SECONDS),
      scala.concurrent.duration.Duration(1, HOURS), self, ResetCounter)
  }
  var cancelable: Cancellable = _

  self ! ConnectHost

  def decodeDiCounter(values: Seq[Int], collectorState: String) = {
    val dataOptList =
      for {
        cfg <- param.chs.zipWithIndex
        chCfg = cfg._1 if chCfg.enable && chCfg.mt.isDefined
        idx = cfg._2
        mt = chCfg.mt.get
        scale = chCfg.scale.get
      } yield {
        val v = scale * values(idx)
        val state = if (chCfg.repairMode.isDefined && chCfg.repairMode.get)
          MonitorStatus.MaintainStat
        else
          collectorState

        Some(MonitorTypeData(mt, v, state))
      }
    val dataList = dataOptList.flatMap { d => d }
    context.parent ! ReportData(dataList.toList)
  }

  import com.serotonin.modbus4j._
  import com.serotonin.modbus4j.ip.IpParameters

  import scala.concurrent.{Future, blocking}

  def receive = handler(MonitorStatus.NormalStat, None)

  def handler(collectorState: String, masterOpt: Option[ModbusMaster]): Receive = {
    case ConnectHost =>
      log.info(s"connect to E1212")
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
            cancelable = context.system.scheduler.scheduleOnce(Duration(3, SECONDS), self, Collect)
          } catch {
            case ex: Exception =>
              Logger.error(ex.getMessage, ex)
              Logger.info("Try again 1 min later...")
              //Try again
              import scala.concurrent.duration._
              cancelable = context.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectHost)
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

            //DI Counter ...
            {
              val batch = new BatchRead[Integer]

              for (idx <- 0 to 7)
                batch.addLocator(idx, BaseLocator.inputRegister(1, 16 + 2 * idx, DataType.FOUR_BYTE_INT_SIGNED))

              batch.setContiguousRequests(true)

              val rawResult = masterOpt.get.send(batch)
              val result =
                for (idx <- 0 to 7) yield rawResult.getIntValue(idx).toInt

              decodeDiCounter(result.toSeq, collectorState)
            }
            // DI Value ...
            {
              val batch = new BatchRead[Integer]
              for (idx <- 0 to 7)
                batch.addLocator(idx, BaseLocator.inputStatus(1, idx))

              batch.setContiguousRequests(true)

              val rawResult = masterOpt.get.send(batch)
              val result =
                for (idx <- 0 to 7) yield rawResult.getValue(idx).asInstanceOf[Boolean]

              val dataList: Seq[SignalData] =
                for {
                  cfg <- param.chs.zipWithIndex
                  chCfg = cfg._1 if chCfg.enable && chCfg.mt.isDefined
                  mt = chCfg.mt.get if mt != MonitorType.RAIN
                  idx = cfg._2
                  v = result(idx)
                } yield
                  SignalData(mt, v)
              context.parent ! ReportSignalData(dataList)
            }

            import scala.concurrent.duration._
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

    case ResetCounter =>
      Logger.info("Reset counter to 0")
      try {
        import com.serotonin.modbus4j.locator.BaseLocator
        val resetRegAddr = 272

        for {
          ch_idx <- param.chs.zipWithIndex if ch_idx._1.enable && ch_idx._1.mt == Some(MonitorType.RAIN)
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