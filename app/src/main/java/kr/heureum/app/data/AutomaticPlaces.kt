package kr.heureum.app.data

import android.content.Context
import android.content.Intent
import kr.heureum.app.TrackingService
import kr.heureum.app.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.floor

/** Small pilot cache. A production deployment must use its own/licensed POI backend. */
object AutomaticPlaces {
    private val executor=Executors.newSingleThreadExecutor()
    private val lock=Any()
    @Volatile private var fetching=false
    @Volatile private var snapshot:JSONObject?=null
    @Volatile private var loaded=false
    private var parsed:List<MapPlace> = emptyList()
    private var generation=0
    private const val TTL=7*86_400_000L
    private fun file(c:Context)=File(c.filesDir,"automatic-places.json")
    private fun read(c:Context):JSONObject?=synchronized(lock) {
        if(!loaded){
            val cached=runCatching { val value=JSONObject(file(c).readText());value to OsmPlaces.parse(value.getJSONObject("data")) }.getOrNull()
            snapshot=cached?.first;parsed=cached?.second?:emptyList();loaded=true
        }
        snapshot?.let { if(System.currentTimeMillis()-it.optLong("fetchedAt") !in 0..TTL){snapshot=null;parsed=emptyList();file(c).delete();Preferences(c).placeLookupStatus="위치가 잡히면 주변 장소를 새로 찾아요."} }
        snapshot
    }
    fun places(c:Context):List<MapPlace> {
        if(!Preferences(c).automaticPlaces)return emptyList()
        val root=read(c)?:return emptyList()
        if(System.currentTimeMillis()-root.optLong("fetchedAt") !in 0..TTL)return emptyList()
        return synchronized(lock){parsed}
    }
    fun status(c:Context):String {
        if(!Preferences(c).automaticPlaces)return "자동 장소 연결이 꺼져 있어요."
        if(fetching)return "주변 학교와 건물을 찾고 있어요."
        val root=read(c)
        val items=places(c)
        return if(root!=null && System.currentTimeMillis()-root.optLong("fetchedAt") in 0..TTL)"지도 장소 ${items.size}개를 자동으로 찾았어요. ${if(items.isEmpty())"이 지역의 건물 정보가 부족해요." else "장소 이름을 입력할 필요가 없어요."}" else Preferences(c).placeLookupStatus
    }
    fun refresh(c:Context,fix:GeoSample) {
        val context=c.applicationContext;val prefs=Preferences(context);val now=System.currentTimeMillis()
        if(!prefs.automaticPlaces || now-fix.measuredAt !in 0..120_000 || fix.accuracyM !in 0f..100f || !fix.latitude.isFinite() || !fix.longitude.isFinite() || fix.latitude !in -84.0..84.0 || fix.longitude !in -179.9..179.9)return
        val root=read(context)
        if(root!=null && now-root.optLong("fetchedAt") in 0..TTL && fix.latitude in root.optDouble("south")+.002..root.optDouble("north")-.002 && fix.longitude in root.optDouble("west")+.002..root.optDouble("east")-.002)return
        // Coarse region, never an exact fix, user ID, app use, timetable, or EMA payload.
        val south=floor(fix.latitude/.02)*.02-.01;val west=floor(fix.longitude/.02)*.02-.01
        val north=south+.04;val east=west+.04
        val ticket:Int
        synchronized(lock){
            if(fetching || now-prefs.lastPlaceLookup in 0 until 6*3_600_000L)return
            fetching=true;ticket=generation;prefs.lastPlaceLookup=now
        }
        executor.execute {
            val result=runCatching {
                val bbox=listOf(south,west,north,east).joinToString(","){"%.4f".format(java.util.Locale.US,it)}
                val query="[out:json][timeout:25][maxsize:33554432];(way[building]($bbox);nwr[amenity~\"^(university|college|student_accommodation|library|restaurant|fast_food|food_court|cafe)$\"]($bbox);nwr[shop=convenience]($bbox);nwr[landuse=education]($bbox);relation[type=multipolygon][building]($bbox););out geom;"
                val conn=URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout=10_000;conn.readTimeout=40_000;conn.requestMethod="POST";conn.doOutput=true;conn.instanceFollowRedirects=false
                    conn.setRequestProperty("User-Agent","Heureum/0.4 (Android; kr.heureum.app; campus pilot)")
                    conn.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8")
                    conn.outputStream.use { it.write(("data="+URLEncoder.encode(query,"UTF-8")).toByteArray()) }
                    check(conn.responseCode==200)
                    val bytes=conn.inputStream.use { input ->
                        val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                        while(true){val n=input.read(buffer);if(n<0)break;check(output.size()+n<=6*1024*1024);output.write(buffer,0,n)}
                        output.toByteArray()
                    }
                    val data=JSONObject(String(bytes,Charsets.UTF_8));check(!data.has("remark") && data.has("elements"))
                    OsmPlaces.parse(data)
                    JSONObject().put("fetchedAt",now).put("south",south).put("west",west).put("north",north).put("east",east).put("data",data)
                }finally{conn.disconnect()}
            }
            synchronized(lock) {
                if(ticket==generation && Preferences(context).automaticPlaces) {
                    result.mapCatching { value ->
                        val decoded=OsmPlaces.parse(value.getJSONObject("data"))
                        val temp=File(context.filesDir,"automatic-places.pending");temp.writeText(value.toString());check(temp.renameTo(file(context)))
                        snapshot=value;parsed=decoded;loaded=true;prefs.placeLookupStatus="주변 지도 정보를 불러왔어요."
                    }.onFailure { prefs.placeLookupStatus="지도 정보를 연결하지 못했어요. 저장된 정보로 계속 기록하고, 6시간 뒤 다시 시도해요." }
                }
                fetching=false
            }
            context.sendBroadcast(Intent(TrackingService.ACTION_UPDATED).setPackage(context.packageName))
        }
    }
    fun clear(c:Context)=synchronized(lock){generation++;snapshot=null;parsed=emptyList();loaded=true;file(c).delete();File(c.filesDir,"automatic-places.pending").delete();Preferences(c).placeLookupStatus="위치가 잡히면 주변 장소를 찾아요. 지도 요청은 최대 6시간 간격이에요."}
}

