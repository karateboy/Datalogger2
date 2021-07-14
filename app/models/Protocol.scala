package models
import org.bson.codecs.configuration.{CodecProvider, CodecRegistry}
import play.api.libs.json._
import org.bson.{BsonReader, BsonWriter}
import org.bson.codecs.{Codec, DecoderContext, EncoderContext}
import org.mongodb.scala.bson.codecs.Macros



object Protocol extends Enumeration{
  case class ProtocolParam(protocol:ProtocolType, host:Option[String], comPort:Option[Int])
  implicit val pReads: Reads[Protocol.Value] = EnumUtils.enumReads(Protocol)
  implicit val pWrites: Writes[Protocol.Value] = EnumUtils.enumWrites
  implicit val ptReads: Reads[ProtocolType] = new Reads[ProtocolType] {
    def reads(json: JsValue): JsResult[ProtocolType] = json match {
      case JsString(s) => {
        try {
          s match {
            case "tcp" => JsSuccess(Tcp())
            case "serial" => JsSuccess(Serial())
          }
        } catch {
          case _: NoSuchElementException => JsError(s"unknown protocol type: '$s'")
        }
      }
      case _ => JsError("String value expected")
    }
  }

  implicit val ptWrites : Writes[ProtocolType] = new Writes[ProtocolType] {
    override def writes(o: ProtocolType): JsValue = JsString(o.protocolName)
  }
  implicit val ppReader = Json.reads[ProtocolParam]
  implicit val ppWrite = Json.writes[ProtocolParam]

  sealed trait ProtocolType {
    def protocolName: String
  }
  case class Tcp() extends ProtocolType {
    override def protocolName: String = "tcp"
  }

  case class Serial() extends ProtocolType {
    override def protocolName: String = "serial"
  }

  val tcp = Value
  val serial = Value

  // For display only
  def map: Map[ProtocolType, String] = Map(Tcp()->"TCP", Serial()->"RS232")

  val CODEC = new Codec[Value]{
    override def decode(reader: BsonReader, decoderContext: DecoderContext): Protocol.Value = {
      val value = reader.readString()
      Protocol.withName(value)
    }
    override def encode(writer: BsonWriter, value: Protocol.Value, encoderContext: EncoderContext): Unit = {
      writer.writeString(value.toString)
    }
    override def getEncoderClass: Class[Protocol.Value] = classOf[Protocol.Value]
  }

  val protocolTypeCodecProvider = Macros.createCodecProvider[ProtocolType]()

  val CODEC2 = new Codec[ProtocolType] {
    override def encode(writer: BsonWriter, value: ProtocolType, encoderContext: EncoderContext): Unit =
      writer.writeString(value.protocolName)

    override def getEncoderClass: Class[ProtocolType] = classOf[ProtocolType]

    override def decode(reader: BsonReader, decoderContext: DecoderContext): ProtocolType = {
      val v = reader.readString()
      v match {
        case "tcp" => Tcp()
        case "serial" => Serial()
      }
    }
  }

  val CODEC3 = new Codec[Tcp] {
    override def encode(writer: BsonWriter, value: Tcp, encoderContext: EncoderContext): Unit =
      writer.writeString(value.protocolName)

    override def getEncoderClass: Class[Tcp] = classOf[Tcp]

    override def decode(reader: BsonReader, decoderContext: DecoderContext): Tcp = {
      val v = reader.readString()
      v match {
        case "tcp" => Tcp()
      }
    }
  }

  val CODEC4 = new Codec[Serial] {
    override def encode(writer: BsonWriter, value: Serial, encoderContext: EncoderContext): Unit =
      writer.writeString(value.protocolName)

    override def getEncoderClass: Class[Serial] = classOf[Serial]

    override def decode(reader: BsonReader, decoderContext: DecoderContext): Serial = {
      val v = reader.readString()
      v match {
        case "serial" => Serial()
      }
    }
  }
}