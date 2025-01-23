package models.mongodb

import play.api._

import javax.inject._

@Singleton
class MongoDB @Inject() (config: Configuration){
  import org.mongodb.scala._

  private val url = config.get[String]("my.mongodb.url")
  private val dbName = config.get[String]("my.mongodb.db")
  
  private val mongoClient: MongoClient = MongoClient(url)
  val database: MongoDatabase = mongoClient.getDatabase(dbName)
  val below44: Boolean = config.getOptional[Boolean]("my.mongodb.below44").getOrElse(false)
  Logger.info("mongodb ready")
  def cleanup={
    mongoClient.close()
  }
}