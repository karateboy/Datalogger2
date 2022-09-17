package models

import akka.actor.{Actor, ActorSystem}
import com.github.nscala_time.time.Imports
import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam
import models.mongodb.AlarmOp
import play.api.Logger
import play.api.libs.json.{JsError, Json}
import play.api.libs.ws.WSClient

import scala.language.postfixOps

case class DuoMonitorType(id: String, desc: String, configID: String, isSpectrum: Boolean = false)

case class DuoConfig(fixed: Boolean, monitorTypes: Seq[DuoMonitorType])

object Duo extends DriverOps {
  implicit val readMt = Json.reads[DuoMonitorType]
  implicit val writeMt = Json.writes[DuoMonitorType]
  implicit val reads = Json.reads[DuoConfig]
  implicit val writes = Json.writes[DuoConfig]
  val monitorTypes: Seq[MonitorType] = Seq(
    // LAeq0.5s;LZeq0.5s;LCpeak;LAF;LZF;LAF0.5sMax;LAF0.5sMin
    // Leq0.5s;LF
    MonitorType(_id = "LAeq0.5s", desp = "LAeq 0.5s", unit = "dB",
      prec = 2, order = 100,
      acoustic = Some(true)),
    MonitorType(_id = "LZeq0.5s", desp = "LZeq 0.5s", unit = "dB",
      prec = 2, order = 100,
      acoustic = Some(true)),
    MonitorType(_id = "LCpeak", desp = "LC peak", unit = "dB",
      prec = 2, order = 100,
      acoustic = Some(true)),
    MonitorType(_id = "LAF", desp = "LAF", unit = "dB",
      prec = 2, order = 100,
      acoustic = Some(true)),
    MonitorType(_id = "LZF", desp = "LZF", unit = "dB",
      prec = 2, order = 100,
      acoustic = Some(true)),
    MonitorType(_id = "LAF0.5sMax", desp = "LAF 0.5sMax", unit = "dB",
      prec = 2, order = 100,
      acoustic = Some(true)),
    MonitorType(_id = "LAF0.5sMin", desp = "LAF 0.5sMin", unit = "dB",
      prec = 2, order = 100,
      acoustic = Some(true)),
    MonitorType(_id = "Leq0.5s", desp = "Leq 0.5s", unit = "dB",
      prec = 2, order = 100,
      acoustic = Some(true)),
    MonitorType(_id = "LF", desp = "LF", unit = "dB",
      prec = 2, order = 100,
      acoustic = Some(true))
  )
  val map: Map[String, MonitorType] = monitorTypes.map(mt => mt._id -> mt).toMap
  val fixedMonitorTypes: Seq[MonitorType] = Seq(
    MonitorType(_id = "LeqAF", desp = "Leq fast A weighting", unit = "dB",
      prec = 2, order = 100,
      acoustic = Some(true)),
    MonitorType(_id = "LeqA", desp = "Leq A weighting", unit = "dB",
      prec = 2, order = 100,
      acoustic = Some(true)),
    MonitorType(_id = "LeqZ", desp = "Leq Z weighting", unit = "dB",
      prec = 2, order = 100,
      acoustic = Some(true))
  )
  val fixedMap: Map[String, MonitorType] = fixedMonitorTypes.map(mt => mt._id -> mt).toMap
  val ONE_THIRD_OCTAVE_BANDS_CENTER_FREQ: Seq[String] = Seq("6.3", "8", "10", "12.5", "16", "20", "25", "31.5",
    "40", "50", "63", "80", "100", "125", "160", "200", "250",
    "315", "400", "500", "630", "800", "1k", "1.25k", "1.6k",
    "2k", "2.5k", "3.15k", "4k", "5k", "6.3k", "8k", "10k", "12.5k", "16k", "20k")

  override def id: String = "duo"

  override def description: String = "01dB Duo"

  override def protocol: List[String] = List(Protocol.tcp)

