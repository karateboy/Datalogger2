package models

import akka.actor._
import com.github.nscala_time.time.Imports.{DateTimeFormat, richInt}
import com.github.tototoshi.csv.CSVReader
import models.DataImporter.FileType
import models.ModelHelper.{errorHandler, getPeriods}
import org.apache.poi.ss.usermodel.{CellType, WorkbookFactory}
import org.joda.time.{DateTime, LocalDateTime, Period}
import play.api._

import java.io.File
import java.util.Locale
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}
import scala.util.{Failure, Success}


object DataImporter {
  var n = 0
  private var actorRefMap = Map.empty[String, ActorRef]

  def start(monitorOp: MonitorOp, recordOp: RecordOp, sensorOp: MqttSensorOp,
            dataFile: File, fileType: FileType, dataCollectManagerOp: DataCollectManagerOp)(implicit actorSystem: ActorSystem) = {
    val name = getName
    val actorRef = actorSystem.actorOf(DataImporter.props(monitorOp = monitorOp,
      recordOp = recordOp, sensorOp = sensorOp,
      dataFile = dataFile, fileType, dataCollectManagerOp = dataCollectManagerOp), name)
    actorRefMap = actorRefMap + (name -> actorRef)
    name
  }

  def getName: String = {
    n = n + 1
    s"dataImporter${n}"
  }

  def props(monitorOp: MonitorOp, recordOp: RecordOp, sensorOp: MqttSensorOp,
            dataFile: File, fileType: FileType, dataCollectManagerOp: DataCollectManagerOp): Props =
    Props(new DataImporter(monitorOp, recordOp, sensorOp, dataFile, fileType, dataCollectManagerOp))

  def finish(actorName: String): Unit = {
    actorRefMap = actorRefMap.filter(p => {
      p._1 != actorName
    })
  }

  def isFinished(actorName: String): Boolean = {
    !actorRefMap.contains(actorName)
  }

  sealed trait FileType

  final case object SensorData extends FileType

  final case object SensorRawData extends FileType

  final case object UpdateSensorData extends FileType

  final case object EpaData extends FileType

  case object Import

  case object Complete
}

