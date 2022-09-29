val topic = "WECC/SAQ200/861108035980803/sensor"
val topic1 = "861108035980803/sensor"
val pattern = "WECC/SAQ200/([0-9]+)/sensor".r
val pattern1 = "WECC/SAQ200/([0-9]+)/.*".r
val pattern1(a) =  topic

val str= "\02"
val v = str.getBytes
print(v(0))
import java.time.Instant
Instant.parse("2022-01-01T00:00:00.000Z").toString