  override def verifyParam(param: String): String = {
    val ret = Json.parse(param).validate[DuoConfig]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      config => {

      })
    param
  }

  override def getMonitorTypes(param: String): List[String] = {
    val ret = Json.parse(param).validate[DuoConfig]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      config => {
        val spectrumTypes = config.monitorTypes.filter(t => t.isSpectrum)
        val otherTypes = config.monitorTypes.filter(t => !t.isSpectrum)

        val spectrumMonitorTypes: Seq[MonitorType] = spectrumTypes map getSpectrumMonitorTypes flatten
        val spectrumMonitorTypeID = spectrumMonitorTypes map {
          _._id
        }
        val allTypes = spectrumMonitorTypeID ++ otherTypes.map {
          _.id
        }

        allTypes.toList
      })
  }

  override def getCalibrationTime(param: String): Option[Imports.LocalTime] = None

  override def factory(id: String, protocolParam: Protocol.ProtocolParam, param: String)(f: AnyRef, fOpt:Option[AnyRef]): Actor = {
    assert(f.isInstanceOf[Duo.Factory])
    val f2 = f.asInstanceOf[Duo.Factory]
    val config: DuoConfig = Json.parse(param).validate[DuoConfig].get
    f2(id, protocolParam, config)
  }

  def ensureSpectrumTypes(duoMT: DuoMonitorType)(monitorTypeOp: MonitorTypeDB) =
    for (mt <- getSpectrumMonitorTypes(duoMT))
      monitorTypeOp.ensure(mt)

  def getSpectrumMonitorTypes(duoMT: DuoMonitorType) = for (idx <- 0 to 35) yield
    MonitorType(_id = s"${duoMT.configID}_${idx}", desp = s"${duoMT.desc} ${ONE_THIRD_OCTAVE_BANDS_CENTER_FREQ(idx)}Hz",
      unit = "dB",
      prec = 2, order = 100,
      acoustic = Some(true), spectrum = Some(true))

  trait Factory {
    def apply(instId: String, protocolParam: ProtocolParam, config: DuoConfig): Actor
  }

  case object ReadData

  case object ReadFixedData
}

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

