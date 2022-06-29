package models

object AkProtocol {
  case class AskRegCmd(station:String, channel:String, code:String){
    def getCmd = {
      val STX = "\u0002"
      val ETX = "\u0003"
      assert(station.length == 1)
      assert(channel.length == 2)
      assert(code.length == 3)
      s"$STX${station}AREG $channel $code$ETX".getBytes
    }
  }
  object AskRegCmd{
    def apply(station:String, channel:String, code:Int): AskRegCmd = {
      var addrStr = code.toString
      AskRegCmd(station, channel, addrStr)
    }
  }

  case class AkResponse(success:Boolean, value:String)
  def handleAkResponse(resp:String): AkResponse ={
    val parts = resp.split(" ")
    if(parts.length < 4)
      AkResponse(false, "")
    else
      AkResponse(true, parts(3).takeWhile(_ != '\u0003'))
  }
}
