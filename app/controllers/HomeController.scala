package controllers

import akka.actor.ActorRef
import buildinfo.BuildInfo
import com.github.nscala_time.time.Imports._
import models.DataCollectManager.{RemoveMultiCalibrationTimer, SetupMultiCalibrationTimer, StartMultiCalibration, StopMultiCalibration}
import models.ForwardManager.{ForwardHourRecord, ForwardMinRecord}
import models.ModelHelper.{errorHandler, handleJsonValidateError, handleJsonValidateErrorFuture}
import models._
import play.api._
import play.api.libs.json._
import play.api.libs.ws.{WSClient, WSResponse}
import play.api.mvc._

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class HomeController @Inject()(
                                userOp: UserDB, instrumentOp: InstrumentDB, dataCollectManagerOp: DataCollectManagerOp,
                                monitorTypeOp: MonitorTypeDB, query: Query, monitorOp: MonitorDB, groupOp: GroupDB,
                                instrumentTypeOp: InstrumentTypeOp, monitorStatusOp: MonitorStatusDB,
                                sensorOp: MqttSensorDB, WSClient: WSClient,
                                emailTargetOp: EmailTargetDB,
                                sysConfig: SysConfigDB,
                                recordDB: RecordDB,
                                calibrationConfigDB: CalibrationConfigDB,
                                lineNotify: LineNotify,
                                @Named("dataCollectManager") manager: ActorRef) extends Controller {

  val title = "資料擷取器"

  implicit val userParamRead: Reads[User] = Json.reads[User]

  val logger: Logger = Logger(this.getClass)

  import groupOp.{read, write}
  import monitorTypeOp.{mtRead, mtWrite}

  def newUser: Action[JsValue] = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      val newUserParam = request.body.validate[User]

      newUserParam.fold(
        error => handleJsonValidateError(error),
        param => {
          userOp.newUser(param)
          Ok(Json.obj("ok" -> true))
        })
  }

  def deleteUser(email: String): Action[AnyContent] = Security.Authenticated {
    implicit request =>
      val userInfoOpt = Security.getUserinfo(request)
      val userInfo = userInfoOpt.get

      userOp.deleteUser(email)
      Ok(Json.obj("ok" -> true))
  }

  def updateUser(id: String): Action[JsValue] = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      val userParam = request.body.validate[User]

      userParam.fold(
        error => handleJsonValidateError(error),
        param => {
          userOp.updateUser(param)
          Ok(Json.obj("ok" -> true))
        })
  }

  def getAllUsers: Action[AnyContent] = Security.Authenticated {
    val users = userOp.getAllUsers()
    implicit val userWrites: OWrites[User] = Json.writes[User]

    Ok(Json.toJson(users))
  }

  def newGroup: Action[JsValue] = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      val newUserParam = request.body.validate[Group]

      newUserParam.fold(
        error => handleJsonValidateError(error),
        param => {
          groupOp.newGroup(param)
          Ok(Json.obj("ok" -> true))
        })
  }

  def deleteGroup(id: String): Action[AnyContent] = Security.Authenticated {
    val ret = groupOp.deleteGroup(id)
    Ok(Json.obj("ok" -> (ret.getDeletedCount != 0)))
  }

  def updateGroup(id: String): Action[JsValue] = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      val userParam = request.body.validate[Group]

      userParam.fold(
        error => handleJsonValidateError(error),
        param => {
          val ret = groupOp.updateGroup(param)
          Ok(Json.obj("ok" -> (ret.getMatchedCount != 0)))
        })
  }

  def getAllGroups: Action[AnyContent] = Security.Authenticated {
    val groups = groupOp.getAllGroups()

    Ok(Json.toJson(groups))
  }

  def getInstrumentTypes: Action[AnyContent] = Security.Authenticated {
    implicit val w1: OWrites[ProtocolInfo] = Json.writes[ProtocolInfo]
    implicit val write: OWrites[InstrumentTypeInfo] = Json.writes[InstrumentTypeInfo]
    val iTypes =
      for (instType <- instrumentTypeOp.map.keys) yield {
        val t = instrumentTypeOp.map(instType)
        InstrumentTypeInfo(t.id, t.desp,
          t.protocol.map { p => ProtocolInfo(p, Protocol.map(p)) })
      }
    val sorted = iTypes.toList.sortWith((a, b) => a.desp < b.desp)
    Ok(Json.toJson(sorted))
  }

  def getInstrumentType(id: String): Action[AnyContent] = Security.Authenticated {
    implicit val w1: OWrites[ProtocolInfo] = Json.writes[ProtocolInfo]
    implicit val write: OWrites[InstrumentTypeInfo] = Json.writes[InstrumentTypeInfo]
    val iTypes = {
      val t = instrumentTypeOp.map(id)
      InstrumentTypeInfo(t.id, t.desp,
        t.protocol.map { p => ProtocolInfo(p, Protocol.map(p)) })
    }
    Ok(Json.toJson(iTypes))
  }

  def newInstrument: Action[JsValue] = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      implicit val r1: Reads[InstrumentStatusType] = Json.reads[InstrumentStatusType]
      implicit val reads: Reads[Instrument] = Json.reads[Instrument]
      val instrumentResult = request.body.validate[Instrument]

      instrumentResult.fold(
        error => handleJsonValidateErrorFuture(error),
        rawInstrument => {
          try {
            val instType = instrumentTypeOp.map(rawInstrument.instType)
            val instParam = instType.driver.verifyParam(rawInstrument.param)
            val newInstrument = rawInstrument.replaceParam(instParam)
            if (newInstrument._id.isEmpty)
              throw new Exception("儀器ID不可是空的!")

            //Stop measuring if any
            dataCollectManagerOp.stopCollect(newInstrument._id)
            val f = monitorTypeOp.stopMeasuring(newInstrument._id)
            val f2 = f.map(_ => instrumentOp.upsertInstrument(newInstrument))
            val mtList = instType.driver.getMonitorTypes(instParam)
            val f3 = f2.map {
              _ =>
                Future.sequence {
                  for (mt <- mtList) yield
                    monitorTypeOp.addMeasuring(mt, newInstrument._id, instType.analog, recordDB)
                }
            }
            f3.map {
              _ =>
                if (newInstrument.active)
                  dataCollectManagerOp.startCollect(newInstrument)

                Ok(Json.obj("ok" -> true))
            }
          } catch {
            case ex: Throwable =>
              ModelHelper.logException(ex)
              Future.successful(Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage)))
          }
        })
  }

  implicit val w1: OWrites[InstrumentStatusType] = Json.writes[InstrumentStatusType]
  implicit val w: OWrites[Instrument] = Json.writes[Instrument]

  def getInstrumentInfoList: Action[AnyContent] = Security.Authenticated {
    implicit val write: OWrites[InstrumentInfo] = Json.writes[InstrumentInfo]
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

      def getCalibrationTime: Option[LocalTime] = {
        val instTypeCase = instrumentTypeOp.map(inst.instType)
        instTypeCase.driver.getCalibrationTime(inst.param)
      }

      def getInfoClass: InstrumentInfo = {
        val mtStr = getMonitorTypes.map { mt =>
          if (monitorTypeOp.map.contains(mt))
            monitorTypeOp.map(mt).desp
          else
            mt
        }.mkString(",")
        val protocolParam =
          inst.protocol.protocol match {
            case Protocol.tcp =>
              inst.protocol.host.get
            case Protocol.serial =>
              s"COM${inst.protocol.comPort.get}"
            case Protocol.tcpCli =>
              inst.protocol.host.getOrElse("")
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

  def getInstrumentList: Action[AnyContent] = Security.Authenticated {
    val ret = instrumentOp.getInstrumentList()

    Ok(Json.toJson(ret))
  }

  def getDoInstrumentList: Action[AnyContent] = Security.Authenticated {
    val ret = instrumentOp.getInstrumentList().filter(p => instrumentTypeOp.DoInstruments.contains(p.instType))

    Ok(Json.toJson(ret))
  }

  def getInstrument(id: String): Action[AnyContent] = Security.Authenticated {
    val ret = instrumentOp.getInstrument(id)
    if (ret.isEmpty)
      BadRequest(s"No such instrument: $id")
    else {
      val inst = ret.head
      Ok(Json.toJson(inst))
    }
  }

  def removeInstrument(instruments: String): Action[AnyContent] = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.foreach {
        dataCollectManagerOp.stopCollect
      }
      ids.foreach {
        monitorTypeOp.stopMeasuring
      }
      ids.map {
        instrumentOp.delete
      }
    } catch {
      case ex: Exception =>
        logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def deactivateInstrument(instruments: String): Action[AnyContent] = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.foreach {
        dataCollectManagerOp.stopCollect
      }
      ids.map {
        instrumentOp.deactivate
      }
    } catch {
      case ex: Throwable =>
        logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def activateInstrument(instruments: String): Action[AnyContent] = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      val f = ids.map {
        instrumentOp.activate
      }
      ids.foreach {
        dataCollectManagerOp.startCollect
      }
    } catch {
      case ex: Throwable =>
        logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def toggleMaintainInstrument(instruments: String): Action[AnyContent] = Security.Authenticated {
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
        logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def calibrateInstrument(instruments: String, zeroCalibrationStr: String): Action[AnyContent] = Security.Authenticated {
    val ids = instruments.split(",")
    val zeroCalibration = zeroCalibrationStr.toBoolean
    logger.debug(s"zeroCalibration=$zeroCalibration")

    try {
      ids.foreach { id =>
        if (zeroCalibration)
          dataCollectManagerOp.zeroCalibration(id)
        else
          dataCollectManagerOp.spanCalibration(id)
      }
    } catch {
      case ex: Throwable =>
        logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def calibrateInstrumentFull(instruments: String): Action[AnyContent] = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.foreach { id =>
        dataCollectManagerOp.autoCalibration(id)
      }
    } catch {
      case ex: Throwable =>
        logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def resetInstrument(instruments: String): Action[AnyContent] = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.foreach { id =>
        dataCollectManagerOp.setInstrumentState(id, MonitorStatus.NormalStat)
      }
    } catch {
      case ex: Throwable =>
        logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  import DataCollectManager.WriteDO
  def writeDO(instruments: String): Action[JsValue] = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      implicit val read: Reads[WriteDO] = Json.reads[WriteDO]
      val mResult = request.body.validate[WriteDO]
      mResult.fold(
        error => handleJsonValidateError(error),
        writeDO => {
          val ids = instruments.split(",")
          try {
            ids.foreach { id =>
              dataCollectManagerOp.writeTargetDO(id, writeDO.bit, writeDO.on)
            }
            Ok(Json.obj("ok" -> true))
          } catch {
            case ex: Throwable =>
              logger.error(ex.getMessage, ex)
              Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
          }
        })
  }

  def getExecuteSeq(seq: String, on: Boolean): Action[AnyContent] = Security.Authenticated {
    try {
      dataCollectManagerOp.executeSeq(seq, on)
    } catch {
      case ex: Throwable =>
        logger.error(ex.getMessage, ex)
        Ok(ex.getMessage)
    }

    Ok(s"Execute $seq")
  }

  def monitorList: Action[AnyContent] = Security.Authenticated {
    implicit request =>
      val userInfo = Security.getUserinfo(request).get
      val group = groupOp.getGroupByID(userInfo.group).get

      implicit val writes: OWrites[Monitor] = Json.writes[Monitor]

      val mList =
        if (userInfo.isAdmin)
          monitorOp.mvList map { m => monitorOp.map(m) }
        else
          for (m <- group.monitors if monitorOp.map.contains(m)) yield
            monitorOp.map(m)

      // Make active monitor first
      val (active, rest) = mList.partition { m => m._id == Monitor.activeId }

      Ok(Json.toJson(active ++ rest))
  }

  def upsertMonitor(id: String): Action[JsValue] = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      implicit val read: Reads[Monitor] = Json.reads[Monitor]
      val mResult = request.body.validate[Monitor]
      mResult.fold(
        error => handleJsonValidateError(error),
        m => {
          monitorOp.upsertMonitor(m)
          Ok(Json.obj("ok" -> true))
        })
  }

  def deleteMonitor(id: String): Action[AnyContent] = Security.Authenticated.async {
    for (ret <- monitorOp.delete(id, sysConfig)) yield
      Ok(Json.obj("ok" -> (ret.getDeletedCount != 0)))
  }

  def getActiveMonitorID: Action[AnyContent] = Security.Authenticated {
    Ok(Monitor.activeId)
  }

  def setActiveMonitorID(id: String): Action[AnyContent] = Security.Authenticated {
    if (monitorOp.map.contains(id)) {
      Monitor.activeId = id
      sysConfig.setActiveMonitorId(id)
      Ok(Json.obj("ok" -> true))
    } else
      BadRequest("Invalid monitor ID")
  }

  def monitorTypeList: Action[AnyContent] = Security.Authenticated {
    implicit request =>
      val userInfo = Security.getUserinfo(request).get
      val group = groupOp.getGroupByID(userInfo.group).get

      val mtList = if (userInfo.isAdmin)
        monitorTypeOp.mtvList map monitorTypeOp.map
      else
        monitorTypeOp.mtvList.filter(group.monitorTypes.contains) map monitorTypeOp.map

      // populate group
      val groupedMtList = mtList.map { mt =>
        if(GcReader.vocMonitorTypes.contains(mt._id))
          mt.copy(group = Some("Voc"))
        else if(GcReader.vocAuditMonitorTypes.contains(mt._id))
          mt.copy(group = Some("VocAudit"))
        else
          mt.copy(group = Some("Others"))
      }

      Ok(Json.toJson(groupedMtList.sortBy(_.order)))
  }

  def activatedMonitorTypes: Action[AnyContent] = Security.Authenticated {
    implicit request =>
      val userInfo = Security.getUserinfo(request).get
      val group = groupOp.getGroupByID(userInfo.group).get

      val mtList = if (userInfo.isAdmin)
        monitorTypeOp.activeMtvList map monitorTypeOp.map
      else
        monitorTypeOp.activeMtvList.filter(group.monitorTypes.contains) map monitorTypeOp.map

      Ok(Json.toJson(mtList.sortBy(_.order)))
  }

  def upsertMonitorType: Action[JsValue] = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      val mtResult = request.body.validate[MonitorType]

      mtResult.fold(
        error => handleJsonValidateErrorFuture(error),
        mt => {
          logger.info(s"upsertMt ${mt.toString}")
          for (_ <- monitorTypeOp.upsertMonitorType(mt)) yield
            Ok(Json.obj("ok" -> true))
        })
  }

  def deleteMonitorType(id: String): Action[AnyContent] = Security.Authenticated {
    monitorTypeOp.deleteMonitorType(id)
    Ok("")
  }

  def signalTypeList: Action[AnyContent] = Security.Authenticated {
    val mtList = monitorTypeOp.signalMtvList map monitorTypeOp.map
    Ok(Json.toJson(mtList))
  }

  def signalValues: Action[AnyContent] = Security.Authenticated.async {
    for (ret <- dataCollectManagerOp.getLatestSignal) yield
      Ok(Json.toJson(ret))
  }

  def setSignal(mtId: String, bit: Boolean): Action[AnyContent] = Security.Authenticated {
    implicit request =>
      dataCollectManagerOp.writeSignal(mtId, bit)
      Ok("")
  }

  def recalculateHour(monitorStr: String, startNum: Long, endNum: Long): Action[AnyContent] = Security.Authenticated {
    val monitors = monitorStr.split(":")
    val start = new DateTime(startNum).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0)
    val end = new DateTime(endNum).withMinuteOfHour(23).withSecondOfMinute(59).withMillisOfSecond(0)

    logger.info(s"Recalculate Hour from $start to $end")

    for {
      monitor <- monitors
      hour <- query.getPeriods(start, end, 1.hour)} {
      dataCollectManagerOp.recalculateHourData(monitor, hour)(monitorTypeOp.activeMtvList, monitorTypeOp)
    }

    Ok(Json.obj("ok" -> true))
  }

  def uploadData(startNum: Long, endNum: Long): Action[AnyContent] = Security.Authenticated {
    val start = new DateTime(startNum)
    val end = new DateTime(endNum)

    manager ! ForwardMinRecord(start, end)
    manager ! ForwardHourRecord(start, end)

    Ok(Json.obj("ok" -> true))
  }

  def getSensors: Action[AnyContent] = Security.Authenticated.async {
    import MqttSensor.write
    val f = sensorOp.getAllSensorList
    for (ret <- f) yield
      Ok(Json.toJson(ret))
  }

  def upsertSensor(id: String): Action[JsValue] = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      import MqttSensor.read
      val ret = request.body.validate[Sensor]
      ret.fold(
        error => handleJsonValidateErrorFuture(error),
        sensor => {
          for (ret <- sensorOp.upsert(sensor)) yield {
            //insert case
            monitorOp.ensure(id)

            Ok(Json.obj("ok" -> ret.wasAcknowledged()))
          }
        })
  }

  def deleteSensor(id: String): Action[AnyContent] = Security.Authenticated.async {
    for (ret <- sensorOp.delete(id)) yield
      Ok(Json.obj("ok" -> ret.getDeletedCount))
  }

  def getUser(id: String): Action[AnyContent] = Security.Authenticated {
    implicit val write: OWrites[User] = Json.writes[User]
    val user = userOp.getUserByEmail(id)
    Ok(Json.toJson(user))
  }

  def probeDuoMonitorTypes(host: String): Action[AnyContent] = Security.Authenticated.async {
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
      implicit val writes: OWrites[DuoMonitorType] = Json.writes[DuoMonitorType]

      logger.info(monitorTypes.mkString("Array(", ", ", ")"))
      Ok(Json.toJson(monitorTypes))
    }
  }

  def configureDuoMonitorTypes(host: String): Action[JsValue] = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      import Duo._
      val ret = request.body.validate[Seq[DuoMonitorType]]
      ret.fold(err => {
        logger.error(JsError.toJson(err).toString())
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
          for (_: WSResponse <- f) yield {
            monitorTypes.foreach(t =>
              if (t.isSpectrum) {
                Duo.ensureSpectrumTypes(t)(monitorTypeOp)
              } else {
                if (Duo.map.contains(t.id))
                  monitorTypeOp.ensure(Duo.map(t.id))
                else
                  monitorTypeOp.ensure(t.id)
              })

            Ok(Json.obj("ok" -> true))
          }
        })
  }

  def getDuoFixedMonitorTypes: Action[AnyContent] = Security.Authenticated {
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
            monitorTypeOp.ensure(Duo.fixedMap(t.id))
          else
            monitorTypeOp.ensure(t.id)
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

      logger.info(monitorTypes.toString)
      Ok(Json.toJson(monitorTypes))
    }
  }

  def getAlertEmailTargets: Action[AnyContent] = Security.Authenticated.async({
    import EmailTarget._
    val f = emailTargetOp.getList()
    f onFailure errorHandler
    for (ret <- f) yield
      Ok(Json.toJson((ret)))
  })

  def saveAlertEmailTargets(): Action[JsValue] = Security.Authenticated.async(BodyParsers.parse.json)({
    implicit request =>
      import EmailTarget._
      val ret = request.body.validate[Seq[EmailTarget]]
      ret.fold(
        error => {
          logger.error(JsError.toJson(error).toString())
          Future {
            BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
          }
        },
        emails => {
          for (_ <- emailTargetOp.deleteAll) yield {
            emailTargetOp.upsertMany(emails)
            Ok(Json.obj("ok" -> true))
          }
        })
  })

  def getEffectiveRatio: Action[AnyContent] = Security.Authenticated.async({
    val f = sysConfig.getEffectiveRatio
    f onFailure errorHandler
    for (ret <- f) yield
      Ok(Json.toJson(ret))
  })

  def saveEffectiveRatio(): Action[JsValue] = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      implicit val reads = Json.reads[EditData]
      val ret = request.body.validate[EditData]

      ret.fold(
        error => handleJsonValidateError(error),
        param => {
          val ratio = param.value.toDouble
          if (ratio < 1 && ratio > 0) {
            sysConfig.setEffectiveRation(ratio)
            DataCollectManager.effectiveRatio = ratio
            Ok(Json.obj("ok" -> true))
          } else
            BadRequest("Invalid effective ratio")
        })
  }

  def resetReaders(): Action[AnyContent] = Security.Authenticated {
    dataCollectManagerOp.resetReaders()
    Ok(Json.obj("ok" -> true))
  }


  def saveLineToken(): Action[JsValue] = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      implicit val reads: Reads[EditData] = Json.reads[EditData]
      val ret = request.body.validate[EditData]

      ret.fold(
        error => handleJsonValidateError(error),
        param => {
          val token = param.value
          sysConfig.setLineToken(token)
          Ok(Json.obj("ok" -> true))
        })
  }

  def getLineToken: Action[AnyContent] = Security.Authenticated.async {
    val f = sysConfig.getLineToken
    f onFailure errorHandler
    for (ret <- f) yield
      Ok(Json.toJson(ret))
  }

  def verifyLineToken(token: String): Action[AnyContent] = Security.Authenticated.async {
    val f = lineNotify.notify(token, "測試訊息")
    f onFailure errorHandler
    for (ret <- f) yield
      Ok(Json.obj("ok" -> ret))
  }

  case class EditData(id: String, value: String)

  def splitTable(): Action[JsValue] = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      implicit val reads: Reads[EditData] = Json.reads[EditData]
      val ret = request.body.validate[EditData]

      ret.fold(
        error => {
          logger.error(JsError.toJson(error).toString())
          Future.successful(BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString())))
        },
        param => {
          val year = param.value.toInt
          val f1 = recordDB.moveRecordToYearTable(recordDB.MinCollection)(year)
          val f2 = recordDB.moveRecordToYearTable(recordDB.HourCollection)(year)
          for {
            _ <- f1
            _ <- f2
          } yield {
            Ok(Json.obj("ok" -> true))
          }
        })
  }

  def version: Action[AnyContent] = Security.Authenticated {
    Ok(Json.obj("version" -> BuildInfo.version, "scalaVersion" -> BuildInfo.scalaVersion, "sbtVersion" -> BuildInfo.sbtVersion))
  }

  import calibrationConfigDB._
  def getCalibrationConfig: Action[AnyContent] = Security.Authenticated.async {
    val f = calibrationConfigDB.getListFuture
    f onFailure errorHandler
    for (ret <- f) yield
      Ok(Json.toJson(ret))
  }

  def upsertCalibrationConfig: Action[JsValue] = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      val ret = request.body.validate[CalibrationConfig]

      ret.fold(
        error => handleJsonValidateErrorFuture(error),
        config => {
          manager ! RemoveMultiCalibrationTimer(config._id)
          for (_ <- calibrationConfigDB.upsertFuture(config)) yield {
            manager ! SetupMultiCalibrationTimer(config)
            Ok(Json.obj("ok" -> true))
          }
        })
  }

  def deleteCalibrationConfig(id: String): Action[AnyContent] = Security.Authenticated.async {
    manager ! RemoveMultiCalibrationTimer(id)
    for (ret <- calibrationConfigDB.deleteFuture(id)) yield
      Ok(Json.obj("ok" -> ret))
  }

  def executeCalibration(id:String): Action[AnyContent] = Security.Authenticated.async {
    for(calibrationConfigs <- calibrationConfigDB.getListFuture) yield {
      val configOpt = calibrationConfigs.find(_._id == id)
      if(configOpt.isDefined){
        manager ! StartMultiCalibration(configOpt.get)
        Ok(Json.obj("ok" -> true))
      }else
        BadRequest("No such calibration config")
    }
  }

  def cancelCalibration(id:String): Action[AnyContent] = Security.Authenticated.async {
    for(calibrationConfigs <- calibrationConfigDB.getListFuture) yield {
      val configOpt = calibrationConfigs.find(_._id == id)
      if(configOpt.isDefined){
        manager ! StopMultiCalibration(configOpt.get)
        Ok(Json.obj("ok" -> true))
      }else
        BadRequest("No such calibration config")
    }
  }
}