class DuoCollector @Inject()
(wsClient: WSClient, system: ActorSystem)
(@Assisted instId: String, @Assisted protocolParam: ProtocolParam, @Assisted config: DuoConfig) extends Actor {

  import Duo._

  import scala.concurrent.duration._

  val timer = if (config.fixed)
    system.scheduler.schedule(Duration(1, SECONDS), Duration(1, SECONDS), self, ReadFixedData)
  else
    system.scheduler.schedule(Duration(1, SECONDS), Duration(1, SECONDS), self, ReadData)

  val host = protocolParam.host.get

  val valueTypes = config.monitorTypes.filter(p => p.configID.startsWith("V"))
  val spectrumTypes = config.monitorTypes.filter(p => p.configID.startsWith("S"))
  val weatherTypes = config.monitorTypes.filter(p => p.configID.startsWith("W"))

  override def receive: Receive = {
    case ReadData =>
      val f = wsClient.url(s"http://${host}/pub/GetRealTimeValues.asp").get()
      var dataList = Seq.empty[MonitorTypeData]

      for (ret <- f) {
        def getMonitorData(tag: String, mtList: Seq[DuoMonitorType]) = {
          val valueNode = ret.xml \ tag
          val values = valueNode.text.split(";")
          if (values.length != mtList.length) {
            Logger.warn(s"$tag length ${values.length} != config length ${mtList.length}")
            Logger.info(values.toString)
            Seq.empty[MonitorTypeData]
          } else {
            val dataOptList =
              for ((mt, valueStr) <- mtList.zip(values)) yield {
                val vOpt = try {
                  Some(valueStr.toDouble)
                } catch {
                  case _: Throwable =>
                    None
                }
                for (v <- vOpt) yield
                  MonitorTypeData(mt.id, v, MonitorStatus.NormalStat)
              }
            dataOptList.flatten
          }
        }

        def getSpectrumMonitorData(spectrumMT: DuoMonitorType) = {
          val spectrumIdx = spectrumMT.configID.drop(1)
          val tag = s"Spectrum${spectrumIdx}"
          val valueNode = ret.xml \ tag
          val values = valueNode.text.split(";")
          if (values.length != 36) {
            Logger.warn(s"spectrum length != 36")
            Logger.info(valueNode.text)
            Seq.empty[MonitorTypeData]
          } else {
            val mtIdList =
              for (idx <- 0 to 35) yield
                s"${spectrumMT.configID}_${idx}"

            val dataOptList =
              for ((mtID, valueStr) <- mtIdList.zip(values)) yield {
                val vOpt = try {
                  Some(valueStr.toDouble)
                } catch {
                  case _: Throwable =>
                    None
                }
                for (v <- vOpt) yield
                  MonitorTypeData(mtID, v, MonitorStatus.NormalStat)
              }
            dataOptList.flatten
          }
        }

        if (valueTypes.length != 0)
          dataList = dataList ++ getMonitorData("Values", valueTypes)

        for (spectrumType <- spectrumTypes)
          dataList = dataList ++ getSpectrumMonitorData(spectrumType)

        if (weatherTypes.length != 0)
          dataList = dataList ++ getMonitorData("Weather", weatherTypes)

        context.parent ! ReportData(dataList.toList)
      }
    case ReadFixedData =>
      val f = wsClient.url(s"http://${host}/ajax/F_refresh.asp?Mode=RT").get()
      var dataList = Seq.empty[MonitorTypeData]

      for (ret <- f) {
        def getMonitorTypeData(tag: String, mtList: Seq[DuoMonitorType]) = {
          val valueNode = ret.xml \\ tag
          val values = valueNode.text.split(";")
          val dataOptList =
            for {mt <- mtList} yield {
              val vOpt = try {
                val pos = mt.configID.drop(1).toInt - 1
                val valueStr = values(pos)
                if (valueStr.startsWith("v"))
                  Some(valueStr.drop(1).toDouble)
                else
                  Some(valueStr.toDouble)
              } catch {
                case _: Throwable =>
                  None
              }
              for (v <- vOpt) yield
                MonitorTypeData(mt.id, v, MonitorStatus.NormalStat)
            }
          dataOptList.flatten
        }

        def getSpectrumMonitorData(spectrumMT: DuoMonitorType) = {
          val tag = s"spectrum"
          val valueNode = ret.xml \\ tag
          val values = valueNode.text.split(";")
          if (values.length != 36) {
            Logger.error(s"spectrum length != 36")
            Seq.empty[MonitorTypeData]
          } else {
            val mtIdList =
              for (idx <- 0 to 35) yield
                s"${spectrumMT.configID}_${idx}"

            val dataOptList =
              for ((mtID, valueStr) <- mtIdList.zip(values)) yield {
                val vOpt = try {
                  if (valueStr.startsWith("v"))
                    Some(valueStr.drop(1).toDouble)
                  else
                    Some(valueStr.toDouble)
                } catch {
                  case _: Throwable =>
                    None
                }
                for (v <- vOpt) yield
                  MonitorTypeData(mtID, v, MonitorStatus.NormalStat)
              }
            dataOptList.flatten
          }
        }

        if (valueTypes.length != 0) {
          val instantList = getMonitorTypeData("instant", valueTypes)
          dataList = dataList ++ getMonitorTypeData("instant", valueTypes)
        }

        for (spectrumType <- spectrumTypes) {
          val spectrumList = getSpectrumMonitorData(spectrumType)
          dataList = dataList ++ getSpectrumMonitorData(spectrumType)
        }

        if (weatherTypes.length != 0) {
          val weatherList = getMonitorTypeData("weather", weatherTypes)
          dataList = dataList ++ weatherList
        }

        context.parent ! ReportData(dataList.toList)
      }
  }

  override def postStop() {
    timer.cancel()
    super.postStop()
  }
}
