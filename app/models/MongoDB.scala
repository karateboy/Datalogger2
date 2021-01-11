package models
import play.api._
import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class MongoDB (config: Configuration){
  import org.mongodb.scala._

  val url = config.getString("my.mongodb.url")
  val dbName = config.getString("my.mongodb.db")
  
  val mongoClient: MongoClient = MongoClient(url.get)
  val database: MongoDatabase = mongoClient.getDatabase(dbName.get);
  def init(){
    val f = database.listCollectionNames().toFuture()
    val colFuture = f.map { colNames =>
      SysConfig.init(colNames)
      //MonitorType => 
      val mtFuture = MonitorType.init(colNames)
      ModelHelper.waitReadyResult(mtFuture)
      Instrument.init(colNames)
      Record.init(colNames)
      User.init(colNames)
      Calibration.init(colNames)
      MonitorStatus.init(colNames)
      Alarm.init(colNames)
      InstrumentStatus.init(colNames)
      ManualAuditLog.init(colNames)
    }
    //Program need to wait before init complete
    import scala.concurrent.Await
    import scala.concurrent.duration._
    import scala.language.postfixOps
    
    Await.result(colFuture, 30 seconds)
  }
  
  def cleanup={
    mongoClient.close()
  }
}