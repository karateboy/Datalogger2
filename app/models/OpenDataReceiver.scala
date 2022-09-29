package models

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import play.api._
import play.api.libs.ws._

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global


object OpenDataReceiver {
  case object GetEpaHourData
  case class ReloadEpaData(start:DateTime, end:DateTime)
}

@Singleton
class OpenDataReceiver @Inject()(monitorTypeOp: MonitorTypeDB, monitorOp: MonitorDB, recordOp: RecordDB,
                                 WSClient: WSClient,
                                 sysConfigDB: SysConfigDB, epaMonitorOp: EpaMonitorOp) extends Actor {

  import OpenDataReceiver._
  import com.github.nscala_time.time.Imports._

  val epaMonitors: Seq[Monitor] = epaMonitorOp.getEpaMonitors().getOrElse(Seq.empty[Monitor])

  for(epaMonitor <- epaMonitors)
    Logger.info(s"OpenDataReceiver set up to receive $epaMonitor")

  epaMonitors.foreach(monitorOp.ensure)

  val timerOpt = {
    import scala.concurrent.duration._
    if(epaMonitors.nonEmpty)
      Some(context.system.scheduler.schedule(FiniteDuration(5, SECONDS), FiniteDuration(1, HOURS), self, GetEpaHourData))
    else
      None
  }

  import scala.xml._

  def receive = {
    case GetEpaHourData =>
      for{startDate <- sysConfigDB.getEpaLastRecordTime()
          start = new DateTime(startDate)
          end = DateTime.now()
          }{

        if(end.minusDays(1) > start){
          Logger.info(s"Get EpaData ${start.toString("yyyy-MM-d")} => ${end.toString("yyyy-MM-d")}")
          getEpaHourData(start, end)
        }
      }

    case ReloadEpaData(start, end) =>
      Logger.info(s"reload EpaData ${start.toString("yyyy-MM-d")} => ${end.toString("yyyy-MM-d")}")
      getEpaHourData(start, end)
  }

  def getEpaHourData(start: DateTime, end: DateTime) {
    val limit = 500

    def parser(node: Elem) = {
      import scala.collection.mutable.Map
      import scala.xml.Node
      val recordMap = Map.empty[String, Map[DateTime, Map[String, Double]]]

      def filter(dataNode: Node) = {
        val monitorDateOpt = dataNode \ "MonitorDate".toUpperCase
        val mDate =
          try{
            DateTime.parse(s"${monitorDateOpt.text.trim()}", DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ss"))
          }catch{
            case _:Exception=>
              DateTime.parse(s"${monitorDateOpt.text.trim()}", DateTimeFormat.forPattern("YYYY-MM-dd"))
          }

        start <= mDate && mDate < end
      }

      def processData(dataNode: Node) {
        val siteName = dataNode \ "SiteName".toUpperCase
        val itemId = dataNode \ "ItemId".toUpperCase
        val monitorDateOpt = dataNode \ "MonitorDate".toUpperCase
        val siteID = (dataNode \ "SiteId".toUpperCase).text.trim.toInt

        try {
          //Filter interested EPA monitor
          if (epaMonitorOp.map.contains(siteID) &&
            monitorTypeOp.epaToMtMap.contains(itemId.text.trim().toInt)) {
            val epaMonitor = epaMonitorOp.map(siteID)
            val monitorType = monitorTypeOp.epaToMtMap(itemId.text.trim().toInt)
            val mDate = try {
              DateTime.parse(s"${monitorDateOpt.text.trim()}", DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ss"))
            }catch {
              case _:Exception=>
                DateTime.parse(s"${monitorDateOpt.text.trim()}", DateTimeFormat.forPattern("YYYY-MM-dd"))
            }

            val monitorNodeValueSeq =
              for (v <- 0 to 23) yield {
                val monitorValue = try {
                  Some((dataNode \ "MonitorValue%02d".format(v).toUpperCase).text.trim().toDouble)
                } catch {
                  case x: Throwable =>
                    None
                }
                (mDate + v.hour, monitorValue)
              }

            val timeMap = recordMap.getOrElseUpdate(epaMonitor._id, Map.empty[DateTime, Map[String, Double]])
            for {(mDate, mtValueOpt) <- monitorNodeValueSeq} {
              val mtMap = timeMap.getOrElseUpdate(mDate, Map.empty[String, Double])
              for (mtValue <- mtValueOpt)
                mtMap.put(monitorType, mtValue)
            }
          }
        } catch {
          case x: Throwable =>
            Logger.error("failed", x)
        }
      }

      val data = node \ "data"

      val qualifiedData = data.filter(filter)

      qualifiedData.map {
        processData
      }

      val recordLists =
        for {
          monitorMap <- recordMap
          monitor = monitorMap._1
          timeMaps = monitorMap._2
          dateTime <- timeMaps.keys.toList.sorted
        } yield {
          val mtRecords = timeMaps(dateTime).map(pair=>MtRecord(pair._1, Some(pair._2), MonitorStatus.NormalStat)).toSeq
          RecordList(_id = RecordListID(dateTime.toDate, monitor), mtDataList = mtRecords)
        }

      recordOp.upsertManyRecords(recordOp.HourCollection)(recordLists.toSeq)

      qualifiedData.size
    }

    def getThisMonth(skip: Int) {
      val url = s"https://data.epa.gov.tw/api/v2/aqx_p_15?format=xml&offset=${skip}&limit=${limit}&api_key=1f4ca8f8-8af9-473d-852b-b8f2d575f26a&&sort=MonitorDate%20desc"
      val future =
        WSClient.url(url).get().map {
          response =>
            try {
              parser(response.xml)
            } catch {
              case ex: Exception =>
                Logger.error(ex.toString())
                throw ex
            }
        }
      future onFailure (errorHandler())
      future onSuccess ({
        case ret: Int =>
          if (ret < limit) {
            Logger.info(s"Import EPA ${start.getYear()}/${start.getMonthOfYear()} complete")
          } else
            getThisMonth(skip + limit)
      })
    }

    def getMonthData(year:Int, month:Int, skip: Int) {
      val url = f"https://data.epa.gov.tw/api/v2/aqx_p_15?format=xml&offset=$skip%d&limit=$limit&year_month=$year%d_$month%02d&api_key=1f4ca8f8-8af9-473d-852b-b8f2d575f26a"
      val f = WSClient.url(url).get()
      f onFailure errorHandler()
      for(resp <- f) {
        try {
          val updateCount = parser(resp.xml)
          if (updateCount < limit) {
            Logger.info(f"Import EPA $year/$month%02d complete")
            val dataLast = new DateTime(year, month, 1, 0, 0).plusMonths(1)
            sysConfigDB.setEpaLastRecordTime(dataLast.toDate)
            if(dataLast < end)
              self ! ReloadEpaData(dataLast, end)
          } else
            getMonthData(year, month, skip + limit)
        } catch {
          case ex: Exception =>
            Logger.error(ex.toString())
            throw ex
        }
      }
    }

    if(start.toString("yyyy-M") == DateTime.now().toString("yyyy-M"))
      getThisMonth(0)
    else{
      getMonthData(start.getYear(), start.getMonthOfYear(), 0)
    }
  }

  override def postStop = {
    for(timer<-timerOpt)
      timer.cancel()
  }

}

