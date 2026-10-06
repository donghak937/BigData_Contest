package kr.heureum.app.core

import java.time.LocalDate

/** One continuous stretch of a day on the 24-hour wheel, in minutes since local midnight. */
data class DayArc(val startMin: Double, val endMin: Double, val activity: String, val confirmed: Boolean, val place: String?) {
    val minutes: Double get() = endMin - startMin
}

/** Turns stored 5-minute segments into 24-hour wheel arcs (participant answers win over the model). */
object DayWheel {
    /** Fixed display order; colour slots follow this order and never change with the data. */
    val order = listOf("수업", "식사", "이동", "공부", "휴식", "업무", "수면", "기타")
    const val UNKNOWN = "미확인"
    private const val BRIDGE_MIN = 3.0

    fun label(activity: String): String = when {
        activity == InferenceEngine.UNKNOWN -> UNKNOWN
        activity in order -> activity
        else -> "기타"
    }

    fun arcs(day: LocalDate, segments: List<Segment>): List<DayArc> {
        val dayStart = day.atStartOfDay(STUDY_ZONE).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(STUDY_ZONE).toInstant().toEpochMilli()
        val pieces = segments.mapNotNull { s ->
            val from = maxOf(s.observedFrom, s.start, dayStart); val until = minOf(s.end, dayEnd)
            if (until <= from) null
            else DayArc((from - dayStart) / 60_000.0, (until - dayStart) / 60_000.0, label(s.reportedActivity ?: s.activity), s.verification != "estimated", s.place)
        }.sortedBy { it.startMin }
        val merged = mutableListOf<DayArc>()
        for (p in pieces) {
            val last = merged.lastOrNull()
            // Captures land once a minute, so consecutive segments leave tiny gaps; bridge them, but never invent long stretches.
            if (last != null && last.activity == p.activity && p.startMin - last.endMin <= BRIDGE_MIN)
                merged[merged.lastIndex] = last.copy(endMin = maxOf(last.endMin, p.endMin), confirmed = last.confirmed || p.confirmed)
            else if (last != null && p.startMin < last.endMin) {
                if (p.endMin > last.endMin) merged += p.copy(startMin = last.endMin)
            } else merged += p
        }
        return merged.filter { it.minutes > 0.0 }
    }

    /** Minutes per activity, largest first; unrecorded time is reported separately by the caller. */
    fun totals(arcs: List<DayArc>): List<Pair<String, Int>> =
        arcs.groupBy { it.activity }.mapValues { (_, a) -> a.sumOf { it.minutes }.toInt() }.toList().filter { it.second > 0 }.sortedByDescending { it.second }

    fun duration(minutes: Int): String = when {
        minutes >= 60 && minutes % 60 == 0 -> "${minutes / 60}시간"
        minutes >= 60 -> "${minutes / 60}시간 ${minutes % 60}분"
        else -> "${minutes}분"
    }
}