class DataImporter(monitorOp: MonitorOp, recordOp: RecordOp, sensorOp: MqttSensorOp,
                   dataFile: File, fileType: FileType, dataCollectManagerOp: DataCollectManagerOp) extends Actor {

  import DataImporter._

  self ! Import

  def receive: Receive = {
    case Import =>
      Future {
        blocking {
          try {
            fileType match {
              case SensorData =>
                try {
                  if (importSensorData("UTF-8") == 0)
                    importSensorData("BIG5")
                } catch {
                  case ex: Throwable =>
                    Logger.error("failed to import sensor data", ex)
                }
              case SensorRawData =>
                try {
                  importSensorRawData("UTF-8")
                } catch {
                  case ex: Throwable =>
                    Logger.error("failed to import sensor raw data", ex)
                }
              case UpdateSensorData =>
                try {
                  importSensorRawData("UTF-8", updateOnly = true)
                } catch {
                  case ex: Throwable =>
                    Logger.error("failed to update sensor raw data", ex)
                }
              case EpaData =>
                importEpaData()
            }
          } catch {
            case ex: Exception =>
              Logger.error("failed to import", ex)
          }
        }
      }
    case Complete =>
      finish(context.self.path.name)
      self ! PoisonPill
  }

  private def importSensorData(encoding: String): Int = {
    Logger.info(s"Start import ${dataFile.getName}")
    val reader = CSVReader.open(dataFile, encoding)
    var count = 0
    val docOpts =
      for (record <- reader.allWithHeaders()) yield
        try {
          val deviceID = record("DEVICE_NAME")
          val time = try {
            LocalDateTime.parse(record("TIME"), DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ss")).toDate
          } catch {
            case _: IllegalArgumentException =>
              LocalDateTime.parse(record("TIME"), DateTimeFormat.forPattern("YYYY/MM/dd HH:mm")).toDate
          }
          val value = record("VALUE(小時平均值)").toDouble
          count = count + 1
          Some(RecordList(time = time, monitor = deviceID,
            mtDataList = Seq(MtRecord(mtName = MonitorType.PM25, value, MonitorStatus.NormalStat))))
        } catch {
          case ex: Throwable =>
            None
        }
    val docs = docOpts.flatten

    reader.close()
    Logger.info(s"Total $count records")
    if (docs.nonEmpty) {
      dataFile.delete()
      val f = recordOp.upsertManyRecord(docs = docs)(recordOp.HourCollection)
      f onFailure (errorHandler)
      f onComplete ({
        case Success(result) =>
          Logger.info(s"Import ${dataFile.getName} complete. ${result.getUpserts.size()} records upserted.")
          self ! Complete
        case Failure(exception) =>
          Logger.error("Failed to import data", exception)
          self ! Complete
      })
    }
    docs.size
  }

  private def importSensorRawData(encoding: String, updateOnly: Boolean = false): Int = {
    Logger.info(s"Start import sensor raw ${dataFile.getName}")
    val sensorMapF = sensorOp.getSensorMap
    val reader = CSVReader.open(dataFile, encoding)
    var count = 0
    val mtMap: Map[String, String] =
      Map[String, String](
        "pm2_5" -> MonitorType.PM25,
        "pm10" -> MonitorType.PM10,
        "humidity" -> MonitorType.HUMID,
        "o3" -> MonitorType.O3,
        "temperature" -> MonitorType.TEMP,
        "voc" -> MonitorType.VOC,
        "no2" -> MonitorType.NO2,
        "h2s" -> MonitorType.H2S,
        "nh3" -> MonitorType.NH3)

    val docOpts =
      for (record <- reader.allWithHeaders()) yield
        try {
          val deviceID = if(record.contains("id"))
            record("id").trim.toDouble.formatted("%.0f")
          else
            record("IMEI").trim.toDouble.formatted("%.0f")

          val time = try {
            DateTime.parse(record("time"), DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ss")).toDate
          } catch {
            case _: IllegalArgumentException =>
              DateTime.parse(record("time"), DateTimeFormat.forPattern("YYYY/MM/dd HH:mm")).toDate
          }

          val mtRecords =
            for {mt <- mtMap.keys.toList
                 value <- record.get(mt) if value.nonEmpty
                 } yield
              MtRecord(mtName = mtMap(mt), value.toDouble, MonitorStatus.NormalStat)

          count = count + 1

          Some(RecordList(time = time, monitor = deviceID,
            mtDataList = mtRecords))
        } catch {
          case ex: Throwable =>
            Logger.error("skip line", ex)
            None
        }
    val docs = docOpts.flatten
    reader.close()
    Logger.info(s"Total $count records")
    if (docs.nonEmpty) {
      dataFile.delete()
      val f = recordOp.upsertManyRecord(docs = docs)(recordOp.MinCollection)

      f onFailure errorHandler
      f onComplete {
        case Success(result) =>
          Logger.info(s"Import ${dataFile.getName} complete.")
          val start = new DateTime(docs.map(_._id.time).min)
          val end = new DateTime(docs.map(_._id.time).max)
          val monitors = mutable.Set.empty[String]
          docs.foreach(recordList=>monitors.add(recordList._id.monitor))
          for {
            monitorID <- monitors
            current <- getPeriods(start, end, new Period(1, 0,0,0))
            monitor <- monitorOp.map.get(monitorID)
          }
            dataCollectManagerOp.recalculateHourData(monitorID, current, forward = false, alwaysValid = true)(monitor.monitorTypes)

          self ! Complete
        case Failure(exception) =>
          Logger.error("Failed to import data", exception)
          self ! Complete
      }
    } else {
      Logger.error("no record to be upsert!")
      self ! Complete
    }

    docs.size
  }


  def importEpaData(): Unit = {
    Logger.info(s"Start import ${dataFile.getName}")
    val reader = CSVReader.open(dataFile, "Big5")
    val docs =
      for {record <- reader.allWithHeaders()
           monitorType = record("測項") if monitorType == "PM2.5"
           monitorName = record("測站")
           monitorOpt = monitorOp.map.find(p => {
             p._2.desc == monitorName
           }) if monitorOpt.isDefined
           } yield {
        val time = try {
          LocalDateTime.parse(record("時間"), DateTimeFormat.forPattern("YYYY-MM-dd HH:mm")).toDate
        } catch {
          case _: IllegalArgumentException =>
            LocalDateTime.parse(record("時間"), DateTimeFormat.forPattern("YYYY/MM/dd HH:mm")).toDate
        }

        try {
          val value = record("資料").toDouble
          RecordList(time = time, monitor = monitorOpt.get._1, mtDataList = Seq(MtRecord(mtName = MonitorType.PM25, value, MonitorStatus.NormalStat)))
        } catch {
          case _: java.lang.NumberFormatException =>
            RecordList(time = time, monitor = monitorOpt.get._1, mtDataList = Seq.empty[MtRecord])
        }
      }
    reader.close()
    dataFile.delete()
    Logger.info(s"Total ${docs.length} records")
    val f = recordOp.upsertManyRecord(docs = docs)(recordOp.HourCollection)
    f onFailure (errorHandler)
    f onComplete ({
      case Success(result) =>
        Logger.info(s"Import ${dataFile.getName} complete. ${result.getUpserts.size()} records upserted.")
        self ! Complete
      case Failure(exception) =>
        Logger.error("Failed to import data", exception)
        self ! Complete
    })
  }
}