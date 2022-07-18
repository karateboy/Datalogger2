package models

import com.github.nscala_time.time.Imports._
import play.api._
import play.api.libs.json.Json

import java.nio.file.Path
import java.util.Date
import javax.inject.{Inject, Singleton}
import javax.xml.ws.Holder

object CdxUploader {
  case class ItemIdMap(epaId: Int, itemName: String)
  case class CdxMonitorType(mt:String, name:String, min:Option[Double], max:Option[Double])

  import MonitorType._
  val itemIdMap = Map(
    SO2 -> ItemIdMap(1, "二氧化硫"),
    NOX -> ItemIdMap(5, "氮氧化物"),
    NO -> ItemIdMap(6, "一氧化氮"),
    NO2 -> ItemIdMap(7, "二氧化氮"),
    CO -> ItemIdMap(2, "一氧化碳"),
    O3 -> ItemIdMap(3, "臭氧"),
    CH4 -> ItemIdMap(25, "甲烷"),
    NMHC -> ItemIdMap(9, "非甲烷"),
    THC -> ItemIdMap(8, "總碳氫"),
    PM10 -> ItemIdMap(4, "懸浮微粒"),
    PM25 -> ItemIdMap(27, "細懸浮微粒"),
    WIN_SPEED -> ItemIdMap(10, "風速"),
    WIN_DIRECTION -> ItemIdMap(11, "風向"),
    TEMP -> ItemIdMap(14, "大氣溫度"),
    HUMID -> ItemIdMap(31, "溼度"),
    PRESS -> ItemIdMap(17, "大氣壓力"),
    RAIN -> ItemIdMap(23, "雨量"),
    TEMP -> ItemIdMap(16, "室內溫度"))

  case class CdxConfig(enable:Boolean, user:String, password:String, siteCounty:String, siteID:String)

  implicit val reads = Json.reads[CdxConfig]
  implicit val writes = Json.writes[CdxConfig]
  implicit val read1 = Json.reads[CdxMonitorType]
  implicit val write1 = Json.writes[CdxMonitorType]

  val defaultConfig = CdxConfig(false, "anonymous", "password", "10013", "026")
  val defaultMonitorTypes: Seq[CdxMonitorType] =
    for(kv<-itemIdMap.toList) yield
      CdxMonitorType(kv._1, kv._2.itemName, min=None, max=None)
}

@Singleton
class CdxUploader @Inject()(alarmDB: AlarmDB, environment: Environment){
  import CdxUploader._
  val serviceID = "AQX_S_00"

  def mtRecprdToXML(siteCounty: String, siteID: String, date: Date, mtRecord: MtRecord) = {
    val map = itemIdMap(mtRecord.mtName)
    val dateTime = new DateTime(date)
    val dateStr = dateTime.toString("YYYY-MM-dd")
    val timeStr = dateTime.toString("HH:mm:ss")
    <aqs:AirQualityData>
      <aqs:SiteIdentifierDetails>
        <aqs:SiteCounty>{ siteCounty }</aqs:SiteCounty>
        <aqs:SiteID>{ siteID }</aqs:SiteID>
      </aqs:SiteIdentifierDetails>
      <aqs:MonitorIdentifierDetails>
        <aqs:Parameter>{ "%03d".format(map.epaId) }</aqs:Parameter>
      </aqs:MonitorIdentifierDetails>
      <aqs:TransactionProtocolDetails>
        <aqs:SamplingDurationCode>1</aqs:SamplingDurationCode>
      </aqs:TransactionProtocolDetails>
      <aqs:SubDailyRawData>
        <aqs:ActionIndicator>I</aqs:ActionIndicator>
        <aqs:SampleCollectionStartDate>{ dateStr }</aqs:SampleCollectionStartDate>
        <aqs:SampleCollectionStartTime>{ timeStr }</aqs:SampleCollectionStartTime>
        {
        val valueElem = <aqs:ReportedSampleValue>{ mtRecord.value.getOrElse("") }</aqs:ReportedSampleValue>
        if(MonitorStatus.isValid(mtRecord.status)){
          valueElem
        }else{
          if (MonitorStatus.isCalbration(mtRecord.status)){
            valueElem ++ <aqs:QualifierCode01>D50</aqs:QualifierCode01>
          }else{
            valueElem ++ <aqs:QualifierCode01>D51</aqs:QualifierCode01>
          }
        }
        }
      </aqs:SubDailyRawData>
    </aqs:AirQualityData>
  }

