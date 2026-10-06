package kr.heureum.app.core

import java.time.Instant
import kotlin.math.*

/** Phone-side signals for the current observation window (all optional). */
data class DeviceContext(val screenMs: Long = 0, val windowMs: Long = 0, val appCategory: String? = null, val usageAvailable: Boolean = false) {
    val screenRatio: Double? get() = if (usageAvailable && windowMs >= 60_000) (screenMs.toDouble() / windowMs).coerceIn(0.0, 1.0) else null
    val screenOff: Boolean get() = screenRatio?.let { it < 0.1 } == true
    val screenBusy: Boolean get() = screenRatio?.let { it >= 0.4 } == true
}

/** Coarse purpose of the foreground app, from its package name and Android's declared app category. */
object AppCategory {
    const val LECTURE = "lecture"; const val STUDY = "study"; const val LEISURE = "leisure"; const val CHAT = "chat"; const val NAVIGATION = "navigation"
    private val rules = listOf(
        LECTURE to Regex("^us\\.zoom|webex|com\\.microsoft\\.teams|apps\\.meetings|com\\.instructure|coursemos|blackboard|(^|\\.)(lms|ecampus|elearning|eclass|cyber)(\\.|$)", RegexOption.IGNORE_CASE),
        STUDY to Regex("notion|com\\.microsoft\\.office|apps\\.docs|app\\.notes|flexcil|goodnotes|noteshelf|com\\.adobe\\.reader|pdf|anki|quizlet|duolingo|papago|naverdic|dict|chatgpt|com\\.anthropic|apps\\.bard|photomath|mathpix|chegg|classting", RegexOption.IGNORE_CASE),
        NAVIGATION to Regex("nmap|android\\.map|tmap|kakao\\.taxi|kakaomobility|apps\\.maps|subway|(^|\\.)bus", RegexOption.IGNORE_CASE),
        CHAT to Regex("kakao\\.talk|everytime|discord|telegram|naver\\.line|slack|whatsapp|messag|android\\.gm$|mail", RegexOption.IGNORE_CASE),
        LEISURE to Regex("youtube|netflix|instagram|musically|ugc\\.trill|twitter|webtoon|kakao\\.page|kakaopage|tving|wavve|pooq|coupang\\.play|disney|twitch|afreeca|chzzk|spotify|melon|soundcloud|reddit|op\\.gg|game|nexon|netmarble|ncsoft|supercell|riotgames|hoyoverse|mihoyo", RegexOption.IGNORE_CASE),
    )
    /** [androidCategory] is ApplicationInfo.category (GAME=0 … PRODUCTIVITY=7), or -1 when unknown. */
    fun of(packageName: String?, androidCategory: Int = -1): String? {
        if (packageName.isNullOrBlank()) return null
        rules.firstOrNull { it.second.containsMatchIn(packageName) }?.let { return it.first }
        return when (androidCategory) { 0, 1, 2, 3, 5 -> LEISURE; 4 -> CHAT; 6 -> NAVIGATION; 7 -> STUDY; else -> null }
    }
}

data class Movement(val distanceM: Double, val speedMps: Double, val spanMs: Long) {
    val kmh: Int get() = (speedMps * 3.6).roundToInt()
    val vehicle: Boolean get() = speedMps > 7.0
}

/**
 * Rule-based activity estimator. Always returns the most plausible activity with a confidence grade
 * ("high"/"medium"/"low"); anything below "high" is offered to the participant as a confirm-style EMA.
 * Grades are rule levels, not calibrated probabilities.
 */
object InferenceEngine {
    const val CLASS = "수업"; const val STUDY = "공부"; const val MEAL = "식사"; const val MOVE = "이동"; const val REST = "휴식"; const val UNKNOWN = "활동 미확인"

