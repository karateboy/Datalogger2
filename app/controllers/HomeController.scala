package controllers

import akka.actor.ActorSystem
import com.github.nscala_time.time.Imports._
import models.ModelHelper.errorHandler
import models._
import play.api._
import play.api.libs.Files
import play.api.libs.json._
import play.api.libs.mailer.{Email, MailerClient}
import play.api.mvc._

import java.nio
import java.nio.file.Files
import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}
import scala.language.postfixOps

class HomeController @Inject()(environment: play.api.Environment,
                               userOp: UserOp, instrumentOp: InstrumentOp, dataCollectManagerOp: DataCollectManagerOp,
                               monitorTypeOp: MonitorTypeOp, query: Query, monitorOp: MonitorOp, groupOp: GroupOp,
                               instrumentTypeOp: InstrumentTypeOp, monitorStatusOp: MonitorStatusOp,
                               recordOp: RecordOp, actorSystem: ActorSystem,
                               sensorOp: MqttSensorOp, errorReportOp: ErrorReportOp,
                               mailerClient: MailerClient,
                               every8d: Every8d,
                               sysConfig: SysConfig,
                               lineNotify: LineNotify) extends Controller {

  val title = "資料擷取器"

  val epaReportPath: String = environment.rootPath + "/importEPA/"

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

  def getMyGroup: Action[AnyContent] = Security.Authenticated {
    implicit request=>
    val userInfo = Security.getUserinfo(request).get
    val group = groupOp.getGroupByID(userInfo.group).get
    Ok(Json.toJson(group))
  }

  def getAllGroups: Action[AnyContent] = Security.Authenticated {
    val groups = groupOp.getAllGroups

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
    val sorted = iTypes.toList.sortWith((a, b) => a.id < b.id)
    Ok(Json.toJson(sorted))
  }

  def getInstrumentType(id: String): Action[AnyContent] = Security.Authenticated {
    implicit val w1 = Json.writes[ProtocolInfo]
    implicit val write = Json.writes[InstrumentTypeInfo]
    val iTypes = {
      val t = instrumentTypeOp.map(id)
      InstrumentTypeInfo(t.id, t.desp,
        t.protocol.map { p => ProtocolInfo(p, Protocol.map(p)) })
    }
    Ok(Json.toJson(iTypes))
  }

  def newInstrument: Action[JsValue] = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      implicit val r1 = Json.reads[InstrumentStatusType]
      implicit val reads = Json.reads[Instrument]
      val instrumentResult = request.body.validate[Instrument]

      instrumentResult.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        instrument => {
          try {
            val instType = instrumentTypeOp.map(instrument.instType)
            val instParam = instType.driver.verifyParam(instrument.param)
            instrument.param = instParam
            if (instrument._id.isEmpty())
              throw new Exception("儀器ID不可是空的!")

            instrumentOp.upsertInstrument(instrument)

            //Stop measuring if any
            dataCollectManagerOp.stopCollect(instrument._id)
            monitorTypeOp.stopMeasuring(instrument._id)

            val mtList = instType.driver.getMonitorTypes(instParam)
            for (mt <- mtList) {
              monitorTypeOp.addMeasuring(mt, instrument._id, instType.analog)
            }
            if (instrument.active)
              dataCollectManagerOp.startCollect(instrument)

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

  def getInstrumentInfoList: Action[AnyContent] = Security.Authenticated {
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
            case Protocol.Tcp() =>
              inst.protocol.host.get
            case Protocol.Serial() =>
              s"COM${inst.protocol.comPort.get}"
          }
        val calibrationTime = getCalibrationTime.map { t => t.toString("HH:mm") }

        val state = getStateStr

        InstrumentInfo(inst._id, instrumentTypeOp.map(inst.instType).desp, state,
          Protocol.map(inst.protocol.protocol), protocolParam, mtStr, calibrationTime, inst, inst.group)
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
    val ret = instrumentOp.getInstrumentList().filter(p => InstrumentType.DoInstruments.contains(p.instType))

    Ok(Json.toJson(ret))
  }

  def getInstrument(id: String): Action[AnyContent] = Security.Authenticated {
    val ret = instrumentOp.getInstrument(id)
    if (ret.isEmpty)
      BadRequest(s"No such instrument: $id")
    else {
      val inst = ret(0)
      Ok(Json.toJson(inst))
    }
  }

  def removeInstrument(instruments: String): Action[AnyContent] = Security.Authenticated {
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

  def deactivateInstrument(instruments: String): Action[AnyContent] = Security.Authenticated {
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

  def activateInstrument(instruments: String): Action[AnyContent] = Security.Authenticated {
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

  def calibrateInstrument(instruments: String, zeroCalibrationStr: String): Action[AnyContent] = Security.Authenticated {
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

  def calibrateInstrumentFull(instruments: String): Action[AnyContent] = Security.Authenticated {
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

  def resetInstrument(instruments: String): Action[AnyContent] = Security.Authenticated {
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

  def writeDO(instruments: String): Action[JsValue] = Security.Authenticated(BodyParsers.parse.json) {
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

  def getExecuteSeq(instruments: String, seq: Int): Action[AnyContent] = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.map { id =>
        dataCollectManagerOp.executeSeq(seq)
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(ex.getMessage)
    }

    Ok(s"Execute $instruments $seq")
  }

  def executeSeq(instruments: String, seq: Int): Action[AnyContent] = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.map { id =>
        dataCollectManagerOp.executeSeq(seq)
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.getMessage, ex)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def monitorList: Action[AnyContent] = Security.Authenticated {
    implicit request =>
      val userInfo = Security.getUserinfo(request).get
      val group = groupOp.getGroupByID(userInfo.group).get

      implicit val writes = Json.writes[Monitor]

      if (userInfo.isAdmin) {
        val mList2 = monitorOp.mvList map { m => monitorOp.map(m) } filter { m => m._id != "" }
        Ok(Json.toJson(mList2))
      } else {
        val mList2 =
          for (m <- group.monitors if monitorOp.map.contains(m)) yield
            monitorOp.map(m)

        Ok(Json.toJson(mList2))
      }
  }

  def upsertMonitor(id: String): Action[JsValue] = Security.Authenticated(BodyParsers.parse.json) {
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

  def deleteMonitor(id: String): Action[AnyContent] = Security.Authenticated.async {
    for (ret <- monitorOp.deleteMonitor(id)) yield
      Ok(Json.obj("ok" -> (ret.getDeletedCount != 0)))
  }

  def monitorTypeList: Action[AnyContent] = Security.Authenticated.async {
    implicit request =>
      val userInfo = Security.getUserinfo(request).get
      val group = groupOp.getGroupByID(userInfo.group).get

      val mtListFuture = if (userInfo.isAdmin)
        Future {
          monitorTypeOp.mtvList map monitorTypeOp.map
        }
      else {
        for (groupMap <- monitorTypeOp.getGroupMapAsync(group._id)) yield
          group.monitorTypes map groupMap
      }

      for (mtList <- mtListFuture) yield
        Ok(Json.toJson(mtList))
  }

  def upsertMonitorType(id: String): Action[JsValue] = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      Logger.info(s"upsert Mt:${id}")
      val userInfo = Security.getUserinfo(request).get
      val group = groupOp.getGroupByID(userInfo.group).get

      val mtResult = request.body.validate[MonitorType]

      mtResult.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        mt => {
          if (!mt._id.contains(group._id)) {
            val mtID = mt._id
            mt._id = s"${mtID}_${group._id}"
          }

          mt.group = Some(group._id)
          Logger.info(mt.toString)
          monitorTypeOp.upsertMonitorType(mt)
          Ok(Json.obj("ok" -> true))
        })
  }

  def signalTypeList: Action[AnyContent] = Security.Authenticated {
    implicit request =>
      val mtList = monitorTypeOp.signalMtvList map monitorTypeOp.map

      Ok(Json.toJson(mtList))
  }

  def signalValues: Action[AnyContent] = Security.Authenticated {
    implicit request =>
      val userInfo = request.user
      Ok(Json.toJson(monitorTypeOp.getSignalValueMap(userInfo.group)))
  }

  def getSignalInstrumentList: Action[AnyContent] = Security.Authenticated.async {
    implicit request =>
      val userInfo = request.user
      val f = instrumentOp.getGroupDoInstrumentList(userInfo.group)
      for (ret <- f) yield {
        implicit val write = Json.writes[InstrumentInfo]

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
                case Protocol.Tcp() =>
                  inst.protocol.host.get
                case Protocol.Serial() =>
                  s"COM${inst.protocol.comPort.get}"
              }
            val calibrationTime = getCalibrationTime.map { t => t.toString("HH:mm") }

            val state = getStateStr

            InstrumentInfo(inst._id, instrumentTypeOp.map(inst.instType).desp, state,
              Protocol.map(inst.protocol.protocol), protocolParam, mtStr, calibrationTime, inst, inst.group)
          }

          getInfoClass
        }
        Ok(Json.toJson(ret2))
      }
  }

  def recalculateHour(monitorStr: String, startNum: Long, endNum: Long): Action[AnyContent] = Security.Authenticated {
    val monitors = monitorStr.split(":")
    val start = new DateTime(startNum).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0)
    val end = new DateTime(endNum).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0)

    for {
      monitor <- monitors
      mCase = monitorOp.map(monitor)
      hour <- query.getPeriods(start, end + 1.hour, 1.hour)} {
      dataCollectManagerOp.recalculateHourData(monitor, hour, forward = false, alwaysValid = true)(mCase.monitorTypes)
    }
    Ok(Json.obj("ok" -> true))
  }

  def testSpray: Action[AnyContent] = Security.Authenticated {
    implicit request =>
      val userInfo = request.user
      val groupID = userInfo.group
      for (groupDoInstruments <- instrumentOp.getGroupDoInstrumentList(groupID)) {
        groupDoInstruments.foreach(inst =>
          dataCollectManagerOp.sprayAction(inst._id, 5))
      }
      Ok("ok")
  }

  def importData(fileTypeStr: String): Action[MultipartFormData[play.api.libs.Files.TemporaryFile]] = Security.Authenticated(parse.multipartFormData) {
    implicit request =>
      val dataFileOpt = request.body.file("data")
      if (dataFileOpt.isEmpty) {
        Logger.info("data is empty..")
        Ok(Json.obj("ok" -> true))
      } else {
        val dataFile = dataFileOpt.get
        val (fileType, filePath) = fileTypeStr match {
          case "sensor" =>
            (DataImporter.SensorData, nio.file.Files.createTempFile("sensor", ".csv"))
          case "sensorRaw" =>
            (DataImporter.SensorRawData, nio.file.Files.createTempFile("sensorRaw", ".csv"))

          case "updateSensorRaw" =>
            (DataImporter.UpdateSensorData, nio.file.Files.createTempFile("sensorRaw", ".csv"))

          case "epa" =>
            (DataImporter.EpaData, nio.file.Files.createTempFile("epa", ".csv"))
        }

        val file = dataFile.ref.moveTo(filePath.toFile, replace = true)

        val actorName = DataImporter.start(monitorOp = monitorOp, recordOp = recordOp,
          sensorOp = sensorOp,
          dataFile = file, fileType = fileType, dataCollectManagerOp = dataCollectManagerOp)(actorSystem)
        Ok(Json.obj("actorName" -> actorName))
      }
  }

  def getUploadProgress(actorName: String): Action[AnyContent] = Security.Authenticated {
    Ok(Json.obj("finished" -> DataImporter.isFinished(actorName)))
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
      ret.fold(err => {
        Logger.error(JsError.toJson(err).toString())
        Future {
          BadRequest(JsError.toJson(err).toString())
        }
      },
        sensor => {
          for (ret <- sensorOp.upsert(sensor)) yield {
            //insert case
            monitorOp.ensureMonitor(id, Seq(MonitorType.PM25, MonitorType.PM10))

            Ok(Json.obj("ok" -> ret.wasAcknowledged()))
          }
        })
  }

  def deleteSensor(id: String): Action[AnyContent] = Security.Authenticated.async {
    for (ret <- sensorOp.delete(id)) yield
      Ok(Json.obj("ok" -> ret.getDeletedCount))
  }

  def testAlertEmail(id: String): Action[AnyContent] = Security.Authenticated {
    Logger.info(s"testAlertEmail $id")
    val userOpt = userOp.getUserByEmail(id)
    for {
      user <- userOpt
      groupID <- user.group
      email <- user.alertEmail
    } yield {
      val subject = "測試信件"
      val msg = "測試信件"
      if (user.alertEmail.nonEmpty) {
        if (user.alertEmail.nonEmpty) {
          Future {
            blocking {
              val content = s"${user.name} 您好:\n$msg"
              for (alertEmail <- user.alertEmail) {
                val mail = Email(
                  subject = subject,
                  from = "AirIot <airiot@wecc.com.tw>",
                  to = alertEmail.split(","),
                  bodyHtml = Some(content)
                )
                try {
                  Thread.currentThread().setContextClassLoader(getClass.getClassLoader)
                  mailerClient.send(mail)
                } catch {
                  case ex: Exception =>
                    Logger.error("Failed to send email", ex)
                }
              }
            }
          }
        }
        for (smsPhone <- user.smsPhone) {
          try {
            every8d.sendSMS(subject, msg, smsPhone.split(",").toList) onFailure errorHandler
          } catch {
            case ex: Exception =>
              Logger.error("Failed to send sms", ex)
          }
        }
      }

    }
    Ok(Json.obj("ok" -> true))
  }

  def testLINE(token:String): Action[AnyContent] = Security.Authenticated.async {
    Logger.info(s"testLINE $token")
    val f = lineNotify.notify(token, "測試訊息")
    for(_ <-f) yield {
      Ok(Json.obj("ok" -> true))
    }
  }

  case class EditData(id: String, data: String)

  for(lower<-sysConfig.get(sysConfig.CleanH2SOver150).map(_.asBoolean().getValue)){
    /*
    Logger.info(s"Lower H2S over 150 $lower")
    if(!lower){
      Logger.info("Lower H2S over 150")
      recordOp.lowerH2SOver150(recordOp.MinCollection)
      recordOp.lowerH2SOver150(recordOp.HourCollection)
      sysConfig.set(sysConfig.CleanH2SOver150, new BsonBoolean(true))
    }
     */
  }
}
