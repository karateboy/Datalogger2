package models

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import models.MonitorType.TEMP
import play.api._
import play.api.libs.ws._

import javax.inject.{Inject, Singleton}
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global


object OpenDataReceiver {
  case object GetEpaHourData
  case class ReloadEpaData(start:DateTime, end:DateTime)
}

@Singleton
class OpenDataReceiver @Inject()(monitorTypeOp: MonitorTypeDB, monitorOp: MonitorDB, recordOp: RecordDB,
                                 WSClient: WSClient,
                                 sysConfigDB: SysConfigDB) extends Actor {

  import OpenDataReceiver._
  import com.github.nscala_time.time.Imports._

  monitorTypeOp.ensure(MonitorType(TEMP, "溫度", "℃", 2, MonitorType.rangeOrder))

  val epaMonitors: Seq[Monitor] = Seq(Monitor("12", "台南"))

  for(epaMonitor <- epaMonitors)
    Logger.info(s"OpenDataReceiver set up to receive $epaMonitor")


  val timerOpt = {
    import scala.concurrent.duration._
    if(epaMonitors.nonEmpty)
      Some(context.system.scheduler.schedule(FiniteDuration(5, SECONDS), FiniteDuration(1, HOURS), self, GetEpaHourData))
    else
      None
  }

  import scala.xml._

  def receive: Receive = {
    case GetEpaHourData =>
      for{startDate <- sysConfigDB.getEpaLastRecordTime
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

  private def getEpaHourData(start: DateTime, end: DateTime): Unit = {
    val limit = 500

    def parser(node: Elem) = {
      import scala.collection.mutable.Map
      import scala.xml.Node
      val timeMap = mutable.Map.empty[DateTime, mutable.Map[String, Double]]

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

      def processData(dataNode: Node): Unit = {
        val siteName = dataNode \ "SiteName".toUpperCase
        val itemId = dataNode \ "ItemId".toUpperCase
        val monitorDateOpt = dataNode \ "MonitorDate".toUpperCase
        val siteID = (dataNode \ "SiteId".toUpperCase).text.trim.toInt

        try {
          //Filter interested EPA monitor 台南(12) TEMP 14
          if (siteID == 12 &&
            itemId.text.trim().toInt == 14) {
            val monitorType = MonitorType.TEMP
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

      qualifiedData.foreach {
        processData
      }

      val recordLists =
        for {
          monitor <- monitorOp.mvList
          dateTime <- timeMap.keys.toList.sorted
        } yield {
          val mtRecords = timeMap(dateTime).map(pair=>MtRecord(pair._1, Some(pair._2), MonitorStatus.NormalStat)).toSeq
          RecordList(_id = RecordListID(dateTime.toDate, monitor), mtDataList = mtRecords)
        }

      if(recordLists.nonEmpty)
        recordOp.upsertManyRecords(recordOp.HourCollection)(recordLists)

      qualifiedData.size
    }

    def getThisMonth(skip: Int): Unit = {
      val url = s"https://data.epa.gov.tw/api/v2/aqx_p_15?format=xml&offset=${skip}&limit=${limit}&api_key=31961023-dee3-442c-9de6-c593346d6918&&sort=MonitorDate%20desc"
      val future =
        WSClient.url(url).get().map {
          response =>
            try {
              parser(response.xml)
            } catch {
              case ex: Exception =>
                Logger.error(ex.toString)
                throw ex
            }
        }
      future onFailure (errorHandler())
      future onSuccess ({
        case ret: Int =>
          if (ret < limit) {
            Logger.info(s"Import EPA ${start.getYear}/${start.getMonthOfYear} complete")
          } else
            getThisMonth(skip + limit)
      })
    }

    def getMonthData(year:Int, month:Int, skip: Int): Unit = {
      val url = f"https://data.epa.gov.tw/api/v2/aqx_p_15?format=xml&offset=$skip%d&limit=$limit&year_month=$year%d_$month%02d&api_key=31961023-dee3-442c-9de6-c593346d6918"
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
            Logger.error(resp.xml.toString())
            Logger.error(ex.toString)
            throw ex
        }
      }
    }

    //if(start.toString("yyyy-M") == DateTime.now().toString("yyyy-M"))
      getThisMonth(0)
    //else{
    //  getMonthData(start.getYear(), start.getMonthOfYear(), 0)
    //}
  }

  override def postStop: Unit = {
    for(timer<-timerOpt)
      timer.cancel()
  }

}

