package kr.heureum.app.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DayWheelTest {
    private val day = LocalDate.of(2026, 10, 7)
    private val midnight = day.atStartOfDay(STUDY_ZONE).toInstant().toEpochMilli()
    private fun seg(fromMin: Int, toMin: Int, activity: String, reported: String? = null) =
        Segment(midnight + fromMin * 60_000L, midnight + toMin * 60_000L, activity, "medium", "", null, "뉴턴홀", 0, null, false, null, null, null, null,
            reportedActivity = reported, verification = if (reported != null) "ema" else "estimated", observedFrom = midnight + fromMin * 60_000L)

    @Test fun consecutiveFiveMinuteSegmentsMergeAcrossCaptureGaps() {
        // Captures end a minute before the next bucket starts.
        val arcs = DayWheel.arcs(day, listOf(seg(600, 604, "수업"), seg(605, 609, "수업"), seg(610, 614, "수업")))
        assertEquals(1, arcs.size); assertEquals(600.0, arcs[0].startMin, 0.01); assertEquals(614.0, arcs[0].endMin, 0.01)
    }
    @Test fun longGapsStayEmptyAndActivitiesSplit() {
        val arcs = DayWheel.arcs(day, listOf(seg(0, 4, "수면"), seg(60, 64, "수면"), seg(65, 69, "이동")))
        assertEquals(listOf("수면", "수면", "이동"), arcs.map { it.activity })
    }
    @Test fun participantAnswerWinsAndUnknownIsLabelled() {
        val arcs = DayWheel.arcs(day, listOf(seg(600, 604, "휴식", reported = "공부"), seg(700, 704, "활동 미확인"), seg(800, 804, "알바")))
        assertEquals(listOf("공부", "미확인", "기타"), arcs.map { it.activity }); assertTrue(arcs[0].confirmed)
    }
    @Test fun segmentsAreClippedToTheDay() {
        val before = Segment(midnight - 120_000, midnight + 120_000, "수면", "high", "", null, "", 0, null, false, null, null, null, null, observedFrom = midnight - 120_000)
        val arc = DayWheel.arcs(day, listOf(before)).single()
        assertEquals(0.0, arc.startMin, 0.01); assertEquals(2.0, arc.endMin, 0.01)
    }
    @Test fun totalsAndDurations() {
        val totals = DayWheel.totals(DayWheel.arcs(day, listOf(seg(0, 420, "수면"), seg(600, 690, "수업"), seg(700, 730, "수업"))))
        assertEquals(listOf("수면" to 420, "수업" to 120), totals)
        assertEquals("7시간", DayWheel.duration(420)); assertEquals("1시간 30분", DayWheel.duration(90)); assertEquals("45분", DayWheel.duration(45))
    }
}
