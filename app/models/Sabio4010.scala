package models
import akka.actor.{Actor, ActorSystem}
import com.github.nscala_time.time.Imports
import com.google.inject.assistedinject.Assisted
import models.Protocol.ProtocolParam

import javax.inject.Inject

object Sabio4010 extends DriverOps {
  override def id: String = "sabio4010"

  override def description: String = "Sabio 4010 Calibrator"

  override def protocol: List[Protocol.Value] = List(Protocol.serial)

  override def verifyParam(param: String): String = param

  override def getMonitorTypes(param: String): List[String] = List.empty[String]

  override def getCalibrationTime(param: String): Option[Imports.LocalTime] = None

  trait Factory {
    def apply(id: String, protocol: ProtocolParam): Actor
  }
  override def factory(id: String, protocol: Protocol.ProtocolParam, param: String)(f: AnyRef): Actor = {
    assert(f.isInstanceOf[Factory])
    val f2 = f.asInstanceOf[Factory]
    f2(id, protocol)
  }

  override def isCalibrator: Boolean = true
}
object Sabio4010Collector {

}
class Sabio4010Collector @Inject()(system: ActorSystem)(@Assisted id: String, @Assisted protocolParam: ProtocolParam) extends Actor {
  override def receive: Receive = {
    case ExecuteSeq(seq, on)=>
  }
}
