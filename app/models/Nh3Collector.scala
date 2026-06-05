package models

import akka.actor.{Actor, ActorContext, ActorLogging, ActorRef, Cancellable, Props}
import com.github.nscala_time.time.Imports._
import com.google.inject.assistedinject.Assisted
import models.ModelHelper.errorHandler
import models.MoxaE1212Collector.{ConnectHost, count}
import models.Protocol.{ProtocolParam, tcp}
import play.api.Logger

import javax.inject.Inject

object Nh3Collector extends DriverOps {
  override def verifyParam(param: String): String = param

  val mtNames: List[String] = List("NH3", "HCL", "HF", "HNO3", "AcOH")

  override def getMonitorTypes(param: String): List[String] = mtNames

  override def getCalibrationTime(param: String): Option[LocalTime] = None

  var count = 0

  override def id: String = "ImsNH3"

  override def description: String = "IMS NH3 instrument"

  override def protocol: List[String] = List(tcp)

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt: Option[AnyRef]): Actor = {
    assert(f.isInstanceOf[Nh3Collector.Factory])
    val f2 = f.asInstanceOf[Nh3Collector.Factory]
    f2(id, protocol)
  }

  trait Factory {
    def apply(id: String, protocol: ProtocolParam): Actor
  }
}

class Nh3Collector @Inject()
(instrumentOp: InstrumentDB)
(@Assisted id: String, @Assisted protocolParam: ProtocolParam) extends Actor {

  import DataCollectManager._
  import MoxaE1212Collector._
  import context.dispatcher

  val logger: Logger = Logger(getClass)
  logger.info(s"NH3 Collector $id start")

  self ! ConnectHost
  var cancelable: Cancellable = _

  def decodeValue(values: Seq[Short], collectorState: String): Unit = {
    val mtInfo = List(
      (3, "HCL"),
      (4, "HF"),
      (5, "NH3"),
      (6, "HNO3"),
      (7, "AcOH")
    )

    val polarity = values(2)
    val dataList = mtInfo flatMap (
      offset_mt => {
        val (offset, mt) = offset_mt
        val v = 0.1 * values(offset)
        val filteredMonitorTypes = Seq("NH3", "HCL")
        if (filteredMonitorTypes.contains(mt)) {
          if (v != 0)
            Some(MonitorTypeData(mt, v, collectorState))
          else
            None
        } else
          Some(MonitorTypeData(mt, v, collectorState))
      }
      )

    context.parent ! ReportData(dataList)
  }

  import com.serotonin.modbus4j._
  import com.serotonin.modbus4j.ip.IpParameters

  import scala.concurrent.{Future, blocking}

  def receive: Receive = handler(MonitorStatus.NormalStat, None)

  def handler(collectorState: String, masterOpt: Option[ModbusMaster]): Receive = {
    case ConnectHost =>
      logger.info(s"connect to NH3 device")
      val f =
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
                logger.error(ex.getMessage, ex)
                logger.info("Try again 1 min later...")
                //Try again
                import scala.concurrent.duration._
                cancelable = context.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectHost)
            }

          }
        }
      f.failed.foreach(errorHandler)

    case Collect =>
      val f = {
        Future {
          blocking {
            try {
              import com.serotonin.modbus4j.BatchRead
              import com.serotonin.modbus4j.code.DataType
              import com.serotonin.modbus4j.locator.BaseLocator

              //DI Counter ...
              {
                val batch = new BatchRead[Integer]

                for (idx <- 0 to 8)
                  batch.addLocator(idx, BaseLocator.inputRegister(1, 1 + idx, DataType.TWO_BYTE_INT_SIGNED))

                batch.setContiguousRequests(true)

                val rawResult = masterOpt.get.send(batch)
                val result =
                  for (idx <- 0 to 7) yield
                    rawResult.getValue(idx).asInstanceOf[Short]


                decodeValue(result, collectorState)
              }

              import scala.concurrent.duration._
              cancelable = context.system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(5, SECONDS), self, Collect)
            } catch {
              case ex: Throwable =>
                logger.error("Read reg failed", ex)
                masterOpt.get.destroy()
                context become handler(collectorState, None)
                self ! ConnectHost
            }
          }
        }
      }
      f.failed.foreach(errorHandler)

    case SetState(id, state) =>
      logger.info(s"$self => $state")
      instrumentOp.setState(id, state)
      context become handler(state, masterOpt)

  }

  override def postStop(): Unit = {
    if (cancelable != null)
      cancelable.cancel()

  }
}