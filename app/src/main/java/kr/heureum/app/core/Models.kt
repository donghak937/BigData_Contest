package kr.heureum.app.core

import java.time.LocalDate
import java.time.ZoneId

val STUDY_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
const val BUCKET_MS = 5 * 60_000L

data class Course(
    val id: Long = 0, val title: String, val weekday: Int,
    val startMinute: Int, val endMinute: Int, val room: String = "",
    val validFrom: String, val validUntil: String, val source: String = "manual"
) {
    fun validate(): String? = when {
        title.isBlank() -> "과목명을 입력해 주세요."
        weekday !in 1..7 -> "요일을 확인해 주세요."
        startMinute !in 0..1439 || endMinute !in 1..1440 || startMinute >= endMinute -> "시작·종료 시각을 확인해 주세요."
        runCatching { LocalDate.parse(validFrom) > LocalDate.parse(validUntil) }.getOrDefault(true) -> "학기 시작·종료 날짜를 확인해 주세요."
        else -> null
    }
    fun activeAt(time: Long): Boolean {
        val t = java.time.Instant.ofEpochMilli(time).atZone(STUDY_ZONE)
        val m = t.hour * 60 + t.minute
        return t.dayOfWeek.value == weekday && t.toLocalDate().toString() in validFrom..validUntil && m in startMinute until endMinute
    }
}

data class Campus(val name: String, val latitude: Double, val longitude: Double, val radiusM: Float)
data class PlaceZone(val id: String, val name: String, val kind: String, val latitude: Double, val longitude: Double, val radiusM: Float, val roomKeys: List<String> = emptyList()) {
    fun valid() = id.isNotBlank() && name.isNotBlank() && kind in listOf("dorm", "classroom") &&
        latitude.isFinite() && latitude in -85.0..85.0 && longitude.isFinite() && longitude in -180.0..180.0 && radiusM.isFinite() && radiusM in 15f..1000f &&
        (kind != "classroom" || roomKeys.any { it.isNotBlank() })
    fun matchesRoom(room: String): Boolean {
        val value=room.replace(Regex("\\s+"), "").uppercase(java.util.Locale.ROOT)
        return kind == "classroom" && roomKeys.any { key ->
            val normalized=key.replace(Regex("\\s+"), "").uppercase(java.util.Locale.ROOT)
            normalized.isNotEmpty() && value.contains(normalized)
        }
    }
}
data class GeoSample(val latitude: Double, val longitude: Double, val accuracyM: Float, val measuredAt: Long, val sessionStart:Long? = null)
data class UsageSlice(val screenMs: Long, val topPackage: String?, val available: Boolean)
data class Prediction(val activity: String, val confidence: String, val reason: String, val course: String?, val place: String, val needsEma: Boolean, val paymentId:Long? = null, val mealExcluded:Boolean = false)
data class Segment(
    val start: Long, val end: Long, val activity: String, val confidence: String, val reason: String,
    val course: String?, val place: String, val screenMs: Long, val topPackage: String?, val usageAvailable: Boolean,
    val latitude: Double?, val longitude: Double?, val accuracyM: Float?, val locationTime: Long?,
    val reportedActivity: String? = null, val verification: String = "estimated",
    val observedFrom: Long = start, val paymentId:Long? = null
)
data class Prompt(val id: Long, val segmentStart: Long, val createdAt: Long, val suggested: String, val reason: String, val kind: String, val status: String)

fun minuteText(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
fun parseMinute(text: String): Int? {
    val m = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(text.trim()) ?: return null
    val h = m.groupValues[1].toInt(); val n = m.groupValues[2].toInt()
    return if (h in 0..23 && n in 0..59 || h == 24 && n == 0) h * 60 + n else null
}
