package models.mongodb

import play.api._

import javax.inject._

@Singleton
class MongoDB @Inject() (config: Configuration){
  import org.mongodb.scala._

  private val url = config.getString("my.mongodb.url")
  private val dbName = config.getString("my.mongodb.db")
  
  val mongoClient: MongoClient = MongoClient(url.get)
  val database: MongoDatabase = mongoClient.getDatabase(dbName.get)
  val below44 = config.getBoolean("my.mongodb.below44").getOrElse(false)
  Logger.info("mongodb ready")
  def cleanup={
    mongoClient.close()
  }
}