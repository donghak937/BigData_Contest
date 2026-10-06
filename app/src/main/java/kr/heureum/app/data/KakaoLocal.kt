package kr.heureum.app.data

import kr.heureum.app.BuildConfig
import kr.heureum.app.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Kakao Local enrichment for the automatic map. Requests are made only around public map points
 * (campus centres from OpenStreetMap), never around the participant's GPS fix.
 * Disabled when no KAKAO_REST_KEY is configured.
 */
object KakaoLocal {
    val enabled: Boolean get() = BuildConfig.KAKAO_REST_KEY.isNotBlank()
    private const val BASE = "https://dapi.kakao.com/v2/local"

    private fun get(path: String, params: Map<String, String>): JSONObject {
        val query = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val conn = URL("$BASE$path?$query").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000; conn.readTimeout = 15_000; conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "KakaoAK ${BuildConfig.KAKAO_REST_KEY}")
            val code = conn.responseCode
            check(code == 200) { "Kakao Local HTTP $code" }
            val text = conn.inputStream.use { it.readBytes() }.also { check(it.size <= 2 * 1024 * 1024) }.toString(Charsets.UTF_8)
            return JSONObject(text)
        } finally { conn.disconnect() }
    }

    private fun pois(rows: JSONArray?): List<KakaoPoi> = if (rows == null) emptyList() else (0 until rows.length()).mapNotNull { i ->
        val r = rows.getJSONObject(i)
        val lat = r.optString("y").toDoubleOrNull() ?: return@mapNotNull null
        val lon = r.optString("x").toDoubleOrNull() ?: return@mapNotNull null
        KakaoPoi(r.optString("id"), r.optString("place_name"), r.optString("category_group_code"), r.optString("category_name"), lat, lon)
    }

    /** Category search around a public point, following pages up to [maxPages]. */
    fun category(group: String, center: MapPoint, radiusM: Int, maxPages: Int): List<KakaoPoi> {
        val out = mutableListOf<KakaoPoi>()
        for (page in 1..maxPages.coerceIn(1, 45)) {
            val root = get("/search/category.json", mapOf("category_group_code" to group, "x" to "%.6f".format(java.util.Locale.US, center.longitude),
                "y" to "%.6f".format(java.util.Locale.US, center.latitude), "radius" to radiusM.coerceIn(1, 20_000).toString(), "sort" to "distance", "size" to "15", "page" to page.toString()))
            out += pois(root.optJSONArray("documents"))
            if (root.optJSONObject("meta")?.optBoolean("is_end", true) != false) break
        }
        return out
    }

    /** Keyword search (e.g. the campus name) around a public point; Kakao allows at most 45 results. */
    fun keyword(query: String, center: MapPoint, radiusM: Int): List<KakaoPoi> {
        val out = mutableListOf<KakaoPoi>()
        for (page in 1..3) {
            val root = get("/search/keyword.json", mapOf("query" to query, "x" to "%.6f".format(java.util.Locale.US, center.longitude),
                "y" to "%.6f".format(java.util.Locale.US, center.latitude), "radius" to radiusM.coerceIn(1, 20_000).toString(), "size" to "15", "page" to page.toString()))
            out += pois(root.optJSONArray("documents"))
            if (root.optJSONObject("meta")?.optBoolean("is_end", true) != false) break
        }
        return out
    }

    /**
     * Fetches enrichment for an Overpass result, stored next to the OSM data in the cache as {"pois":[...]}.
     * Queries: the campus name (building/facility names) and restaurants, cafes, convenience stores around each campus centre.
     */
    fun fetch(osm: List<MapPlace>): JSONObject {
        val campuses = osm.filter { it.kind == "campus" && it.outlines.isNotEmpty() }.sortedByDescending { it.areaM2 }.take(3)
        val pois = mutableListOf<KakaoPoi>()
        for (c in campuses) {
            val radius = (kotlin.math.sqrt(c.areaM2) * 0.8).toInt().coerceIn(300, 1500)
            pois += keyword(c.name, c.center, radius + 500)
            for (g in listOf("FD6", "CE7", "CS2")) pois += category(g, c.center, radius + 700, 5)
        }
        val rows = JSONArray()
        pois.distinctBy { it.id }.forEach { rows.put(JSONObject().put("id", it.id).put("name", it.name).put("group", it.group).put("category", it.category).put("lat", it.latitude).put("lon", it.longitude)) }
        return JSONObject().put("pois", rows).put("fetchedAt", System.currentTimeMillis())
    }

    fun parse(root: JSONObject?): List<KakaoPoi> {
        val rows = root?.optJSONArray("pois") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i -> rows.optJSONObject(i)?.let { r ->
            KakaoPoi(r.optString("id"), r.optString("name"), r.optString("group"), r.optString("category"), r.optDouble("lat"), r.optDouble("lon")).takeIf { it.latitude.isFinite() && it.longitude.isFinite() }
        } }
    }
}
