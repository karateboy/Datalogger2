package models

object AkProtocol {
  case class AskRegCmd(station:String, channel:String, param:String){
    def getCmd = {
      val STX = "\02"
      val ETX = "\03"
      assert(station.length == 1)
      assert(channel.length == 2)
      val cmdStr = s"$STX${station}AREG $channel "
    }
  }
}
