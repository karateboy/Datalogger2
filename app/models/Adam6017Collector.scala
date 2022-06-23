package models

import akka.actor._
import com.google.inject.assistedinject.Assisted
import models.ModelHelper._
import models.Protocol.ProtocolParam
import play.api._

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

object Adam6017Collector {

  var count = 0

  def start(id: String, protocolParam: ProtocolParam, param: Adam6017Param)(implicit context: ActorContext) = {
    val prop = Props(classOf[Adam6017Collector], id, protocolParam, param)
    val collector = context.actorOf(prop, name = "Adam6077Collector" + count)
    count += 1
    assert(protocolParam.protocol == Protocol.Tcp())
    collector ! ConnectHost
    collector

  }

  trait Factory {
    def apply(id: String, protocol: ProtocolParam, param: Adam6017Param): Actor
  }

  case object ConnectHost

  case object Collect

}


class Adam6017Collector @Inject()
(instrumentOp: InstrumentOp, alarmOp: AlarmOp, groupOp: GroupOp)
(@Assisted id: String, @Assisted protocolParam: ProtocolParam, @Assisted param: Adam6017Param) extends Actor with ActorLogging {

  import MoxaE1212Collector._

  self ! ConnectHost
  var cancelable: Cancellable = _

  def decodeAi(values: Seq[Double], collectorState: String)(param: Adam6017Param) = {
    val ret = for (v <- values) yield
      "%.5f".format(v)

    Logger.info(ret.toString())
    val dataPairList =
      for {
        cfg <- param.chs.zipWithIndex
        (chCfg, idx) = cfg if chCfg.enable
        rawValue = values(idx)
        mt <- chCfg.mt
        mtMin <- chCfg.mtMin
        mtMax <- chCfg.mtMax
        max <- chCfg.max
        min <- chCfg.min
      } yield {
        val v = mtMin + (mtMax - mtMin) / (max - min) * (rawValue - min)
        val status = if (MonitorTypeCollectorStatus.map.contains(mt))
          MonitorTypeCollectorStatus.map(mt)
        else {
          if (chCfg.repairMode.getOrElse(false))
            MonitorStatus.MaintainStat
          else
            collectorState
        }
        // val rawMt = monitorTypeOp.getRawMonitorType(mt)
        // List(MonitorTypeData(mt, v, status), MonitorTypeData(rawMt, rawValue, status))
        List(MonitorTypeData(mt, v, status))
      }
    val dataList = dataPairList.flatMap { x => x }
    context.parent ! ReportData(dataList.toList)
  }

  val me = instrumentOp.getInstrument(id)(0)

  import com.serotonin.modbus4j._
  import com.serotonin.modbus4j.ip.IpParameters

  import scala.concurrent.{Future, blocking}

  def receive = handler(MonitorStatus.NormalStat, None)

  def handler(collectorState: String, masterOpt: Option[ModbusMaster]): Receive = {
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
            context become handler(collectorState, Some(master))
            import scala.concurrent.duration._
            cancelable = context.system.scheduler.scheduleOnce(Duration(3, SECONDS), self, Collect)
            self ! WriteDO(bit = 16, on = false)
          } catch {
            case ex: Exception =>
              Logger.error(ex.getMessage)
              Logger.info("Try again 1 min later...")
              for(groupID <- me.group)
                alarmOp.log(alarmOp.Src(groupID), alarmOp.Level.WARN, s"${groupOp.map(groupID).name}> 灑水設備斷線", 10)
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

            //AI ...
            {
              val batch = new BatchRead[Float]

              for (idx <- 0 to 7)
                batch.addLocator(idx, BaseLocator.holdingRegister(1, 30 + 2*idx, DataType.FOUR_BYTE_FLOAT_SWAPPED))

              batch.setContiguousRequests(true)

              val rawResult = masterOpt.get.send(batch)
              val result =
                for (idx <- 0 to 7) yield
                  rawResult.getFloatValue(idx).toDouble

              //val actualResult = result map { v => -5.0 + 10 * v / 65535.0 }
              decodeAi(result, collectorState)(param)
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

    case WriteDO(bit, on) =>
      Logger.info(s"Output DO $bit to $on")
      try {
        import com.serotonin.modbus4j.locator.BaseLocator
        val locator = BaseLocator.coilStatus(1, bit)
        masterOpt map {
          master => master.setValue(locator, on)
        }
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