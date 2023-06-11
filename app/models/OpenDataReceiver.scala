package models

import akka.actor.{Actor, Cancellable}
import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import play.api._
import play.api.libs.json.{JsError, Json}
import play.api.libs.ws._

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


object OpenDataReceiver {
  case object GetEpaHourData

  case class ReloadEpaData(start: DateTime, end: DateTime)
}

@Singleton
class OpenDataReceiver @Inject()(monitorTypeOp: MonitorTypeDB, monitorOp: MonitorDB, recordOp: RecordDB,
                                 WSClient: WSClient,
                                 sysConfigDB: SysConfigDB, epaMonitorOp: EpaMonitorOp) extends Actor {

  import OpenDataReceiver._
  import com.github.nscala_time.time.Imports._

  val epaMonitors: Seq[Monitor] = epaMonitorOp.getEpaMonitors.getOrElse(Seq.empty[Monitor])

  if(epaMonitors.nonEmpty){
    for (epaMonitor <- epaMonitors)
      Logger.info(s"OpenDataReceiver set up to receive $epaMonitor")

    epaMonitors.foreach(monitorOp.ensure)
    monitorTypeOp.epaToMtMap.values.foreach(mt => {
      monitorTypeOp.ensure(mt)
      recordOp.ensureMonitorType(mt)
    })
  }


  val timerOpt: Option[Cancellable] = {
    import scala.concurrent.duration._
    if (epaMonitors.nonEmpty)
      Some(context.system.scheduler.schedule(FiniteDuration(5, SECONDS), FiniteDuration(1, HOURS), self, GetEpaHourData))
    else
      None
  }

  def receive: Receive = {
    case GetEpaHourData =>
      for {startDate <- sysConfigDB.getEpaLastRecordTime
           start = new DateTime(startDate).minusDays(7).withTimeAtStartOfDay()
           end = DateTime.now().withTimeAtStartOfDay()
           } {

        Logger.info(s"Get EpaData ${start.toString("yyyy-MM-d")} => ${end.toString("yyyy-MM-d")}")
        for (retF <- fetchEpaHourData(start, end)) {
          for (success <- retF if success) {
            Logger.info(s"Get EpaData ${start.toString("yyyy-MM-d")} => ${end.toString("yyyy-MM-d")} successful")
            sysConfigDB.setEpaLastRecordTime(end)
          }
        }
      }

    case ReloadEpaData(start, end) =>
      Logger.info(s"reload EpaData ${start.toString("yyyy-MM-d")} => ${end.toString("yyyy-MM-d")}")
      fetchEpaHourData(start, end)
  }

  private def fetchEpaHourData(start: DateTime, end: DateTime): Option[Future[Boolean]] = {
    if (epaMonitorOp.upstream.isEmpty)
      Logger.error("openData did not config upstream!")

    import recordOp.recordListRead
    for (upstream <- epaMonitorOp.upstream) yield {
      val epaMonitorsIDs = epaMonitors.map(_._id).mkString(":")
      val startNum = start.getMillis
      val endNum = end.getMillis
      val f = WSClient.url(s"$upstream/HourRecord/$epaMonitorsIDs/$startNum/$endNum").get()
      f onFailure errorHandler
      for (response <- f) yield {
        val ret = response.json.validate[Seq[RecordList]]
        ret.fold(
          err => {
            Logger.error(JsError.toJson(err).toString())
            false
          },
          recordLists => {
            Logger.info(s"Total ${recordLists.size} records fetched.")
            recordOp.upsertManyRecords(recordOp.HourCollection)(recordLists)
            true
          }
        )
      }
    }
  }

  override def postStop: Unit = {
    for (timer <- timerOpt)
      timer.cancel()
  }

}

