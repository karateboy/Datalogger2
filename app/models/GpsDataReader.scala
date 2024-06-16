package models

import akka.actor._
import com.github.nscala_time.time.Imports.{DateTime, Period}
import models.ModelHelper.{getHourBetween, getPeriods, waitReadyResult}
import models.mongodb.RecordOp
import play.api._

import java.io.File
import java.nio.file.Path
import java.time.format.{DateTimeFormatter, DateTimeParseException}
import java.time.{Instant, LocalDateTime, ZoneId}
import java.util.Date
import scala.concurrent.duration.{FiniteDuration, MINUTES, SECONDS}
import scala.concurrent.{Future, blocking}
import scala.io.{Codec, Source}

case class MonitorConfig(m:Monitor, path:Path)
case class GpsReaderConfig(monitorConfigs: Seq[MonitorConfig])
import scala.concurrent.ExecutionContext.Implicits.global

object GpsDataReader {
  def start(actorSystem: ActorSystem, environment: Environment,
            sysConfig: SysConfigDB, monitorDB: MonitorDB, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB) = {

    val rootPath = environment.rootPath.toPath.resolve("report_template")
    val monitorConfigs = Seq(
      MonitorConfig(Monitor("CB", "彰濱"), rootPath.resolve("CBPV.series")),
      MonitorConfig(Monitor("DD", "大肚"), rootPath.resolve("DDSC.series")),
      MonitorConfig(Monitor("TC", "台中"), rootPath.resolve("TCPP.series"))
    )
    val config = GpsReaderConfig(monitorConfigs)

    actorSystem.actorOf(props(config, sysConfig, monitorDB, monitorTypeOp,
      recordOp), "gpsReader")
  }

  def props(config: GpsReaderConfig, sysConfig: SysConfigDB,
            monitorDB: MonitorDB, monitorTypeOp: MonitorTypeDB,
            recordOp: RecordDB) =
    Props(classOf[GpsDataReader], config, sysConfig, monitorDB, monitorTypeOp, recordOp)

  case object ParseReport
}

class GpsDataReader(config: GpsReaderConfig, sysConfig: SysConfigDB,
                    monitorDB: MonitorDB, monitorTypeOp: MonitorTypeDB, recordOp: RecordDB) extends Actor {
  Logger.info(s"GpsDataReader start")


  import WeatherReader._

  val monitorTypes: Seq[MonitorType] = Seq(
    monitorTypeOp.rangeType("E", "E", "mm", 6),
    monitorTypeOp.rangeType("N", "N", "mm", 6),
    monitorTypeOp.rangeType("V", "V", "mm", 6),
    monitorTypeOp.rangeType("SigmaE", "SigmaE", "mm", 6),
    monitorTypeOp.rangeType("SigmaN", "SigmaN", "mm", 6),
    monitorTypeOp.rangeType("SigmaV", "SigmaV", "mm", 6),
    monitorTypeOp.rangeType("CorrEN", "CorrEN", "mm", 6),
    monitorTypeOp.rangeType("CorrEV", "CorrEV", "mm", 6),
    monitorTypeOp.rangeType("CorrNV", "CorrNV", "mm", 6)
  )

  val mtList = monitorTypes.map(_._id)
  monitorTypes.foreach(mt=>{
    monitorTypeOp.ensureMonitorType(mt)
    monitorTypeOp.ensureMeasuring(mt._id)
    recordOp.ensureMonitorType(mt._id)
  })

  config.monitorConfigs.foreach(mc=> monitorDB.ensureMonitor(mc.m._id))
  self ! ParseReport

  override def receive: Receive = {
    case ParseReport =>
      Future {
        blocking {
          try {
            config.monitorConfigs.foreach(mc=>{{
              fileParser(mc.m._id, mc.path)
              Logger.info(s"Finish parsing ${mc.m._id}, ${mc.path}")
            }})
            sysConfig.setImportGPS(true)
          } catch {
            case ex: Throwable =>
              Logger.error("fail to process file", ex)
          }
        }
      }
  }

  def fileParser(monitorID: String, path: Path): Unit = {
    import scala.collection.mutable.ListBuffer

    var processedLine = 0
    val src = Source.fromFile(path.toFile)(Codec.UTF8)
    try {
      val lines = src.getLines()
      val docList = ListBuffer.empty[RecordList]
      for (line <- lines) {
        try {
          val token: Array[String] = line.split("\\s+")

          val year = token(11).toInt
          val month = token(12).toInt
          val day = token(13).toInt
          val hour = token(14).toInt
          val min = token(15).toInt
          val sec = token(16).toInt
          val dt = LocalDateTime.of(year, month, day, hour, min, sec)


          val mtRecordOpts: Seq[Option[MtRecord]] =
            for ((mt, idx) <- mtList.zipWithIndex) yield {
              try {
                val value = token(idx + 1).toDouble
                Some(MtRecord(mt, Some(value), MonitorStatus.NormalStat))
              } catch {
                case _: Exception =>
                  None
              }
            }

          docList.append(RecordList(time = Date.from(dt.atZone(ZoneId.systemDefault()).toInstant), monitor = monitorID,
            mtDataList = mtRecordOpts.flatten))
        } catch {
          case ex: Throwable =>
            Logger.warn("skip unknown line", ex)
        } finally {
          processedLine = processedLine + 1
        }
      }

      if (docList.nonEmpty) {
        Logger.debug(s"update ${docList.head}")
        recordOp.upsertManyRecords(recordOp.HourCollection)(docList)
      }
    } finally {
      src.close()
    }
  }

  override def postStop(): Unit = {
    super.postStop()
  }

}
