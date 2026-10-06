package kr.heureum.app.core

import kotlin.math.*

data class MapPoint(val latitude: Double, val longitude: Double)

/** A map label is not evidence of being inside a building. Only closed footprints qualify. */
data class MapPlace(
    val id: String, val name: String, val kind: String, val center: MapPoint,
    val outlines: List<List<MapPoint>>, val holes: List<List<MapPoint>> = emptyList(),
    val aliases: List<String> = emptyList()
) {
    fun relation(sample: GeoSample): String {
        if (outlines.isEmpty()) return "label"
        val p=MapPoint(sample.latitude,sample.longitude)
        val inside=outlines.any { contains(it,p) } && holes.none { contains(it,p) }
        val boundary=(outlines+holes).minOf { edgeDistance(it,p) }
        return if(boundary <= sample.accuracyM.toDouble()+1.0) "boundary" else if(inside) "inside" else "outside"
    }
    /** The fix's centre point lies inside a closed footprint (courtyard holes excluded). */
    fun containsPoint(sample: GeoSample): Boolean {
        if (outlines.isEmpty()) return false
        val p=MapPoint(sample.latitude,sample.longitude)
        return outlines.any { contains(it,p) } && holes.none { contains(it,p) }
    }
    /** Metres from the fix to the nearest wall, or to the label point when the map has no footprint. */
    fun wallDistanceM(sample: GeoSample): Double =
        if (outlines.isEmpty()) InferenceEngine.distanceM(sample.latitude,sample.longitude,center.latitude,center.longitude)
        else (outlines+holes).minOf { edgeDistance(it,MapPoint(sample.latitude,sample.longitude)) }
    /** Approximate footprint area, used to prefer the innermost of nested footprints. */
    val areaM2: Double by lazy {
        if (outlines.isEmpty()) 0.0 else outlines.sumOf { ring ->
            val k=111_195*cos(Math.toRadians(ring.first().latitude))
            abs(ring.zipWithNext().sumOf { (a,b) -> a.longitude*k*b.latitude*111_195 - b.longitude*k*a.latitude*111_195 }) / 2
        }
    }
    fun matchesRoom(room: String): Boolean {
        val value=normalize(room)
        return kind in listOf("classroom","building") && aliases.any { alias ->
            val key=normalize(alias)
            key.length>=2 && key.any{it.isLetter()} && value.startsWith(key) && (value.length==key.length || value[key.length].isDigit() || !key.last().isLetter())
        }
    }
    companion object {
        private fun normalize(s:String)=s.replace(Regex("[\\s\\-()]"),"").uppercase(java.util.Locale.ROOT)
        fun closed(points:List<MapPoint>)=points.size>=4 && points.first()==points.last() && points.all { it.latitude.isFinite() && it.longitude.isFinite() && it.latitude in -85.0..85.0 && it.longitude in -180.0..180.0 } &&
            points.distinct().size>=3 && abs(points.zipWithNext().sumOf { (a,b)->a.longitude*b.latitude-b.longitude*a.latitude })>1e-12
        private fun contains(ring:List<MapPoint>,p:MapPoint):Boolean {
            var inside=false
            ring.zipWithNext().forEach { (a,b) ->
                if((a.latitude>p.latitude)!=(b.latitude>p.latitude) && p.longitude<(b.longitude-a.longitude)*(p.latitude-a.latitude)/(b.latitude-a.latitude)+a.longitude)inside=!inside
            }
            return inside
        }
        private fun edgeDistance(ring:List<MapPoint>,p:MapPoint):Double {
            fun xy(v:MapPoint)=Pair((v.longitude-p.longitude)*111_195*cos(Math.toRadians(p.latitude)),(v.latitude-p.latitude)*111_195)
            return ring.zipWithNext().minOf { (a,b) ->
                val (x,y)=xy(a);val (u,v)=xy(b);val dx=u-x;val dy=v-y
                val t=if(dx*dx+dy*dy==0.0)0.0 else (-(x*dx+y*dy)/(dx*dx+dy*dy)).coerceIn(0.0,1.0)
                hypot(x+t*dx,y+t*dy)
            }
        }
    }
}

object MapPlaceTags {
    fun kind(tags:Map<String,String>):String? {
        val name=listOfNotNull(tags["name"],tags["name:ko"],tags["name:en"]).joinToString(" ").lowercase()
        return when {
            tags["building"]=="dormitory" || tags["building:use"]=="dormitory" || tags["amenity"]=="student_accommodation" || tags["residential"]=="university" || Regex("기숙사|생활관|dormitory|student residence").containsMatchIn(name)->"dorm"
            tags["amenity"] in listOf("university","college") && tags["building"]==null || tags["landuse"]=="education"->"campus"
            tags["amenity"]=="library"->"library"
            tags["shop"]=="convenience"->"convenience"
            tags["amenity"] in listOf("restaurant","fast_food","food_court","cafe")->"food"
            tags["building"] in listOf("university","college","school") || tags["building:use"]=="education"->"classroom"
            tags["building"] in listOf("apartments","house","residential","detached","semidetached_house","terrace")->"home"
            tags["building"]!=null->"building"
            else->null
        }
    }
    fun aliases(tags:Map<String,String>):List<String> = listOf("name","name:ko","name:en","short_name","ref","loc_ref","alt_name").flatMap { tags[it]?.split(';')?:emptyList() }.map{it.trim()}.filter{it.isNotEmpty()}.distinct()
    fun label(kind:String)=when(kind){"dorm"->"기숙사";"campus"->"학교";"classroom"->"교육 건물";"library"->"도서관";"food"->"식음료 장소";"convenience"->"편의점";"home"->"주거 건물";else->"건물"}
}
