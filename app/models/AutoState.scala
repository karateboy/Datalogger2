package models

import play.api.Configuration

case class AutoStateConfig(instID:String, state:String, period: String, time:String)
object AutoState {
  def getConfig(configuration: Configuration): Option[Seq[AutoStateConfig]] = {
    for (autoStateConfigList <- configuration.getConfigSeq("autoState")) yield {
      try{
        autoStateConfigList.map {
          config =>
            val instID = config.getString("instID").get
            val state = config.getString("state").get
            val period = config.getString("period").get
            val time = config.getString("time").get
            AutoStateConfig(instID, state, period, time)
        }
      }catch {
        case _:Throwable=>
          Seq.empty[AutoStateConfig]
      }
    }
  }

}
