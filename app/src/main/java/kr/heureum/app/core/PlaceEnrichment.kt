package kr.heureum.app.core

/** A participant's on-device fix for one map building: display name and/or kind. Never sent anywhere. */
data class PlaceCorrection(val placeId: String, val name: String? = null, val kind: String? = null)

object PlaceCorrections {
    /** Kinds a participant can choose, with their labels. */
    val choices = listOf("classroom" to "강의동·연구동", "library" to "도서관", "dorm" to "기숙사", "food" to "식당·카페", "home" to "주거 건물", "building" to "기타 건물")
    fun apply(places: List<MapPlace>, corrections: Map<String, PlaceCorrection>): List<MapPlace> = places.map { p ->
        val c = corrections[p.id] ?: return@map p
        val name = c.name?.trim()?.takeIf { it.isNotEmpty() } ?: p.name
        // Keep the old name as an alias so timetable codes that matched it still do.
        p.copy(name = name, kind = c.kind?.takeIf { k -> choices.any { it.first == k } } ?: p.kind, aliases = (p.aliases + p.name + name).distinct())
    }
}

/** A place from Kakao Local (category or keyword search). */
data class KakaoPoi(val id: String, val name: String, val group: String, val category: String, val latitude: Double, val longitude: Double)

/**
 * Merges Kakao Local data into OpenStreetMap places, conservatively:
 * - a university facility POI inside a footprint adds its name as an alias (and names the building only when OSM has no name),
 * - Kakao's "기숙사"/"도서관" categories set the kind; a generic OSM building with a campus facility POI becomes "classroom",
 * - restaurants, cafes and convenience stores missing from OSM are added as label points.
 * Kakao often lists dormitories as plain "학교부속시설", so it never downgrades an OSM dormitory; participants fix that by hand.
 */
object PlaceEnrichment {
    private val facility = Regex("학교부속시설|대학교|기숙사|도서관")
    private val notBuilding = Regex("^(교통|스포츠|여행|사회,공공기관)")

    /** "한동대학교 SW타운" → "SW타운"; returns null when only the campus name is left. */
    fun shortName(name: String, campusNames: Collection<String>): String? {
        val compact = { s: String -> s.replace(" ", "") }
        var n = name.trim()
        for (c in campusNames.filter { it.length >= 2 }.sortedByDescending { it.length }) {
            if (compact(n).startsWith(compact(c))) {
                var dropped = 0; var i = 0
                while (i < n.length && dropped < compact(c).length) { if (n[i] != ' ') dropped++; i++ }
                n = n.substring(i).trim(); break
            }
        }
        return n.takeIf { it.length >= 2 && campusNames.none { c -> compact(c) == compact(it) } }
    }

    private fun groupKind(group: String) = when (group) { "FD6", "CE7" -> "food"; "CS2" -> "convenience"; else -> null }
    private fun norm(s: String) = s.replace(Regex("[\\s()·._-]"), "").lowercase(java.util.Locale.ROOT)

    fun merge(osm: List<MapPlace>, pois: List<KakaoPoi>): List<MapPlace> {
        val campusNames = osm.filter { it.kind == "campus" }.flatMap { it.aliases + it.name }.toSet()
        val facilities = pois.filter { groupKind(it.group) == null && facility.containsMatchIn(it.category) && !notBuilding.containsMatchIn(it.category) && it.group != "SC4" }
        val merged = osm.map { p ->
            if (p.kind == "campus" || p.outlines.isEmpty()) return@map p
            val hits = facilities.filter { f -> GeoSample(f.latitude, f.longitude, 0f, 0).let { s -> p.containsPoint(s) || p.wallDistanceM(s) <= 8 } }
            val name = hits.mapNotNull { shortName(it.name, campusNames) }.distinct().singleOrNull() ?: return@map p
            val categories = hits.joinToString(" ") { it.category }
            val kind = when {
                categories.contains("기숙사") -> "dorm"
                categories.contains("도서관") -> "library"
                p.kind in listOf("building", "home") -> "classroom"
                else -> p.kind
            }
            val unnamed = p.name == MapPlaceTags.label(p.kind)
            p.copy(name = if (unnamed) name else p.name, kind = kind, aliases = (p.aliases + p.name + name).distinct())
        }
        val labels = merged.filter { it.outlines.isEmpty() }
        val added = pois.mapNotNull { poi ->
            val kind = groupKind(poi.group) ?: return@mapNotNull null
            if (!poi.latitude.isFinite() || !poi.longitude.isFinite()) return@mapNotNull null
            val duplicate = labels.any { l -> InferenceEngine.distanceM(l.center.latitude, l.center.longitude, poi.latitude, poi.longitude) <= 40 &&
                norm(l.name).let { n -> n.length >= 2 && (norm(poi.name).contains(n) || n.contains(norm(poi.name))) } }
            if (duplicate) null else MapPlace("kakao:${poi.id}", poi.name, kind, MapPoint(poi.latitude, poi.longitude), emptyList(), aliases = listOf(poi.name))
        }.distinctBy { it.id }
        return merged + added
    }
}