  def getXml(path: Path, recordList: RecordList, cdxConfig: CdxConfig, mtConfigs: Seq[CdxMonitorType]): String = {
    val xmlList = recordList.mtDataList.flatMap { mtRecord =>
      try {
        val mt = mtRecord.mtName
        if (itemIdMap.contains(mt)) {
          val cdxMtConfig =  mtConfigs.find(mtConfig=>mtConfig.mt == mt).getOrElse(CdxMonitorType(mt, mt, None, None))
          cdxMtConfig match {
            case CdxMonitorType(_, _, Some(min), Some(max))=>
              for(v<-mtRecord.value if v>= min && v <= max) yield
                mtRecprdToXML(cdxConfig.siteCounty, cdxConfig.siteID, recordList._id.time, mtRecord)

            case CdxMonitorType(_, _, None, Some(max))=>
              for(v<-mtRecord.value if v <= max) yield
                mtRecprdToXML(cdxConfig.siteCounty, cdxConfig.siteID, recordList._id.time, mtRecord)

            case CdxMonitorType(_, _, Some(min), None)=>
              for(v<-mtRecord.value if v>= min) yield
                mtRecprdToXML(cdxConfig.siteCounty, cdxConfig.siteID, recordList._id.time, mtRecord)

            case _=>
              Some(mtRecprdToXML(cdxConfig.siteCounty, cdxConfig.siteID, recordList._id.time, mtRecord))
          }
        } else
          None
      } catch {
        case _: java.util.NoSuchElementException =>
          None
      }
    }
    val nowStr = DateTime.now().toString("YYYY-MM-dd_HH:mm:ss")

    val xml =
      <aqs:AirQualitySubmission xmlns:aqs="http://taqm.epa.gov.tw/taqm/aqs/schema/" Version="1.0" n1:schemaLocation="http://taqm.epa.gov.tw/taqm/aqs/schema/" xmlns:n1="http://www.w3.org/2001/XMLSchema-instance">
        <aqs:FileGenerationPurposeCode>AQS</aqs:FileGenerationPurposeCode>
        <aqs:FileGenerationDateTime>{ nowStr }</aqs:FileGenerationDateTime>
        { xmlList }
      </aqs:AirQualitySubmission>

    val dateTime = new DateTime(recordList._id.time)
    val tempFile = s"${serviceID}_${dateTime.toString("MMdd")}${dateTime.getHourOfDay}_${cdxConfig.user}.xml"
    scala.xml.XML.save(path.resolve(tempFile).toString, xml, "UTF-8", true)
    //scala.io.Source.fromFile(tempFile)("UTF-8").mkString
    xml.toString
  }

  def upload(recordList: RecordList, cdxConfig: CdxConfig, mtConfigs: Seq[CdxMonitorType]) = {
    val localPath = environment.rootPath.toPath.resolve("cdxUpload")
    val dateTime = new DateTime(recordList._id.time)
    val fmt = DateTimeFormat.fullDateTime()
    val xmlStr = getXml(localPath, recordList, cdxConfig, mtConfigs)

    if (cdxConfig.enable) {
      val fileName = s"${serviceID}_${dateTime.toString("MMdd")}${dateTime.getHourOfDay}_${cdxConfig.user}.xml"
      val errMsgHolder = new Holder("")
      val resultHolder = new Holder(Integer.valueOf(0))
      val unknownHolder = new Holder(new java.lang.Boolean(true))
      try{
        CdxWebService.service.putFile(cdxConfig.user, cdxConfig.password, fileName, xmlStr.getBytes("UTF-8"), errMsgHolder, resultHolder, unknownHolder)
        if (resultHolder.value != 1) {
          Logger.error(s"errMsg:${errMsgHolder.value}")
          Logger.error(s"ret:${resultHolder.value.toString}")
          Logger.error(s"unknown:${unknownHolder.value.toString}")
          alarmDB.log(alarmDB.srcCDX(), alarmDB.Level.ERR, s"CDX上傳${dateTime.toString(fmt)}小時值失敗 錯誤訊息 ${errMsgHolder.value}")
        } else {
          Logger.info(s"Success upload ${dateTime.date.toString}")
          alarmDB.log(alarmDB.srcCDX(), alarmDB.Level.INFO, s"CDX 上傳${dateTime.toString(fmt)}小時值成功")
        }
      }catch{
        case ex:Throwable =>
          Logger.error("CDX: 上傳錯誤", ex)
          alarmDB.log(alarmDB.srcCDX(), alarmDB.Level.ERR, s"CDX上傳${dateTime.toString(fmt)}小時值失敗 錯誤訊息 ${ex.getMessage}")
      }
    }else{
      alarmDB.log(alarmDB.srcCDX(), alarmDB.Level.INFO, s"CDX 模擬上傳${dateTime.toString(fmt)}小時值成功")
    }
  }
}