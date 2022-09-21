package models

import akka.actor._
import com.google.inject.assistedinject.Assisted
import jssc.SerialPort
import models.GpsCollector.monitorTypes
import models.Protocol.{ProtocolParam, serial}
import net.sf.marineapi.nmea.io.AbstractDataReader
import net.sf.marineapi.nmea.util.Position
import org.joda.time.DateTime
import play.api._
import play.api.libs.json.{JsError, Json}

import java.io.BufferedReader
import scala.concurrent.duration.{FiniteDuration, MINUTES}

case class GpsParam(lat: Option[Double], lon: Option[Double], radius: Option[Double], enableAlert: Option[Boolean])

object GpsCollector extends DriverOps {
  val POS_IN_THE_RANGE = "POS_IN_THE_RANGE"
  val monitorTypes = List(MonitorType.LAT, MonitorType.LNG, MonitorType.ALTITUDE, MonitorType.SPEED)
  var count = 0

  override def getMonitorTypes(param: String) = monitorTypes


  override def verifyParam(json: String) = {
    implicit val reads = Json.reads[GpsParam]
    val ret = Json.parse(json).validate[GpsParam]
    ret.fold(err => {
      throw new IllegalArgumentException(JsError(err).toString)
    }, param =>
      json
    )
  }

  override def getCalibrationTime(param: String) = None

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt: Option[AnyRef]): Actor = {
    assert(f.isInstanceOf[Factory])
    implicit val read = Json.reads[GpsParam]
    val f2 = f.asInstanceOf[Factory]
    val gpsParam = Json.parse(param).asOpt[GpsParam].get
    f2(id, protocol, gpsParam)
  }

  override def id: String = "gps"

  override def description: String = "GPS"

  override def protocol: List[String] = List(serial)

  trait Factory {
    def apply(id: String, protocolParam: ProtocolParam, gpsParam: GpsParam): Actor
  }

  case object OpenCom
}

import net.sf.marineapi.nmea.event.{SentenceEvent, SentenceListener}
import net.sf.marineapi.nmea.io.{ExceptionListener, SentenceReader}
import net.sf.marineapi.provider.PositionProvider
import net.sf.marineapi.provider.event.{PositionEvent, PositionListener}

import javax.inject._

class SerialDataReader(serialComm: SerialComm) extends AbstractDataReader {

  import collection.mutable._

  val lineBuffer = ListBuffer.empty[String]

  override def read(): String = {
    for (line <- serialComm.getLine3())
      lineBuffer.append(line.trim)

    if (lineBuffer.isEmpty)
      null
    else
      lineBuffer.remove(0)
  }
}

