package models

import akka.actor.{Actor, Cancellable}
import com.github.nscala_time.time.Imports.LocalTime
import com.google.inject.assistedinject.Assisted
import models.Adam4000.ADAM4017
import models.ModelHelper.errorHandler
import models.Protocol.{ProtocolParam, serial}
import play.api.Logger
import play.api.libs.json.{JsError, Json}

import scala.concurrent.{Future, blocking}
import scala.concurrent.duration.{FiniteDuration, SECONDS}

case class SignalConfig(monitorType: Option[String])

case class AiChannelCfg(enable: Boolean, mt: Option[String], max: Option[Double], mtMax: Option[Double],
                        min: Option[Double], mtMin: Option[Double], repairMode: Option[Boolean])

case class Adam4000Module(module: String, address: String, param: String)

object Adam4000 extends DriverOps {
  override def id: String = "adam4000"

  override def description: String = "Adam 4017/4068/4069"

  override def protocol: List[Protocol.Value] = List(serial)

  implicit val reads = Json.reads[Adam4000Module]

  val ADAM4017 = "4017"
  val ADAM4069 = "4069"

  override def verifyParam(param: String): String = {
    val ret = Json.parse(param).validate[Seq[Adam4000Module]]
    ret.fold(
      error => {
        throw new Exception(JsError.toJson(error).toString())
      },
      moduleList => {
        moduleList.foreach(module => {
          module.module match {
            case ADAM4017 =>
              // #8 AI
              implicit val reads = Json.reads[AiChannelCfg]
              val ret = Json.parse(module.param).validate[Seq[AiChannelCfg]]
              val channelCfgList = ret.asOpt.getOrElse(throw new Exception("Invalid 4017 config!"))
              if (channelCfgList.size != 8)
                throw new Exception("4017 shall have 8 channel configs")
              for (cfg <- channelCfgList) {
                if (cfg.enable) {
                  assert(cfg.mt.isDefined)
                  assert(cfg.max.get > cfg.min.get)
                  assert(cfg.mtMax.get > cfg.mtMin.get)
                }
              }

            case ADAM4069 =>
              //#8 DO
              implicit val reads = Json.reads[SignalConfig]
              val ret = Json.parse(module.param).validate[Seq[SignalConfig]]
              val signalList = ret.asOpt.getOrElse(throw new Exception("Invalid 4069 config!"))
              if (signalList.size != 8) {
                throw new Exception("4069 shall have 8 channel configs")
              }
          }
        })
        param
      })
  }

  override def getMonitorTypes(param: String): List[String] = {
    val moduleList = Json.parse(param).validate[List[Adam4000Module]].asOpt.get
    moduleList.map(module => {
      module.module match {
        case ADAM4017 =>
          // #8 AI
          implicit val reads = Json.reads[AiChannelCfg]
          val ret = Json.parse(module.param).validate[Seq[AiChannelCfg]]
          val channelCfgList = ret.asOpt.getOrElse(throw new Exception("Invalid 4017 config!"))
          for (cfg <- channelCfgList if cfg.enable) yield
            cfg.mt.get

        case ADAM4069 =>
          //#8 DO
          implicit val reads = Json.reads[SignalConfig]
          val ret = Json.parse(module.param).validate[Seq[SignalConfig]]
          val signalList = ret.asOpt.getOrElse(throw new Exception("Invalid 4069 config!"))
          if (signalList.size != 8)
            throw new Exception("4069 shall have 8 channel configs")
          signalList.map(_.monitorType).flatten
      }
    }).flatten
  }

  override def getCalibrationTime(param: String): Option[LocalTime] = None

  override def factory(id: String, protocol: Protocol.ProtocolParam, param: String)(f: AnyRef): Actor = {
    assert(f.isInstanceOf[Adam4000Collector.Factory])
    val f2 = f.asInstanceOf[Adam4000Collector.Factory]
    val moduleList = Json.parse(param).validate[List[Adam4000Module]].asOpt.get
    f2(id, protocol, moduleList)
  }
}

object Adam4000Collector {
  trait Factory {
    def apply(id: String, protocol: ProtocolParam, param: List[Adam4000Module]): Actor
  }
  case object OpenCom
  case object ReadAI
}
import scala.concurrent.ExecutionContext.Implicits.global
import javax.inject._
class Adam4000Collector @Inject()(instrumentOp: InstrumentOp, alarmOp: AlarmOp)
                                 (@Assisted instId: String, @Assisted protocolParam: ProtocolParam, @Assisted moduleList: List[Adam4000Module]) extends Actor {
  import Adam4000Collector._
  self ! OpenCom

  var comm: SerialComm = _
  var timerOpt: Option[Cancellable] = None

  def decode(str: String)(aiChannelCfgs: Seq[AiChannelCfg]) = {
    val ch = str.substring(1).split("(?=[+-])", 8)
    if (ch.length != 8)
      throw new Exception("unexpected format:" + str)
    val values = ch.map {_.toDouble }
    val dataPairList =
      for {
        cfg <- aiChannelCfgs.zipWithIndex
        (chCfg, idx) = cfg if chCfg.enable
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
            MonitorStatus.NormalStat
        }
        List(MonitorTypeData(mt, v, status))
      }
    val dataList = dataPairList.flatMap { x => x }
    context.parent ! ReportData(dataList.toList)
  }

  override def receive: Receive = {
    case OpenCom =>
      try{
        comm = SerialComm.open(protocolParam.comPort.get, protocolParam.speed.getOrElse(9600))
        timerOpt = Some(context.system.scheduler.scheduleOnce(FiniteDuration(3, SECONDS), self, ReadAI))
      }catch{
        case ex: Exception =>
          Logger.error(ex.getMessage, ex)
          alarmOp.log(alarmOp.instStr(instId), alarmOp.Level.ERR, s"無法連接:${ex.getMessage}")
          import scala.concurrent.duration._
          context.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, OpenCom)
      }
    case ReadAI =>
      Future {
        blocking {
          val os = comm.os
          try {
            for (module <- moduleList if module.module == ADAM4017) {
              val readCmd = s"#${module.address}\r"
              os.write(readCmd.getBytes)
              val strList = comm.getLineWithTimeout(2)
              implicit val reads = Json.reads[AiChannelCfg]
              val aiChannelCfgs = Json.parse(module.param).validate[Seq[AiChannelCfg]].asOpt.getOrElse(Seq.empty[AiChannelCfg])
              for (str <- strList) {
                decode(str)(aiChannelCfgs)
              }
            }

          } catch (errorHandler)
          finally {
            timerOpt = Some(context.system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(3, SECONDS), self, ReadAI))
          }
        }
      } onFailure errorHandler

    case WriteDO(bit, on) =>
      /*
      val os = comm.os
      val is = comm.is
      Logger.info(s"Output DO $bit to $on")
      val writeCmd = if (on)
        s"#${param.addr}0001\r"
      else
        s"#${param.addr}0000\r"

      os.write(writeCmd.getBytes)
      val strList = comm.getLineWithTimeout(2)
       */
  }
}




