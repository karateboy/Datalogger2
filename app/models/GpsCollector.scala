package models

import akka.actor._
import com.google.inject.assistedinject.Assisted
import jssc.SerialPort
import models.GpsCollector.ReadBuffer
import models.Protocol.{ProtocolParam, serial}
import net.sf.marineapi.nmea.io.AbstractDataReader
import play.api._

import java.io.{BufferedReader, InputStreamReader}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{FiniteDuration, MICROSECONDS, SECONDS}

object GpsCollector extends DriverOps {
  var count = 0

  override def getMonitorTypes(param: String) = {
    val lat = "LAT"
    val lng = "LNG"
    List(lat, lng)
  }

  override def verifyParam(json: String) = {
    json
  }

  override def getCalibrationTime(param: String) = None

  override def factory(id: String, protocol: ProtocolParam, param: String)(f: AnyRef, fOpt: Option[AnyRef]): Actor = {
    assert(f.isInstanceOf[Factory])
    val f2 = f.asInstanceOf[Factory]
    f2(id, protocol)
  }

  override def id: String = "gps"

  override def description: String = "GPS"

  override def protocol: List[String] = List(serial)

  trait Factory {
    def apply(id: String, protocolParam: ProtocolParam): Actor
  }

  case object ReadBuffer
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

class GpsCollector @Inject()()(@Assisted id: String, @Assisted protocolParam: ProtocolParam) extends Actor
  with ActorLogging with SentenceListener with ExceptionListener with PositionListener {
  Logger.info(s"$id ${protocolParam}")

  val comm: SerialComm =
    SerialComm.open(protocolParam.comPort.get, protocolParam.speed.getOrElse(SerialPort.BAUDRATE_9600))
  var reader: SentenceReader = _
  var buffer: Option[BufferedReader] = None
  var timer: Option[Cancellable] = None

  def receive = handler(MonitorStatus.NormalStat)

  init

  def handler(collectorState: String): Receive = {
    case SetState(id, state) =>
      Logger.warn(s"Ignore $self => $state")

    case ReadBuffer =>
      comm.getLine3().foreach(line => Logger.info(line.trim))
  }

  def init() {
    Logger.info("Init GPS reader...")
    reader = new SentenceReader(new SerialDataReader(comm))
    reader.setExceptionListener(this)
    val provider = new PositionProvider(reader)
    provider.addListener(this)
    reader.start()
  }

  def initDebug(): Unit = {
    buffer = Some(new BufferedReader(new InputStreamReader(comm.is)))
    timer = Some(context.system.scheduler.schedule(FiniteDuration(1, SECONDS),
      FiniteDuration(100, MICROSECONDS),
      self, ReadBuffer))
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

  def providerUpdate(evt: PositionEvent) {
    val lat = MonitorTypeData(MonitorType.LAT, evt.getPosition.getLatitude, MonitorStatus.NormalStat)
    val lng = MonitorTypeData(MonitorType.LNG, evt.getPosition.getLongitude, MonitorStatus.NormalStat)
    context.parent ! ReportData(List(lat, lng))
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
