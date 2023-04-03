package models

object TableType extends Enumeration{
  val second = Value
  val hour = Value
  val min = Value
  val mapCollection = Map(second->"sec_data", hour->"hour_data", min->"min_data")

}