package kr.heureum.app.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

/** Scenarios for the 0.5.0 estimator: every observed window gets a best guess, uncertainty goes to EMA. */
class ActivityInferenceTest {
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, 5, h, m, 0, 0, STUDY_ZONE).toInstant().toEpochMilli() // Monday
    private val course = Course(title = "자료구조", weekday = 1, startMinute = 600, endMinute = 690, room = "NTH 311", validFrom = "2026-09-01", validUntil = "2026-12-31")
    // ~45 m x 45 m footprint: indoor GPS error (30-50 m) always reaches a wall.
    private fun ring(lat: Double, lon: Double, size: Double = .0002) = listOf(MapPoint(lat - size, lon - size), MapPoint(lat - size, lon + size), MapPoint(lat + size, lon + size), MapPoint(lat + size, lon - size), MapPoint(lat - size, lon - size))
    private val campus = MapPlace("c", "한동대학교", "campus", MapPoint(36.10, 129.39), listOf(ring(36.10, 129.39, .01)))
    private val newton = MapPlace("n", "뉴턴홀", "classroom", MapPoint(36.100, 129.390), listOf(ring(36.100, 129.390)), aliases = listOf("뉴턴홀", "NTH"))
    private val library = MapPlace("l", "중앙도서관", "library", MapPoint(36.102, 129.390), listOf(ring(36.102, 129.390)))
    private val dorm = MapPlace("d", "비전관", "dorm", MapPoint(36.104, 129.390), listOf(ring(36.104, 129.390)))
    private val cafeteria = MapPlace("f", "학생식당", "food", MapPoint(36.100, 129.394), emptyList())
    private val hall = MapPlace("h", "학생회관", "building", MapPoint(36.100, 129.394), listOf(ring(36.100, 129.394)))
    private val map = listOf(campus, newton, library, dorm, cafeteria, hall)
    private fun fix(p: MapPlace, t: Long, acc: Float = 10f, dLat: Double = 0.0) = GeoSample(p.center.latitude + dLat, p.center.longitude, acc, t, 1)
    private fun predict(now: Long, samples: List<GeoSample>, courses: List<Course> = listOf(course), device: DeviceContext = DeviceContext()) =
        InferenceEngine.predict(now, courses, null, samples, mapPlaces = map, device = device)

    @Test fun indoorGpsErrorInSmallLinkedBuildingStillConfirmsClass() {
        val now = at(10, 20)
        val p = predict(now, listOf(fix(newton, now - 240_000, 45f), fix(newton, now - 120_000, 38f), fix(newton, now, 42f)))
        assertEquals("수업", p.activity); assertEquals("high", p.confidence); assertEquals("뉴턴홀", p.place); assertFalse(p.needsEma)
    }
    @Test fun fixDriftingOutsideTheWallStillLinksTheClassroom() {
        val now = at(10, 20)
        val p = predict(now, listOf(fix(newton, now - 180_000, 35f, .0004), fix(newton, now, 35f, .0004))) // ~22 m outside the north wall
        assertEquals("수업", p.activity); assertEquals("high", p.confidence)
    }
    @Test fun walkingAcrossCampusIsMovement() {
        val now = at(15, 0)
        val p = predict(now, listOf(fix(dorm, now - 240_000), fix(library, now - 120_000), fix(newton, now)), emptyList())
        assertEquals("이동", p.activity); assertEquals("high", p.confidence); assertTrue(p.reason.contains("걸어서"))
    }
    @Test fun arrivingStopsMovement() {
        val now = at(15, 0)
        val p = predict(now, listOf(fix(dorm, now - 300_000), fix(library, now - 120_000), fix(library, now)), emptyList())
        assertEquals("공부", p.activity)
    }
    @Test fun gpsJitterIsNotMovement() {
        val now = at(15, 0)
        assertNull(InferenceEngine.movement(listOf(fix(library, now - 120_000, 40f), fix(library, now, 40f, .0005))))
    }
    @Test fun vehicleSpeedIsDetected() {
        val now = at(15, 0)
        val m = InferenceEngine.movement(listOf(GeoSample(36.10, 129.39, 10f, now - 120_000), GeoSample(36.12, 129.39, 10f, now)))!!
        assertTrue(m.vehicle); assertTrue(m.kmh in 60..70)
    }
    @Test fun lateForClassWhileWalkingIsMovement() {
        val now = at(10, 5)
        val p = predict(now, listOf(fix(dorm, now - 240_000), fix(library, now)))
        assertEquals("이동", p.activity); assertEquals("자료구조", p.course)
    }
    @Test fun libraryStayIsStudy() {
        val now = at(14, 0)
        val p = predict(now, (0..3).map { fix(library, now - it * 120_000L) }, emptyList())
        assertEquals("공부", p.activity); assertEquals("high", p.confidence)
    }
    @Test fun cafeteriaInsideGenericBuildingAtLunchIsMeal() {
        val now = at(12, 10)
        val p = predict(now, listOf(fix(hall, now - 120_000), fix(hall, now)), emptyList())
        assertEquals("식사", p.activity); assertEquals("학생식당", p.place); assertTrue(p.needsEma)
    }
    @Test fun cafeOutsideMealTimeIsNotMeal() {
        val now = at(15, 30)
        assertNotEquals("식사", predict(now, listOf(fix(hall, now)), emptyList()).activity)
    }
    @Test fun dormAtNightWithScreenOffIsConfidentSleep() {
        val now = at(1, 30)
        val p = predict(now, listOf(fix(dorm, now)), emptyList(), DeviceContext(0, 300_000, null, true))
        assertEquals("수면", p.activity); assertEquals("high", p.confidence); assertTrue(p.mealExcluded)
    }
    @Test fun usingThePhoneAtNightIsRestNotSleep() {
        val now = at(1, 30)
        assertEquals("휴식", predict(now, listOf(fix(dorm, now)), emptyList(), DeviceContext(250_000, 300_000, AppCategory.LEISURE, true)).activity)
        assertEquals("수면", predict(now, emptyList(), emptyList(), DeviceContext(0, 300_000, null, true)).activity)
    }
    @Test fun dormDaytimeUsesForegroundApp() {
        val now = at(16, 0)
        val busy = DeviceContext(250_000, 300_000, AppCategory.LEISURE, true)
        assertEquals("휴식", predict(now, listOf(fix(dorm, now)), emptyList(), busy).activity)
        assertEquals("공부", predict(now, listOf(fix(dorm, now)), emptyList(), busy.copy(appCategory = AppCategory.STUDY)).activity)
    }
    @Test fun lectureAppDuringClassIsOnlineClassAnywhere() {
        val now = at(10, 20)
        val p = predict(now, listOf(fix(dorm, now)), device = DeviceContext(280_000, 300_000, AppCategory.LECTURE, true))
        assertEquals("수업", p.activity); assertEquals("high", p.confidence)
    }
    @Test fun classroomWithoutClassIsSelfStudy() {
        val now = at(16, 0)
        val p = predict(now, listOf(fix(newton, now - 120_000), fix(newton, now)), emptyList())
        assertEquals("공부", p.activity); assertEquals("medium", p.confidence)
    }
    @Test fun nothingKnownStaysUnknown() {
        val p = predict(at(15, 0), emptyList(), emptyList())
        assertEquals("활동 미확인", p.activity); assertTrue(p.needsEma)
    }
    @Test fun noLocationDuringClassFallsBackToTimetable() {
        val p = predict(at(10, 20), emptyList())
        assertEquals("수업", p.activity); assertEquals("low", p.confidence)
    }
    @Test fun everyBelowHighEstimateIsAskedAndHighIsNot() {
        val now = at(10, 20)
        for (samples in listOf(listOf(fix(newton, now)), listOf(fix(dorm, now)), emptyList(), listOf(fix(newton, now - 180_000), fix(newton, now))))
            predict(now, samples).let { assertEquals(it.confidence != "high", it.needsEma) }
    }
    @Test fun appCategoriesFromPackageAndAndroidCategory() {
        assertEquals(AppCategory.LECTURE, AppCategory.of("us.zoom.videomeetings"))
        assertEquals(AppCategory.LEISURE, AppCategory.of("com.google.android.youtube"))
        assertEquals(AppCategory.CHAT, AppCategory.of("com.kakao.talk"))
        assertEquals(AppCategory.STUDY, AppCategory.of("notion.id"))
        assertEquals(AppCategory.LEISURE, AppCategory.of("com.example.puzzle", 0))
        assertEquals(AppCategory.STUDY, AppCategory.of("com.example.editor", 7))
        assertNull(AppCategory.of("com.example.unknown")); assertNull(AppCategory.of(null))
    }
    @Test fun residentialBuildingsAreTagged() {
        assertEquals("home", MapPlaceTags.kind(mapOf("building" to "apartments")))
        assertEquals("dorm", MapPlaceTags.kind(mapOf("building" to "dormitory")))
    }
    @Test fun paymentMealCanRefineALowConfidenceGuessButNotAConfidentOne() {
        val t = at(12, 30)
        val restaurant = MapPlace("r", "한솥도시락", "food", MapPoint(37.0, 127.0), emptyList(), aliases = listOf("한솥도시락"))
        val samples = listOf(-2, 0, 2).map { GeoSample(37.0, 127.0, 5f, t + it * 60_000L, 1) }
        val event = PaymentEvent(1, t, "toss", "한솥도시락", "food", "approval")
        val guess = Prediction("휴식", "low", "", null, "", true)
        assertEquals("식사", PaymentInference.prediction(guess, t + 120_000, listOf(event), samples, listOf(restaurant)).activity)
        val sure = guess.copy(activity = "공부", confidence = "high")
        assertEquals(sure, PaymentInference.prediction(sure, t + 120_000, listOf(event), samples, listOf(restaurant)))
    }
}
