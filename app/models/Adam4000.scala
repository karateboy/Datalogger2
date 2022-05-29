package models

import akka.actor.{Actor, Cancellable}
import com.github.nscala_time.time.Imports.{DateTime, LocalTime}
import com.google.inject.assistedinject.Assisted
import models.Adam4000.{ADAM4017, ADAM4069, ADAM4080}
import models.ModelHelper.errorHandler
import models.Protocol.{ProtocolParam, serial}
import play.api.Logger
import play.api.libs.json.{JsError, Json, Reads}

import scala.concurrent.duration.{FiniteDuration, SECONDS}
import scala.concurrent.{Future, blocking}

case class SignalConfig(monitorType: Option[String])

case class CounterConfig(enable: Boolean, monitorType: Option[String], multiplier: Option[Double], repairMode: Option[Boolean])

case class AiChannelCfg(enable: Boolean, mt: Option[String], max: Option[Double], mtMax: Option[Double],
                        min: Option[Double], mtMin: Option[Double], repairMode: Option[Boolean])

case class Adam4069Cfg(address: String, channelList: Seq[SignalConfig])

case class Adam4080Cfg(address: String, channelList: Seq[CounterConfig])

case class Adam4017Cfg(address: String, channelList: Seq[AiChannelCfg])

case class Adam4000Module(module: String, address: String, param: String) {
  def get4017Cfg: Adam4017Cfg = {
    assert(module == ADAM4017)
    implicit val reads: Reads[AiChannelCfg] = Json.reads[AiChannelCfg]
    val ret = Json.parse(param).validate[Seq[AiChannelCfg]]
    val channelCfgList = ret.asOpt.getOrElse(throw new Exception("Invalid 4017 config!"))
    Adam4017Cfg(address, channelCfgList)
  }

  def get4069Cfg: Adam4069Cfg = {
    assert(module == ADAM4069)
    implicit val reads: Reads[SignalConfig] = Json.reads[SignalConfig]
    val ret = Json.parse(param).validate[Seq[SignalConfig]]
    val channelList = ret.asOpt.getOrElse(throw new Exception("Invalid 4069 config!"))
    Adam4069Cfg(address, channelList)
  }

  def get4080Cfg: Adam4080Cfg = {
    assert(module == ADAM4080)
    implicit val reads: Reads[CounterConfig] = Json.reads[CounterConfig]
    val ret = Json.parse(param).validate[Seq[CounterConfig]]
    val channelList = ret.asOpt.getOrElse(throw new Exception("Invalid 4080 config!"))
    Adam4080Cfg(address, channelList)
  }
}

object Adam4000 extends DriverOps {
  val ADAM4017 = "4017"
  val ADAM4069 = "4069"
  val ADAM4080 = "4080"

  implicit val reads: Reads[Adam4000Module] = Json.reads[Adam4000Module]

  override def id: String = "adam4000"

  override def description: String = "Adam 4017/4068/4069/4080"

  override def protocol: List[String] = List(serial)

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
              implicit val reads: Reads[AiChannelCfg] = Json.reads[AiChannelCfg]
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
              implicit val reads: Reads[SignalConfig] = Json.reads[SignalConfig]
              val ret = Json.parse(module.param).validate[Seq[SignalConfig]]
              val signalList = ret.asOpt.getOrElse(throw new Exception("Invalid 4069 config!"))
              if (signalList.size != 8) {
                throw new Exception("4069 shall have 8 channel configs")
              }

            case ADAM4080 =>
              // #2 Counter
              implicit val reads = Json.reads[CounterConfig]
              val ret = Json.parse(module.param).validate[Seq[CounterConfig]]
              val configList = ret.asOpt.getOrElse(throw new Exception("Invalid 4080 config!"))
              if (configList.size != 2) {
                throw new Exception("4080 shall have 2 channel configs")
              }
          }
        })
        param
      })
  }

  override def getMonitorTypes(param: String): List[String] = {
    val moduleList = Json.parse(param).validate[List[Adam4000Module]].asOpt.get
    moduleList.flatMap(module => {
      module.module match {
        case ADAM4017 =>
          val adam4017cfg = module.get4017Cfg
          adam4017cfg.channelList.filter(_.enable).flatMap(_.mt)

        case ADAM4069 =>
          val adam4069cfg = module.get4069Cfg
          adam4069cfg.channelList.flatMap(_.monitorType)

        case ADAM4080 =>
          val adam4080cfg = module.get4080Cfg
          adam4080cfg.channelList.filter(_.enable).flatMap(_.monitorType)
      }
    })
  }

  override def getCalibrationTime(param: String): Option[LocalTime] = None

  override def factory(id: String, protocol: Protocol.ProtocolParam, param: String)(f: AnyRef, fOpt: Option[AnyRef]): Actor = {
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

  case class WriteModuleDo(address: String, ch: Int, on: Boolean)

  case object OpenCom

  case object ReadInput

  case object ResetCounter
}

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