    fun distanceM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val lat = Math.toRadians(bLat - aLat); val lon = Math.toRadians(bLon - aLon)
        val h = sin(lat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(lon / 2).pow(2)
        return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Where the latest fix is. [inside] = the fix centre lies in the footprint; otherwise it is within GPS error of it. */
    data class Spot(val place: MapPlace, val inside: Boolean, val wallM: Double)

    fun spot(fix: GeoSample, places: List<MapPlace>): Spot? {
        val candidates = places.filter { it.kind != "campus" }
        val containing = candidates.filter { it.containsPoint(fix) }
        if (containing.isNotEmpty()) {
            val p = containing.minBy { it.areaM2 }
            return Spot(p, true, p.wallDistanceM(fix))
        }
        // Indoor fixes often drift just outside the wall; accept the closest place within the reported error.
        val reach = min(fix.accuracyM.toDouble(), 40.0) + 5
        return candidates.map { it to it.wallDistanceM(fix) }
            .filter { (p, d) -> d <= if (p.outlines.isEmpty()) max(reach, 25.0) else reach }
            .minByOrNull { it.second }?.let { Spot(it.first, false, it.second) }
    }

    /** Displacement between an earlier fix and the latest one that clearly exceeds both error circles. */
    fun movement(samples: List<GeoSample>): Movement? {
        val usable = samples.filter { it.accuracyM <= 100f }.sortedBy { it.measuredAt }
        val last = usable.lastOrNull() ?: return null
        val window = usable.filter { last.measuredAt - it.measuredAt <= 360_000 && it.sessionStart == last.sessionStart }
        fun apart(a: GeoSample, b: GeoSample) = distanceM(a.latitude, a.longitude, b.latitude, b.longitude)
        // Already settled: the two newest fixes, 90 s+ apart, agree with each other.
        val prev = window.dropLast(1).lastOrNull()
        if (prev != null && last.measuredAt - prev.measuredAt >= 90_000 && apart(prev, last) <= max(30.0, (prev.accuracyM + last.accuracyM).toDouble())) return null
        return window.dropLast(1).mapNotNull { s ->
            val span = last.measuredAt - s.measuredAt
            val d = apart(s, last)
            if (span < 45_000 || d <= max(50.0, (s.accuracyM + last.accuracyM).toDouble())) null else Movement(d, d / (span / 1000.0), span)
        }.maxByOrNull { it.distanceM }?.takeIf { it.speedMps in 0.5..70.0 }
    }

    /** How long (ms) the newest fixes have continuously satisfied [here]. */
    private fun dwellMs(samples: List<GeoSample>, here: (GeoSample) -> Boolean): Long {
        val ordered = samples.sortedByDescending { it.measuredAt }
        val latest = ordered.firstOrNull() ?: return 0
        if (!here(latest)) return 0
        var earliest = latest
        for (s in ordered) { if (!here(s) || s.sessionStart != latest.sessionStart) break; earliest = s }
        return latest.measuredAt - earliest.measuredAt
    }

    private fun near(p: MapPlace, s: GeoSample) = p.containsPoint(s) || p.wallDistanceM(s) <= min(s.accuracyM.toDouble(), 40.0) + 5

    fun predict(
        now: Long, courses: List<Course>, campus: Campus?, samples: List<GeoSample>,
        zones: List<PlaceZone> = emptyList(), mapPlaces: List<MapPlace> = emptyList(), device: DeviceContext = DeviceContext()
    ): Prediction {
        val recent = samples.filter { now - it.measuredAt in 0..600_000 && it.accuracyM.isFinite() && it.accuracyM in 0f..100f && it.latitude.isFinite() && it.longitude.isFinite() && it.latitude in -85.0..85.0 && it.longitude in -180.0..180.0 }
        val fix = recent.maxByOrNull { it.measuredAt }
        val minute = Instant.ofEpochMilli(now).atZone(STUDY_ZONE).let { it.hour * 60 + it.minute }
        val night = minute >= 23 * 60 || minute < 7 * 60
        val mealTime = minute in 450 until 570 || minute in 660 until 840 || minute in 1020 until 1200
        val validZones = zones.filter { it.valid() }
        fun zoneDistance(z: PlaceZone, s: GeoSample) = distanceM(s.latitude, s.longitude, z.latitude, z.longitude)

        val spot = fix?.let { spot(it, mapPlaces) }
        val dormZone = fix?.let { f -> validZones.firstOrNull { it.kind == "dorm" && zoneDistance(it, f) <= it.radiusM } }
        val inDorm = spot?.place?.kind == "dorm" || dormZone != null
        val campusDistance = if (campus != null && fix != null) distanceM(fix.latitude, fix.longitude, campus.latitude, campus.longitude) else null
        val inCampusCircle = campus != null && campusDistance != null && campusDistance <= campus.radiusM
        val onCampus = fix != null && (inCampusCircle || spot?.place?.kind in listOf("classroom", "library", "dorm") ||
            mapPlaces.any { it.kind == "campus" && (it.containsPoint(fix) || it.wallDistanceM(fix) <= min(fix.accuracyM.toDouble(), 40.0)) })
        val knowsCampus = campus != null || mapPlaces.any { it.kind in listOf("campus", "classroom", "library", "dorm") }
        val offCampus = fix != null && !onCampus && knowsCampus
        val placeLabel = when {
            spot != null -> if (spot.inside) spot.place.name else "${spot.place.name} 부근"
            dormZone != null -> dormZone.name
            campus != null && fix != null -> if (inCampusCircle) campus.name else "학교 밖"
            fix == null -> "위치 미확인"
            onCampus -> mapPlaces.firstOrNull { it.kind == "campus" }?.name ?: "학교"
            else -> "지도에 없는 장소"
        }
        // A labelled food/library/shop point inside a generic building is more specific than the building.
        val poi = if (fix != null && (spot == null || spot.place.kind in listOf("building", "classroom", "home")))
            mapPlaces.filter { it.outlines.isEmpty() && it.kind in listOf("food", "library", "convenience") }
                .map { it to it.wallDistanceM(fix) }.filter { it.second <= min(max(fix.accuracyM.toDouble(), 20.0), 40.0) }.minByOrNull { it.second }?.first
        else null
        val move = movement(recent)
        val app = device.appCategory

        val scheduled = courses.filter { it.activeAt(now) }
        fun linkedPlace(c: Course) = if (fix == null) null else mapPlaces.filter { it.outlines.isNotEmpty() && it.matchesRoom(c.room) && near(it, fix) }.minByOrNull { it.wallDistanceM(fix) }
        fun linkedZone(c: Course) = if (fix == null) null else validZones.firstOrNull { it.matchesRoom(c.room) && zoneDistance(it, fix) <= it.radiusM + min(fix.accuracyM, 30f) }
        val course = scheduled.firstOrNull { linkedPlace(it) != null || linkedZone(it) != null } ?: scheduled.firstOrNull()
        val overlap = if (scheduled.size > 1) " 같은 시간에 일정이 겹쳐 ${course?.title}(으)로 골랐어요." else ""

        fun result(activity: String, confidence: String, reason: String, place: String = placeLabel, mealExcluded: Boolean = inDorm): Prediction {
            val graded = if (overlap.isNotEmpty() && confidence == "high") "medium" else confidence
            return Prediction(activity, graded, reason + overlap, course?.title, place, graded != "high", mealExcluded = mealExcluded)
        }

        if (course != null) {
            if (app == AppCategory.LECTURE)
                return result(CLASS, "high", "${course.title} 시간에 화상 강의·학습관리 앱을 쓰고 있어요. 온라인 수업으로 추정해요.")
            val linked = linkedPlace(course)
            val zone = linkedZone(course)
            if (linked != null || zone != null) {
                val name = linked?.name ?: zone!!.name
                val stay = dwellMs(recent) { s -> if (linked != null) near(linked, s) else zoneDistance(zone!!, s) <= zone.radiusM + min(s.accuracyM, 30f) }
                val sure = stay >= 120_000 && !inDorm // a footprint overlapping a dorm can't confirm attendance
                return result(CLASS, if (sure) "high" else "medium", "${course.title} 시간이고 시간표 장소와 연결된 $name${if (sure) "에 ${stay / 60_000}분 이상 머물렀어요" else "에서 위치가 확인됐어요"}.", name, false)
            }
            if (move != null)
                return result(MOVE, "medium", "${course.title} 시간이지만 ${move.distanceM.roundToInt()}m를 시속 ${move.kmh}km로 이동 중이에요.")
            if (inDorm) {
                if (app == AppCategory.LEISURE && device.screenBusy) return result(REST, "medium", "${course.title} 시간이지만 기숙사에서 여가 앱을 쓰고 있어요.")
                return result(CLASS, "low", "${course.title} 시간에 기숙사에 있어요. 온라인 수업일 수도, 결석일 수도 있어요.")
            }
            if (onCampus) {
                // The timetable room maps to a known building, but we are clearly somewhere else on campus.
                val elsewhere = mapPlaces.firstOrNull { it.outlines.isNotEmpty() && it.matchesRoom(course.room) }?.name ?: validZones.firstOrNull { it.matchesRoom(course.room) }?.name
                if (elsewhere != null)
                    return result(CLASS, "low", "${course.title} 시간인데 연결된 수업 건물($elsewhere) 밖이에요. 휴강·장소 변경일 수도 있어요.")
                when (poi?.kind ?: spot?.place?.kind) {
                    "library" -> return result(STUDY, "low", "${course.title} 시간이지만 도서관에 있어요.")
                    "food", "convenience" -> return result(MEAL, "low", "${course.title} 시간이지만 식음료 장소에 있어요.")
                }
                return result(CLASS, "medium", "${course.title} 시간이고 학교 안($placeLabel)에 있어요. 시간표 장소를 지도 건물과 연결하지 못해 강의실은 추정이에요.")
            }
            if (fix == null) return result(CLASS, "low", "최근 위치가 없어 시간표만으로 ${course.title} 수업을 추정했어요.")
            if (!offCampus) return result(CLASS, "low", "${course.title} 시간이에요. 주변 학교 지도를 아직 확인하지 못해 시간표로 추정했어요.")
            // Clearly away from school during class: fall through to free-time evidence, capped at low.
        }

        val prefix = if (course != null) "${course.title} 시간이지만 학교 밖이에요. " else ""
        fun free(activity: String, confidence: String, reason: String, place: String = placeLabel) =
            result(activity, if (course != null) "low" else confidence, prefix + reason, place)

        if (move != null) {
            val clear = move.distanceM >= 250 && move.spanMs >= 120_000
            return free(MOVE, if (clear) "high" else "medium", "최근 ${max(1, move.spanMs / 60_000)}분 동안 ${move.distanceM.roundToInt()}m를 시속 ${move.kmh}km로 ${if (move.vehicle) "차량으로" else "걸어서"} 이동했어요.")
        }
        if (app == AppCategory.LECTURE) return free(STUDY, "medium", "화상회의·학습관리 앱을 쓰고 있어요. 팀 회의나 스터디로 추정해요.")
        if (app == AppCategory.NAVIGATION && fix == null) return free(MOVE, "low", "지도·교통 앱을 쓰고 있어요.")

        val place = poi ?: spot?.place
        val here = place?.let { p -> dwellMs(recent) { near(p, it) } } ?: 0L
        val name = poi?.name ?: placeLabel
        when (place?.kind) {
            "library" -> return free(STUDY, if (here >= 300_000) "high" else "medium", "도서관($name)에 ${if (here >= 60_000) "${here / 60_000}분째 " else ""}있어요.", name)
            "food" -> return if (mealTime) free(MEAL, if (here >= 600_000) "high" else "medium", "식사 시간대에 식음료 장소($name)에 있어요.", name)
                else if (app == AppCategory.STUDY || device.screenOff) free(STUDY, "low", "식사 시간이 아닌데 카페·식당($name)에 머물러 있어요.", name)
                else free(REST, "low", "식사 시간이 아닌데 카페·식당($name)에 있어요.", name)
            "convenience" -> return free(if (mealTime) MEAL else REST, "low", "편의점($name)에 들렀어요.", name)
            "classroom" -> return if (app == AppCategory.LEISURE && device.screenBusy) free(REST, "low", "수업이 없는 강의동에서 여가 앱을 쓰고 있어요.")
                else free(STUDY, if (app == AppCategory.STUDY) "high" else "medium", "수업이 없는 시간에 강의동($name)에 있어요. 자습·과제로 추정해요.")
        }
        if (inDorm || place?.kind == "home") {
            val where = if (inDorm) "기숙사" else "주거 건물"
            if (night) return free(REST, if (device.screenOff) "high" else "medium", "밤 시간에 ${where}에 있어요. 수면·휴식으로 추정해요.")
            return when (app) {
                AppCategory.STUDY -> free(STUDY, "medium", "${where}에서 학습·문서 앱을 쓰고 있어요.")
                AppCategory.LEISURE -> free(REST, if (device.screenBusy) "high" else "medium", "${where}에서 여가 앱을 쓰고 있어요.")
                else -> free(REST, "low", "${where}에 머물러 있어요.")
            }
        }
        if (fix != null) {
            when (app) {
                AppCategory.STUDY -> return free(STUDY, "medium", "학습·문서 앱을 쓰고 있어요.")
                AppCategory.LEISURE -> return free(REST, "medium", "여가 앱을 쓰고 있어요.")
            }
            if (night) return free(REST, "medium", "밤 시간에 한곳에 머물러 있어요. 수면·휴식으로 추정해요.")
            if (onCampus) return free(STUDY, "low", "수업이 없는 시간에 학교 안($placeLabel)에 있어요.")
            return free(REST, "low", "지도에서 활동을 특정할 수 없는 장소에 머물러 있어요.")
        }
        // No usable location.
        if (night) return free(REST, if (device.screenOff) "medium" else "low", "밤 시간이고 최근 위치가 없어요.")
        return when (app) {
            AppCategory.STUDY -> free(STUDY, "low", "최근 위치는 없지만 학습·문서 앱을 쓰고 있어요.")
            AppCategory.LEISURE -> free(REST, "low", "최근 위치는 없지만 여가 앱을 쓰고 있어요.")
            AppCategory.CHAT -> free(REST, "low", "최근 위치는 없고 메신저를 쓰고 있어요.")
            else -> result(UNKNOWN, "low", "최근 위치와 앱 사용 근거가 없어요.")
        }
    }
}

data class EmaPolicy(val maxPerDay: Int = 3, val gapMinutes: Int = 90, val quietStart: Int = 21, val quietEnd: Int = 9) {
    fun canAsk(now: Long, countToday: Int, lastPromptAt: Long?, hasPending: Boolean): Boolean {
        val hour = java.time.Instant.ofEpochMilli(now).atZone(STUDY_ZONE).hour
        val quiet = if (quietStart > quietEnd) hour >= quietStart || hour < quietEnd else hour in quietStart until quietEnd
        return !quiet && !hasPending && countToday < maxPerDay && (lastPromptAt == null || now - lastPromptAt >= gapMinutes * 60_000L)
    }
}