class GpsCollector @Inject()(monitorTypeDB: MonitorTypeDB)(@Assisted id: String, @Assisted protocolParam: ProtocolParam,
                                                           @Assisted gpsParam: GpsParam) extends Actor
  with ActorLogging with SentenceListener with ExceptionListener with PositionListener {
  Logger.info(s"$id $protocolParam")
  import GpsCollector._
  val mtPOS_IN_THE_RANGE = monitorTypeDB.signalType(POS_IN_THE_RANGE, "位置在範圍內")
  monitorTypeDB.ensure(mtPOS_IN_THE_RANGE)

  var comm: SerialComm = _
  var reader: SentenceReader = _
  var buffer: Option[BufferedReader] = None
  var timer: Option[Cancellable] = None
  @volatile var lastPositionOpt: Option[Position] = None

  @volatile var lastTimeOpt: Option[Long] = None

  def receive = handler(MonitorStatus.NormalStat)

  self ! OpenCom

  def handler(collectorState: String): Receive = {
    case OpenCom =>
      try{
        comm = SerialComm.open(protocolParam.comPort.get,
          protocolParam.speed.getOrElse(SerialPort.BAUDRATE_9600))
        init()
      } catch {
        case _ :Throwable =>
          import context.dispatcher
          Logger.error(s"failed to open ${protocolParam.comPort.get}")
          context.system.scheduler.scheduleOnce(FiniteDuration(1, MINUTES), self, OpenCom)
      }
    case SetState(id, state) =>
      Logger.warn(s"Ignore $self => $state")
  }

  def init() {
    Logger.info("Init GPS reader...")
    reader = new SentenceReader(new SerialDataReader(comm))
    reader.setExceptionListener(this)
    val provider = new PositionProvider(reader)
    provider.addListener(this)
    reader.start()
  }

  override def postStop(): Unit = {
    if (reader != null) {
      reader.stop()
    }

    if (comm != null)
      SerialComm.close(comm)

    for (tm <- timer)
      tm.cancel()

    for (buff <- buffer)
      buff.close()
  }

  @volatile var posInTheRangeValue : Option[Boolean] = None
  def providerUpdate(evt: PositionEvent) {
    val latValue = MonitorTypeData(MonitorType.LAT, evt.getPosition.getLatitude, MonitorStatus.NormalStat)
    val lngValue = MonitorTypeData(MonitorType.LNG, evt.getPosition.getLongitude, MonitorStatus.NormalStat)
    val altValue = MonitorTypeData(MonitorType.ALTITUDE, evt.getPosition.getAltitude, MonitorStatus.NormalStat)
    val now = DateTime.now().getMillis
    val speedValue = for (lastTime <- lastTimeOpt; distance <- getDistance(evt.getPosition)) yield
      distance / (now - lastTime)

    //Update time and pos
    lastTimeOpt = Some(now)
    lastPositionOpt = Some(evt.getPosition)

    checkWithinRange(evt.getPosition)

    val fullList = if (speedValue.nonEmpty) {
      val speed = MonitorTypeData(MonitorType.SPEED, speedValue.get, MonitorStatus.NormalStat)
      List(latValue, lngValue, altValue, speed)
    } else
      List(latValue, lngValue, altValue)

    context.parent ! ReportData(fullList)
  }

  private def getDistance(pos: Position): Option[Double] = {
    for (lastPos <- lastPositionOpt) yield
      lastPos.distanceTo(pos)
  }

  def checkWithinRange(pos: Position): Unit = {
    for (lat <- gpsParam.lat; lon <- gpsParam.lon; radius <- gpsParam.radius; enable <- gpsParam.enableAlert if enable) {
      val center = new Position(lat, lon)
      val distance = center.distanceTo(pos)

      if (distance <= radius) {
        if(posInTheRangeValue.isEmpty || posInTheRangeValue.get == false) {
          posInTheRangeValue = Some(true)
          context.parent ! WriteSignal(POS_IN_THE_RANGE, true)
        }
      }else{
        if(posInTheRangeValue.isEmpty || posInTheRangeValue.get == true) {
          posInTheRangeValue = Some(false)
          context.parent ! WriteSignal(POS_IN_THE_RANGE, false)
        }
      }
    }
  }

  private def getFixedDistance(pos: Position, lastPos:Position): Double = {
    lastPos.distanceTo(pos)
  }

  def onException(ex: Exception) {
    Logger.warn(ex.getMessage)
  }

  /*
	 * (non-Javadoc)
	 * @see net.sf.marineapi.nmea.event.SentenceListener#readingPaused()
	 */
  def readingPaused() {
    Logger.error("-- Paused --");
  }

  /*
	 * (non-Javadoc)
	 * @see net.sf.marineapi.nmea.event.SentenceListener#readingStarted()
	 */
  def readingStarted() {
    Logger.info("GPS -- Started");
  }

  /*
	 * (non-Javadoc)
	 * @see net.sf.marineapi.nmea.event.SentenceListener#readingStopped()
	 */
  def readingStopped() {
    Logger.info("GPS -- Stopped");
  }

  /*
	 * (non-Javadoc)
	 * @see
	 * net.sf.marineapi.nmea.event.SentenceListener#sentenceRead(net.sf.marineapi
	 * .nmea.event.SentenceEvent)
	 */
  def sentenceRead(event: SentenceEvent) {
    // here we receive each sentence read from the port
    Logger.info(event.getSentence().toString());
  }

}