object OsmPlaces {
    fun parse(data:JSONObject):List<MapPlace> {
        val rows=data.optJSONArray("elements")?:return emptyList()
        check(rows.length()<=12_000)
        return (0 until rows.length()).mapNotNull { index ->
            val row=rows.getJSONObject(index);val raw=row.optJSONObject("tags")?:return@mapNotNull null
            val tags=raw.keys().asSequence().associateWith { raw.getString(it) }
            val kind=MapPlaceTags.kind(tags)?:return@mapNotNull null
            val aliases=MapPlaceTags.aliases(tags)
            val name=tags["name:ko"]?:tags["name"]?:tags["name:en"]?:tags["ref"]?:MapPlaceTags.label(kind)
            fun points(a:JSONArray?):List<MapPoint> = if(a==null)emptyList() else (0 until a.length()).map { i -> val p=a.getJSONObject(i);MapPoint(p.getDouble("lat"),p.getDouble("lon")) }
            val outers=mutableListOf<List<MapPoint>>();val holes=mutableListOf<List<MapPoint>>()
            val geometry=points(row.optJSONArray("geometry"))
            if(MapPlace.closed(geometry))outers+=geometry
            if(row.optString("type")=="relation") {
                val members=row.optJSONArray("members")?:JSONArray()
                // Open fragments are not treated as a footprint; never fill an incomplete relation.
                val outerParts=mutableListOf<List<MapPoint>>();val innerParts=mutableListOf<List<MapPoint>>()
                for(i in 0 until members.length()) {
                    val m=members.getJSONObject(i);val p=points(m.optJSONArray("geometry"))
                    if(p.isNotEmpty())when(m.optString("role")){"outer"->outerParts+=p;"inner"->innerParts+=p}
                }
                fun rings(parts:List<List<MapPoint>>):List<List<MapPoint>>? {
                    val remaining=parts.toMutableList();val result=mutableListOf<List<MapPoint>>()
                    while(remaining.isNotEmpty()) {
                        var ring=remaining.removeAt(0)
                        while(ring.first()!=ring.last()) {
                            val next=remaining.indexOfFirst{it.first()==ring.last() || it.last()==ring.last()}
                            if(next<0)return null
                            var part=remaining.removeAt(next);if(part.last()==ring.last())part=part.reversed()
                            ring=ring+part.drop(1)
                        }
                        if(!MapPlace.closed(ring))return null
                        result+=ring
                    }
                    return result
                }
                val outer=rings(outerParts);val inner=rings(innerParts)
                if(outer!=null && inner!=null){outers+=outer;holes+=inner}
            }
            val center=row.optJSONObject("center")?.let { MapPoint(it.getDouble("lat"),it.getDouble("lon")) } ?: if(row.has("lat"))MapPoint(row.getDouble("lat"),row.getDouble("lon")) else
                (geometry+outers.flatten()).takeIf { it.isNotEmpty() }?.let { p->MapPoint(p.sumOf{it.latitude}/p.size,p.sumOf{it.longitude}/p.size) }?:return@mapNotNull null
            if(!center.latitude.isFinite() || !center.longitude.isFinite() || center.latitude !in -85.0..85.0 || center.longitude !in -180.0..180.0)return@mapNotNull null
            MapPlace("osm:${row.getString("type")}:${row.getLong("id")}",name,kind,center,outers,holes,aliases)
        }.distinctBy { it.id }
    }
}
