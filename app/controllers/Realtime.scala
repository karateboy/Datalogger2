package controllers

import com.github.nscala_time.time.Imports._
import models._
import play.api.libs.json._
import play.api.mvc._

import javax.inject._
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global

class Realtime @Inject()
(monitorTypeOp: MonitorTypeDB, dataCollectManagerOp: DataCollectManagerOp, instrumentOp: InstrumentDB,
 monitorStatusOp: MonitorStatusDB, groupDB: GroupDB, recordDB: RecordDB, monitorDB: MonitorDB) extends Controller {
  val overTimeLimit = 6

  case class MonitorTypeStatus(_id: String, desp: String, value: String, unit: String, instrument: String, status: String, classStr: Seq[String], order: Int)

  def MonitorTypeStatusList() = Security.Authenticated.async {
    implicit request =>

      implicit val mtsWrite = Json.writes[MonitorTypeStatus]

      val result =
        for {
          instrumentMap <- instrumentOp.getInstrumentMap()
          dataMap <- dataCollectManagerOp.getLatestData()
        } yield {
          val list = {
            for {
              mt <- monitorTypeOp.realtimeMtvList.sortBy(monitorTypeOp.map(_).order)
              recordOpt = dataMap.get(mt)
            } yield {
              val mCase = monitorTypeOp.map(mt)
              val measuringByStr = mCase.measuringBy.map {
                instrumentList =>
                  instrumentList.mkString(",")
              }.getOrElse("??")

              def instrumentStatus() = {
                val ret =
                  for (measuringBy <- mCase.measuringBy) yield {
                    val instruments: List[Instrument] =
                      measuringBy.map(instrumentMap.get(_)).flatten

                    if (instruments.exists(inst => !inst.active))
                      "停用"
                    else
                      "斷線"
                  }
                ret.getOrElse("設備啟動中")
              }

              if (recordOpt.isDefined) {
                val record = recordOpt.get
                val duration = new Duration(record.time, DateTime.now())
                val (overInternal, overLaw) = monitorTypeOp.overStd(mt, record.value)
                val status = if (duration.getStandardSeconds <= overTimeLimit)
                  monitorStatusOp.map(record.status).desp
                else
                  instrumentStatus

                MonitorTypeStatus(_id = mCase._id, desp = mCase.desp, monitorTypeOp.format(mt, record.value),
                  mCase.unit, measuringByStr,
                  status,
                  MonitorStatus.getCssClassStr(record.status, overInternal, overLaw), mCase.order)
              } else {
                if (instrumentStatus == "停用")
                  MonitorTypeStatus(_id = mCase._id, mCase.desp, monitorTypeOp.format(mt, None),
                    mCase.unit, measuringByStr,
                    instrumentStatus,
                    Seq("stop_status"), mCase.order)
                else
                  MonitorTypeStatus(_id = mCase._id, mCase.desp, monitorTypeOp.format(mt, None),
                    mCase.unit, measuringByStr,
                    instrumentStatus,
                    Seq("disconnect_status"), mCase.order)
              }
            }
          }

          Ok(Json.toJson(list))
        }

      result
  }

  case class PowerUsage(name: String, usageThisMonth: Double, usageToday: Double)

  private def getUsageMap(recordLists: Seq[RecordList]): mutable.Map[String, Double] = {
    val usageMap = mutable.Map.empty[String, Double]
    recordLists.foreach(recordList => {
      for {mtRecord <- recordList.mtMap.get(MonitorType.POWER)
           usage <- mtRecord.value
           lastUsage = usageMap.getOrElseUpdate(recordList._id.monitor, 0d)
           }
        usageMap.update(recordList._id.monitor, lastUsage + usage)
    })
    usageMap
  }

  def getPowerUsageList: Action[AnyContent] = Security.Authenticated.async {
    implicit request =>
      val userInfo = request.user

      val group = groupDB.getGroupByID(userInfo.group).get

      val now = DateTime.now()
      val monthStart = now.withTimeAtStartOfDay().withDayOfMonth(1)
      val monthUsageMapFuture =
        for (monthRecords <- recordDB.getRecordListFuture(recordDB.HourCollection)(monthStart, DateTime.now, monitorDB.mvList)) yield
          getUsageMap(monthRecords)

      val dayUsageMapFuture =
        for (dayRecords <- recordDB.getRecordListFuture(recordDB.HourCollection)(now.withTimeAtStartOfDay(), DateTime.now, monitorDB.mvList)) yield
          getUsageMap(dayRecords)
      val retFuture =
        for {
          monthUsageMap <- monthUsageMapFuture
          dayUsageMap <- dayUsageMapFuture} yield {
          for ((m, idx) <- monitorDB.mvList.zipWithIndex) yield {
            if (group.monitors.contains(m) || userInfo.isAdmin)
              PowerUsage(monitorDB.map(m).desc, monthUsageMap.getOrElse(m, 0d), dayUsageMap.getOrElse(m, 0d))
            else
              PowerUsage(s"用戶${idx + 1}", monthUsageMap.getOrElse(m, 0d), dayUsageMap.getOrElse(m, 0d))
          }
        }

      implicit val writes: OWrites[PowerUsage] = Json.writes[PowerUsage]
      for (ret <- retFuture) yield
        Ok(Json.toJson(ret))
  }
}