class Adam4000Collector @Inject()(alarmOp: AlarmDB)
                                 (@Assisted instId: String, @Assisted protocolParam: ProtocolParam, @Assisted moduleList: List[Adam4000Module]) extends Actor {

  import Adam4000Collector._

  self ! OpenCom

  val resetCounterTimer = if (moduleList.find(_.module == ADAM4080).isDefined) {
    Some(1)
  } else
    None

  var comm: SerialComm = _
  var timerOpt: Option[Cancellable] = None
  var resetTimerOpt: Option[Cancellable] = None

  override def receive: Receive = {
    case OpenCom =>
      try {
        comm = SerialComm.open(protocolParam.comPort.get, protocolParam.speed.getOrElse(9600))
        addSignalTypeHandler()
        timerOpt = Some(context.system.scheduler.scheduleOnce(FiniteDuration(3, SECONDS), self, ReadInput))
        for(module4080 <-moduleList.find(module=>module.module == ADAM4080)){
          import scala.concurrent.duration._
          //Try to trigger at 30 sec
          val nextHour = DateTime.now().plusHours(1).withSecondOfMinute(0).withMinuteOfHour(0).withMillis(0)
          val elapsedSeconds = new org.joda.time.Duration(DateTime.now, nextHour).getStandardSeconds
          context.system.scheduler.schedule(FiniteDuration(elapsedSeconds, SECONDS), FiniteDuration(1, HOURS), self, ResetCounter)
        }
      } catch {
        case ex: Exception =>
          Logger.error(ex.getMessage, ex)
          alarmOp.log(alarmOp.instrumentSrc(instId), alarmOp.Level.ERR, s"無法連接:${ex.getMessage}")
          import scala.concurrent.duration._
          context.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, OpenCom)
      }
    case ReadInput =>
      Future {
        blocking {
          val os = comm.os
          try {
            for (module <- moduleList if module.module == ADAM4017) {
              val adam4017cfg = module.get4017Cfg
              val readCmd = s"#${module.address}\r"
              os.write(readCmd.getBytes)
              val strList = comm.getLineWithTimeout(2)
              for (str <- strList) {
                decode4017(str)(adam4017cfg.channelList)
              }
            }

            for (module <- moduleList if module.module == ADAM4069) {
              val adam4069cfg = module.get4069Cfg
              val readCmd = s"$$${module.address}6\r"
              os.write(readCmd.getBytes)
              val strList = comm.getLineWithTimeout(2)
              for (str <- strList) {
                decode4069(str, adam4069cfg.channelList)
              }
            }

            for (module <- moduleList if module.module == ADAM4080) {
              val adam4080cfg = module.get4080Cfg
              for((ch, n) <- adam4080cfg.channelList.zipWithIndex if ch.enable) {
                val readCmd = s"$$${module.address}$n\r"
                os.write(readCmd.getBytes)
                val strList = comm.getLineWithTimeout(2)
                decode4080(strList(0), ch)
              }
            }
          } catch errorHandler
          finally {
            timerOpt = Some(context.system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(3, SECONDS), self, ReadInput))
          }
        }
      } onFailure errorHandler

    case ResetCounter =>
      Future {
        blocking {
          for (module <- moduleList if module.module == ADAM4080) {
            val adam4080cfg = module.get4080Cfg
            for((ch, n) <- adam4080cfg.channelList.zipWithIndex if ch.enable) {
              val readCmd = s"$$${module.address}6$n\r"
              comm.os.write(readCmd.getBytes)
              comm.getLineWithTimeout(2)
            }
          }
        }
      } onFailure errorHandler

    case WriteModuleDo(address, ch, on) =>
      val os = comm.os
      Logger.info(s"Output DO $ch channel to $on")
      val writeCmd = if (on)
        s"#${address}1${ch}01\r"
      else
        s"#${address}1${ch}00\r"

      os.write(writeCmd.getBytes)
      comm.getLineWithTimeout(2)
  }

  def decode4017(str: String)(aiChannelCfgs: Seq[AiChannelCfg]): Unit = {
    val ch = str.substring(1).split("(?=[+-])", 8)
    if (ch.length != 8)
      throw new Exception("unexpected format:" + str)
    val values = ch.map {
      _.toDouble
    }
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

  def decode4069(str: String, signalConfigList: Seq[SignalConfig]): Unit = {
    val valueStr = str.substring(1).take(2)
    val value = Integer.valueOf(valueStr, 16)
    val dataList: Seq[SignalData] =
      for {(signalCfg, idx) <- signalConfigList.zipWithIndex
           monitorType <- signalCfg.monitorType
           } yield {
        SignalData(monitorType, (value & 1 << idx) != 0)
      }
    context.parent ! ReportSignalData(dataList)
  }

  def decode4080(str: String, cfg:CounterConfig): Unit = {
    try{
      for(mt<-cfg.monitorType){
        val value: Double = Integer.decode("0x"+str.trim).toDouble * cfg.multiplier.getOrElse(1.0)
        val status = if (cfg.repairMode.getOrElse(false))
          MonitorStatus.MaintainStat
        else
          MonitorStatus.NormalStat
          context.parent !  List(MonitorTypeData(mt, value, status))
      }
    }catch{
      case ex:Throwable=>
        Logger.error(s"$instId: 4080 unable to decode $str",ex)
    }
  }

  def addSignalTypeHandler(): Unit = {
    for (module <- moduleList if module.module == ADAM4069) {
      implicit val reads: Reads[SignalConfig] = Json.reads[SignalConfig]
      val signalConfigList = Json.parse(module.param).validate[Seq[SignalConfig]].asOpt.get
      for {(cfg, idx) <- signalConfigList.zipWithIndex
           monitorTypeId <- cfg.monitorType
           } {
        context.parent ! AddSignalTypeHandler(monitorTypeId, (bit: Boolean) => {
          self ! WriteModuleDo(module.address, idx, bit)
        })
      }
    }
  }

  override def postStop(): Unit = {
    for (timer <- timerOpt)
      timer.cancel()

    for(resetTimer<- resetTimerOpt)
      resetTimer.cancel()

    if (comm != null)
      comm.close

    super.postStop()
  }
}




