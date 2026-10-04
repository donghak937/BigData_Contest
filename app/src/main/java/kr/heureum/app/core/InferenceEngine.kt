package kr.heureum.app.core

import kotlin.math.*

object InferenceEngine {
    fun distanceM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val lat = Math.toRadians(bLat - aLat); val lon = Math.toRadians(bLon - aLon)
        val h = sin(lat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(lon / 2).pow(2)
        return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun predict(now: Long, courses: List<Course>, campus: Campus?, samples: List<GeoSample>, zones: List<PlaceZone> = emptyList(), mapPlaces: List<MapPlace> = emptyList()): Prediction {
        val scheduled = courses.filter { it.activeAt(now) }
        val recent = samples.filter { now - it.measuredAt in 0..600_000 && it.accuracyM.isFinite() && it.accuracyM in 0f..100f && it.latitude.isFinite() && it.longitude.isFinite() && it.latitude in -85.0..85.0 && it.longitude in -180.0..180.0 }
        val fix = recent.maxByOrNull { it.measuredAt }
        val place = if (campus != null && fix != null) {
            val d = distanceM(fix.latitude, fix.longitude, campus.latitude, campus.longitude)
            when {
                d + fix.accuracyM <= campus.radiusM -> campus.name
                d - fix.accuracyM > campus.radiusM -> "학교 밖"
                else -> "학교 경계·위치 불확실"
            }
        } else "위치 미확인"
        val validZones=zones.filter { it.valid() }
        fun distance(zone: PlaceZone, sample: GeoSample) = distanceM(sample.latitude,sample.longitude,zone.latitude,zone.longitude)
        // Exclusion areas take precedence, including uncertainty that could reach a dorm.
        if(fix != null) {
            val dorms=validZones.filter { it.kind == "dorm" && distance(it,fix)-fix.accuracyM <= it.radiusM }
            if(dorms.isNotEmpty()) {
                val inside=dorms.firstOrNull { distance(it,fix)+fix.accuracyM <= it.radiusM }
                val location=inside?.name ?: "${dorms.first().name} 경계·위치 불확실"
                return Prediction("활동 미확인","low",if(inside!=null)"기숙사에 있어요. 수업 시간이어도 출석·휴식·온라인 수업을 단정하지 않아요." else "GPS 오차가 기숙사에 걸쳐 있어요. 수업 건물과 구분할 수 없어 확인이 필요해요.",scheduled.takeIf { it.isNotEmpty() }?.joinToString { it.title },location,true)
            }
        }
        if (scheduled.size > 1) return Prediction("활동 미확인", "low", "같은 시간에 일정이 겹쳐요.", scheduled.joinToString { it.title }, place, true)
        val course = scheduled.firstOrNull()
        val automatic=AutomaticPlaceInference.predict(course,recent,mapPlaces)
        val manualLinked=course!=null && validZones.any { it.matchesRoom(course.room) }
        val automaticDorm=fix!=null && mapPlaces.any{it.kind=="dorm" && it.relation(fix) in listOf("inside","boundary")}
        if(automatic!=null && (!manualLinked || automaticDorm))return automatic
        if (course == null) return Prediction("활동 미확인", "low", "예정된 수업이 없어요. 앱 사용만으로 생활 활동을 단정하지 않아요.", null, place, true)
        val buildings=validZones.filter { it.matchesRoom(course.room) }
        if(fix!=null && buildings.isNotEmpty()) {
            val inside=buildings.firstOrNull { distance(it,fix)+fix.accuracyM <= it.radiusM }
            if(inside==null) {
                val boundary=buildings.any { distance(it,fix)-fix.accuracyM <= it.radiusM }
                return Prediction("활동 미확인","low",if(boundary)"수업 건물 경계에 GPS 오차가 걸쳐 있어요." else "${course.title} 수업 시간인데 연결한 수업 건물 밖이에요. 온라인 수업·휴강일 수도 있어요.",course.title,if(boundary)"수업 건물 경계·위치 불확실" else place,true)
            }
            val earliest=recent.filter { distance(inside,it)+it.accuracyM<=inside.radiusM }.minByOrNull { it.measuredAt }
            val sustained=earliest!=null && fix.measuredAt-earliest.measuredAt>=120_000 && recent.filter { it.measuredAt>=earliest.measuredAt }.all { sample ->
                distance(inside,sample)+sample.accuracyM<=inside.radiusM && validZones.filter { it.kind=="dorm" }.none { distance(it,sample)-sample.accuracyM<=it.radiusM }
            }
            return Prediction("수업",if(sustained)"high" else "medium","${course.title} 수업 시간이고 ${inside.name} 안에서 ${if(sustained)"2분 이상 위치가 확인됐어요" else "위치를 확인 중이에요"}. 실제 출석은 추정이에요.",course.title,inside.name,false)
        }
        if (campus == null) return Prediction("수업", "low", "시간표에 ${course.title} 수업이 있어요. 지도에서 수업 장소를 자동으로 확인 중이에요.", course.title, place, true)
        if (fix == null) return Prediction("수업", "low", "시간표 기반 추정이에요. 최근 10분 내 정확한 위치가 없어요.", course.title, place, true)
        if (place == "학교 밖") return Prediction("활동 미확인", "low", "${course.title} 수업 시간인데 학교 밖에 있어요. 온라인 수업·휴강일 수도 있어요.", course.title, place, true)
        if (place != campus.name) return Prediction("수업", "low", "시간표와 학교 경계의 위치를 함께 확인 중이에요.", course.title, place, true)
        return Prediction("수업", "medium", "${course.title} 수업 시간이고 학교 안에 있어요. 지도에서 수업 건물이 확인되지 않아 실제 활동은 확인이 필요해요.", course.title, place, true)
    }
}

data class EmaPolicy(val maxPerDay: Int = 3, val gapMinutes: Int = 90, val quietStart: Int = 21, val quietEnd: Int = 9) {
    fun canAsk(now: Long, countToday: Int, lastPromptAt: Long?, hasPending: Boolean): Boolean {
        val hour = java.time.Instant.ofEpochMilli(now).atZone(STUDY_ZONE).hour
        val quiet = if (quietStart > quietEnd) hour >= quietStart || hour < quietEnd else hour in quietStart until quietEnd
        return !quiet && !hasPending && countToday < maxPerDay && (lastPromptAt == null || now - lastPromptAt >= gapMinutes * 60_000L)
    }
}
