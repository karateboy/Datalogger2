package controllers

import akka.actor.ActorRef
import com.github.nscala_time.time.Imports._
import models.ForwardManager.{ForwardHourRecord, ForwardMinRecord}
import models.ModelHelper.errorHandler
import models._
import play.api._
import play.api.data.Forms._
import play.api.data._
import play.api.libs.json._
import play.api.libs.ws.{WSClient, WSResponse}
import play.api.mvc._

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class HomeController @Inject()(userOp: UserOp, instrumentOp: InstrumentOp, dataCollectManagerOp: DataCollectManagerOp,
                               monitorTypeOp: MonitorTypeOp, query: Query, monitorOp: MonitorOp, groupOp: GroupOp,
                               instrumentTypeOp: InstrumentTypeOp, monitorStatusOp: MonitorStatusOp,
                               sensorOp: MqttSensorOp, adam6066: Adam6066, WSClient: WSClient,
                               @Named("dataCollectManager") manager: ActorRef) extends Controller {

  val title = "資料擷取器"
  implicit val userParamRead: Reads[User] = Json.reads[User]

  import groupOp.{read, write}
  import monitorTypeOp.{mtRead, mtWrite}

  def newUser = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      val newUserParam = request.body.validate[User]

      newUserParam.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        param => {
          userOp.newUser(param)
          Ok(Json.obj("ok" -> true))
        })
  }

  def deleteUser(email: String) = Security.Authenticated {
    implicit request =>
      val userInfoOpt = Security.getUserinfo(request)
      val userInfo = userInfoOpt.get

      userOp.deleteUser(email)
      Ok(Json.obj("ok" -> true))
  }

  def updateUser(id: String) = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      val userParam = request.body.validate[User]

      userParam.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        param => {
          userOp.updateUser(param)
          Ok(Json.obj("ok" -> true))
        })
  }

  def getAllUsers = Security.Authenticated {
    val users = userOp.getAllUsers()
    implicit val userWrites = Json.writes[User]

    Ok(Json.toJson(users))
  }

  def newGroup = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      val newUserParam = request.body.validate[Group]

      newUserParam.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        param => {
          groupOp.newGroup(param)
          Ok(Json.obj("ok" -> true))
        })
  }

  def deleteGroup(id: String) = Security.Authenticated {
    implicit request =>
      val ret = groupOp.deleteGroup(id)
      Ok(Json.obj("ok" -> (ret.getDeletedCount != 0)))
  }

  def updateGroup(id: String) = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      val userParam = request.body.validate[Group]

      userParam.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        param => {
          val ret = groupOp.updateGroup(param)
          Ok(Json.obj("ok" -> (ret.getMatchedCount != 0)))
        })
  }

  def getAllGroups = Security.Authenticated {
    val groups = groupOp.getAllGroups()

    Ok(Json.toJson(groups))
  }

  def saveMonitorTypeConfig() = Security.Authenticated {
    implicit request =>
      try {
        val mtForm = Form(
          mapping(
            "id" -> text,
            "data" -> text)(EditData.apply)(EditData.unapply))

        val mtData = mtForm.bindFromRequest.get
        val mtInfo = mtData.id.split(":")
        val mt = (mtInfo(0))

        monitorTypeOp.updateMonitorType(mt, mtInfo(1), mtData.data)

        Ok(mtData.data)
      } catch {
        case ex: Throwable =>
          Logger.error(ex.getMessage, ex)
          BadRequest(ex.toString)
      }
  }

  def getInstrumentTypes = Security.Authenticated {
    implicit val w1 = Json.writes[ProtocolInfo]
    implicit val write = Json.writes[InstrumentTypeInfo]
    val iTypes =
      for (instType <- instrumentTypeOp.map.keys) yield {
        val t = instrumentTypeOp.map(instType)
        InstrumentTypeInfo(t.id, t.desp,
          t.protocol.map { p => ProtocolInfo(p, Protocol.map(p)) })
      }
    val sorted = iTypes.toList.sortWith((a, b) => a.desp < b.desp)
    Ok(Json.toJson(sorted.toList))
  }

  def getInstrumentType(id: String) = Security.Authenticated {
    implicit val w1 = Json.writes[ProtocolInfo]
    implicit val write = Json.writes[InstrumentTypeInfo]
    val iTypes = {
      val t = instrumentTypeOp.map(id)
      InstrumentTypeInfo(t.id, t.desp,
        t.protocol.map { p => ProtocolInfo(p, Protocol.map(p)) })
    }
    Ok(Json.toJson(iTypes))
  }

  def newInstrument = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      implicit val r1 = Json.reads[InstrumentStatusType]
      implicit val reads = Json.reads[Instrument]
      val instrumentResult = request.body.validate[Instrument]

      instrumentResult.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        rawInstrument => {
          try {
            val instType = instrumentTypeOp.map(rawInstrument.instType)
            val instParam = instType.driver.verifyParam(rawInstrument.param)
            val newInstrument = rawInstrument.replaceParam(instParam)
            if (newInstrument._id.isEmpty())
              throw new Exception("儀器ID不可是空的!")

            instrumentOp.upsertInstrument(newInstrument)

            //Stop measuring if any
            dataCollectManagerOp.stopCollect(newInstrument._id)
            monitorTypeOp.stopMeasuring(newInstrument._id)

            val mtList = instType.driver.getMonitorTypes(instParam)

            for (mt <- mtList) {
              monitorTypeOp.ensureMonitorType(mt)
              monitorTypeOp.addMeasuring(mt, newInstrument._id, instType.analog)
            }
            if (newInstrument.active)
              dataCollectManagerOp.startCollect(newInstrument)

            Ok(Json.obj("ok" -> true))
          } catch {
            case ex: Throwable =>
              ModelHelper.logException(ex)
              Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
          }
        })
  }

  implicit val w1 = Json.writes[InstrumentStatusType]
  implicit val w = Json.writes[Instrument]

  def getInstrumentInfoList = Security.Authenticated {
    implicit val write = Json.writes[InstrumentInfo]
    val ret = instrumentOp.getInstrumentList()

    val ret2 = ret.map { inst =>
      def getMonitorTypes: List[String] = {
        val instTypeCase = instrumentTypeOp.map(inst.instType)
        instTypeCase.driver.getMonitorTypes(inst.param)
      }

      def getStateStr = {
        if (inst.active) {
          monitorStatusOp.map(inst.state).desp
        } else
          "停用"
      }

      def getCalibrationTime = {
        val instTypeCase = instrumentTypeOp.map(inst.instType)
        instTypeCase.driver.getCalibrationTime(inst.param)
      }

      def getInfoClass = {
        val mtStr = getMonitorTypes.map {
          monitorTypeOp.map(_).desp
        }.mkString(",")
        val protocolParam =
          inst.protocol.protocol match {
            case Protocol.tcp =>
              inst.protocol.host.get
            case Protocol.serial =>
              s"COM${inst.protocol.comPort.get}"
          }
        val calibrationTime = getCalibrationTime.map { t => t.toString("HH:mm") }

        val state = getStateStr

        InstrumentInfo(inst._id, instrumentTypeOp.map(inst.instType).desp, state,
          Protocol.map(inst.protocol.protocol), protocolParam, mtStr, calibrationTime, inst)
      }

      getInfoClass
    }
    Ok(Json.toJson(ret2))
  }

  def getInstrumentList = Security.Authenticated {
    val ret = instrumentOp.getInstrumentList()

    Ok(Json.toJson(ret))
  }

  def getDoInstrumentList = Security.Authenticated {
    val ret = instrumentOp.getInstrumentList().filter(p => instrumentTypeOp.DoInstruments.contains(p.instType))

    Ok(Json.toJson(ret))
  }

  def getInstrument(id: String) = Security.Authenticated {
    val ret = instrumentOp.getInstrument(id)
    if (ret.isEmpty)
      BadRequest(s"No such instrument: $id")
    else {
      val inst = ret(0)
      Ok(Json.toJson(inst))
    }
  }

  def removeInstrument(instruments: String) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.foreach {
        dataCollectManagerOp.stopCollect(_)
      }
      ids.foreach {
        monitorTypeOp.stopMeasuring
      }
      ids.map {
        instrumentOp.delete
      }
    } catch {
      case ex: Exception =>
        Logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def deactivateInstrument(instruments: String) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.foreach {
        dataCollectManagerOp.stopCollect(_)
      }
      ids.map {
        instrumentOp.deactivate
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def activateInstrument(instruments: String) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      val f = ids.map {
        instrumentOp.activate
      }
      ids.foreach {
        dataCollectManagerOp.startCollect(_)
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def toggleMaintainInstrument(instruments: String) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.map { id =>
        instrumentOp.getInstrument(id).map { inst =>
          val newState =
            if (inst.state == MonitorStatus.MaintainStat)
              MonitorStatus.NormalStat
            else
              MonitorStatus.MaintainStat

          dataCollectManagerOp.setInstrumentState(id, newState)
        }
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def calibrateInstrument(instruments: String, zeroCalibrationStr: String) = Security.Authenticated {
    val ids = instruments.split(",")
    val zeroCalibration = zeroCalibrationStr.toBoolean
    Logger.debug(s"zeroCalibration=$zeroCalibration")

    try {
      ids.foreach { id =>
        if (zeroCalibration)
          dataCollectManagerOp.zeroCalibration(id)
        else
          dataCollectManagerOp.spanCalibration(id)
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def calibrateInstrumentFull(instruments: String) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.foreach { id =>
        dataCollectManagerOp.autoCalibration(id)
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def resetInstrument(instruments: String) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.map { id =>
        dataCollectManagerOp.setInstrumentState(id, MonitorStatus.NormalStat)
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def writeDO(instruments: String) = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      implicit val read = Json.reads[WriteDO]
      val mResult = request.body.validate[WriteDO]
      mResult.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        writeDO => {
          val ids = instruments.split(",")
          try {
            ids.map { id =>
              dataCollectManagerOp.writeTargetDO(id, writeDO.bit, writeDO.on)
            }
            Ok(Json.obj("ok" -> true))
          } catch {
            case ex: Throwable =>
              Logger.error(ex.getMessage, ex)
              Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
          }
        })
  }

  def getExecuteSeq(seq: String, on: Boolean) = Security.Authenticated {
    try {
      dataCollectManagerOp.executeSeq(seq, on)
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(ex.getMessage)
    }

    Ok(s"Execute $seq")
  }

  def monitorList = Security.Authenticated {
    implicit request =>
      val userInfo = Security.getUserinfo(request).get
      val group = groupOp.getGroupByID(userInfo.group).get

      implicit val writes = Json.writes[Monitor]

      if (userInfo.isAdmin) {
        val mList2 = monitorOp.mvList map { m => monitorOp.map(m) }
        Ok(Json.toJson(mList2))
      } else {
        val mList2 =
          for (m <- group.monitors if monitorOp.map.contains(m)) yield
            monitorOp.map(m)

        Ok(Json.toJson(mList2))
      }
  }

  def upsertMonitor(id: String) = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      implicit val read = Json.reads[Monitor]
      val mResult = request.body.validate[Monitor]
      mResult.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        m => {
          monitorOp.upsert(m)
          Ok(Json.obj("ok" -> true))
        })
  }

  def deleteMonitor(id: String) = Security.Authenticated.async {
    for (ret <- monitorOp.deleteMonitor(id)) yield
      Ok(Json.obj("ok" -> (ret.getDeletedCount != 0)))
  }

  def getActiveMonitorID = Security.Authenticated {
    Ok(Monitor.activeID)
  }

  def setActiveMonitorID(id: String) = Security.Authenticated {
    if (monitorOp.map.contains(id)) {
      Monitor.activeID = id
      Ok(Json.obj("ok" -> true))
    } else
      BadRequest("Invalid monitor ID")
  }

  def monitorTypeList = Security.Authenticated {
    implicit request =>
      val userInfo = Security.getUserinfo(request).get
      val group = groupOp.getGroupByID(userInfo.group).get

      val mtList = if (userInfo.isAdmin)
        monitorTypeOp.mtvList map monitorTypeOp.map
      else {
        group.monitorTypes map monitorTypeOp.map
        //val pm25 = monitorTypeOp.mtvList.filter(p => p == "PM25")
        //pm25 map monitorTypeOp.map
      }
      Ok(Json.toJson(mtList.sortBy(_.order)))
  }

  def upsertMonitorType(id: String) = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      Logger.info(s"upsert Mt:${id}")
      val mtResult = request.body.validate[MonitorType]

      mtResult.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        mt => {
          Logger.info(mt.toString)
          monitorTypeOp.upsertMonitorType(mt)
          Ok(Json.obj("ok" -> true))
        })
  }

  def deleteMonitorType(id: String) = Security.Authenticated {
    implicit request =>
      monitorTypeOp.deleteMonitorType(id)
      Ok("")
  }

  def signalTypeList = Security.Authenticated {
    implicit request =>
      val mtList = monitorTypeOp.signalMtvList map monitorTypeOp.map

      Ok(Json.toJson(mtList))
  }

  def signalValues = Security.Authenticated.async {
    implicit request =>
      for(ret <- dataCollectManagerOp.getLatestSignal()) yield
        Ok(Json.toJson(ret))
  }

  def setSignal(mtId:String, bit:Boolean) = Security.Authenticated{
    implicit request =>
      dataCollectManagerOp.writeSignal(mtId, bit)
      Ok("")
  }

  def recalculateHour(monitorStr: String, startNum: Long, endNum: Long) = Security.Authenticated {
    val monitors = monitorStr.split(":")
    val start = new DateTime(startNum).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0)
    val end = new DateTime(endNum).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0)

    for {
      monitor <- monitors
      hour <- query.getPeriods(start, end + 1.hour, 1.hour)} {
      dataCollectManagerOp.recalculateHourData(monitor, hour, true)(monitorTypeOp.realtimeMtvList)
    }
    Ok(Json.obj("ok" -> true))
  }

  def uploadData(tabStr: String, startStr: String, endStr: String) = Security.Authenticated {
    val tab = TableType.withName(tabStr)
    val start = DateTime.parse(startStr, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm"))
    val end = DateTime.parse(endStr, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm"))

    tab match {
      case TableType.min =>
        manager ! ForwardMinRecord(start, end)
      case TableType.hour =>
        manager ! ForwardHourRecord(start, end)
    }

    Ok(Json.obj("ok" -> true))
  }

  def testEvtOptHigh = Security.Authenticated {
    dataCollectManagerOp.evtOperationHighThreshold
    Ok("ok")
  }

  def testSpray = Security.Authenticated {
    Logger.info("testSpray")
    val ret: Seq[Instrument] = instrumentOp.getInstrumentList().filter(p => p.instType == adam6066.id)
    ret map {
      inst =>
        dataCollectManagerOp.toggleTargetDO(inst._id, 17, 10)
    }

    Ok("ok")
  }

  def getSensors = Security.Authenticated.async {
    import MqttSensor.write
    val f = sensorOp.getAllSensorList
    for (ret <- f) yield
      Ok(Json.toJson(ret))
  }

  def upsertSensor(id: String) = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      import MqttSensor.read
      val ret = request.body.validate[Sensor]
      ret.fold(err => {
        Logger.error(JsError.toJson(err).toString())
        Future {
          BadRequest(JsError.toJson(err).toString())
        }
      },
        sensor => {
          for (ret <- sensorOp.upsert(sensor)) yield {
            //insert case
            monitorOp.ensureMonitor(id)

            Ok(Json.obj("ok" -> ret.wasAcknowledged()))
          }
        })
  }

  def deleteSensor(id: String) = Security.Authenticated.async {
    for (ret <- sensorOp.delete(id)) yield
      Ok(Json.obj("ok" -> ret.getDeletedCount))
  }

  def getUser(id: String) = Security.Authenticated {
    implicit request =>
      implicit val write = Json.writes[User]
      val user = userOp.getUserByEmail(id)
      Ok(Json.toJson(user))
  }

  def probeDuoMonitorTypes(host: String) = Security.Authenticated.async {
    val url = s"http://$host/pub/GetRealTimeValuesList.asp"
    val f = WSClient.url(s"http://$host/pub/GetRealTimeValuesList.asp").get()
    f onFailure (errorHandler)

    for (ret <- f) yield {
      val values = ret.xml \ "Values"
      val spectrum = ret.xml \ "Spectrums"
      val weather = ret.xml \ "Weather"
      val instantMonitorTypes =
        for ((mtDesc, idx) <- values.text.split(";").zipWithIndex) yield
          DuoMonitorType(id = mtDesc, desc = mtDesc, configID = s"V${idx + 1}")

      val spectrumMonitorTypes =
        for ((mtDesc, idx) <- spectrum.text.split(";").zipWithIndex) yield
          DuoMonitorType(id = mtDesc, desc = mtDesc, configID = s"S${idx + 1}", isSpectrum = true)

      val weatherMonitorTypes =
        for ((mtDesc, idx) <- weather.text.split(";").zipWithIndex) yield {
          val id = mtDesc match {
            case "WindSpeed" =>
              MonitorType.WIN_SPEED
            case "WindDirection" =>
              MonitorType.WIN_DIRECTION
            case "AirTemperature" =>
              MonitorType.TEMP
            case "RelativeHumidity" =>
              MonitorType.HUMID
            case _ =>
              mtDesc
          }

          DuoMonitorType(id = id, desc = mtDesc, configID = s"W${idx + 1}")
        }

      val monitorTypes = instantMonitorTypes ++ spectrumMonitorTypes ++ weatherMonitorTypes
      implicit val writes = Json.writes[DuoMonitorType]

      Logger.info(monitorTypes.toString)
      Ok(Json.toJson(monitorTypes))
    }
  }

  def configureDuoMonitorTypes(host: String) = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      import Duo._
      val ret = request.body.validate[Seq[DuoMonitorType]]
      ret.fold(err => {
        Logger.error(JsError.toJson(err).toString())
        Future {
          BadRequest(JsError.toJson(err).toString())
        }
      },
        monitorTypes => {
          val params: Seq[String] = monitorTypes.map { t =>
            if (t.configID.startsWith("V")) s"V=${t.configID}"
            else if (t.configID.startsWith("S")) s"S=${t.configID}"
            else
              s"W=${t.configID}"
          }
          val paramStr = params.foldLeft("")((a, b) => {
            s"$a&$b"
          }).drop(1)
          val url = s"http://$host/pub/ConfigureRealTimeValues.asp?$paramStr"
          val f = WSClient.url(url).get()
          f onFailure (errorHandler)
          for (ret: WSResponse <- f) yield {
            monitorTypes.foreach(t =>
              if (t.isSpectrum) {
                Duo.ensureSpectrumTypes(t)(monitorTypeOp)
              } else {
                if (Duo.map.contains(t.id))
                  monitorTypeOp.ensureMonitorType(Duo.map(t.id))
                else
                  monitorTypeOp.ensureMonitorType(t.id)
              })

            Ok(Json.obj("ok" -> true))
          }
        })
  }

  def getDuoFixedMonitorTypes() = Security.Authenticated {
    {
      val instants = Seq("LeqAF", "LeqA", "LeqZ")
      val spectrums = Seq("LeqZ")
      val weathers = Seq(MonitorType.WIN_SPEED, MonitorType.WIN_DIRECTION, MonitorType.RAIN,
        MonitorType.PRESS, MonitorType.TEMP, MonitorType.HUMID)
      val instantMonitorTypes =
        for ((mtDesc, idx) <- instants.zipWithIndex) yield
          DuoMonitorType(id = mtDesc, desc = mtDesc, configID = s"V${idx + 1}")

      instantMonitorTypes.foreach(t =>
        if (t.isSpectrum) {
          Duo.ensureSpectrumTypes(t)(monitorTypeOp)
        } else {
          if (Duo.fixedMap.contains(t.id))
            monitorTypeOp.ensureMonitorType(Duo.fixedMap(t.id))
          else
            monitorTypeOp.ensureMonitorType(t.id)
        })

      val spectrumMonitorTypes =
        for ((id, idx) <- spectrums.zipWithIndex) yield
          DuoMonitorType(id = id, desc = s"$id 1/3 octave", configID = s"S${idx + 1}", isSpectrum = true)

      spectrumMonitorTypes.foreach(t => Duo.ensureSpectrumTypes(t)(monitorTypeOp))

      val weatherMonitorTypes =
        for ((id, idx) <- weathers.zipWithIndex) yield
          DuoMonitorType(id = id, desc = monitorTypeOp.map(id).desp, configID = s"W${idx + 1}")

      val monitorTypes = instantMonitorTypes ++ spectrumMonitorTypes ++ weatherMonitorTypes
      implicit val writes = Json.writes[DuoMonitorType]

      Logger.info(monitorTypes.toString)
      Ok(Json.toJson(monitorTypes))
    }
  }


  case class EditData(id: String, data: String)
}
