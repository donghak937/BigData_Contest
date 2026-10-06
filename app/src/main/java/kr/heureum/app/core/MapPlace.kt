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
            tags["building"]!=null->"building"
            else->null
        }
    }
    fun aliases(tags:Map<String,String>):List<String> = listOf("name","name:ko","name:en","short_name","ref","loc_ref","alt_name").flatMap { tags[it]?.split(';')?:emptyList() }.map{it.trim()}.filter{it.isNotEmpty()}.distinct()
    fun label(kind:String)=when(kind){"dorm"->"기숙사";"campus"->"학교";"classroom"->"교육 건물";"library"->"도서관";"food"->"식음료 장소";"convenience"->"편의점";else->"건물"}
}

object AutomaticPlaceInference {
    fun predict(course:Course?,samples:List<GeoSample>,places:List<MapPlace>):Prediction? {
        val fix=samples.maxByOrNull { it.measuredAt }?:return null
        val relations=places.map { it to it.relation(fix) }
        val dorm=relations.firstOrNull { it.first.kind=="dorm" && it.second in listOf("inside","boundary") }
        if(dorm!=null)return Prediction("활동 미확인","low","지도에 기숙사로 등록된 장소예요. 수업 시간만으로 출석을 단정하지 않아요.",course?.title,location(dorm.first,dorm.second),true,mealExcluded=true)
        val buildings=relations.filter { it.first.kind!="campus" && it.second in listOf("inside","boundary") }
        // A GPS uncertainty circle reaching two buildings cannot select either one.
        if(buildings.size>1 || buildings.any { it.second=="boundary" })return Prediction("활동 미확인","low","GPS 오차가 건물 경계나 여러 건물에 걸쳐 있어요.",course?.title,"건물 경계·위치 불확실",true)
        val building=buildings.singleOrNull()?.first
        val campus=relations.firstOrNull { it.first.kind=="campus" && it.second=="inside" }?.first
        if(building==null && campus==null)return null
        val place=building?.name?:campus!!.name
        if(course==null)return Prediction("활동 미확인","low","지도에서 ${building?.let { MapPlaceTags.label(it.kind) }?:"학교"} 위치를 확인했어요. 장소만으로 활동을 단정하지 않아요.",null,place,true)
        if(building!=null && building.matchesRoom(course.room)) {
            val first=samples.filter { building.relation(it)=="inside" }.minByOrNull { it.measuredAt }
            val sustained=first!=null && fix.measuredAt-first.measuredAt>=120_000 && samples.filter { it.measuredAt>=first.measuredAt }.all { s ->
                building.relation(s)=="inside" && places.none { it.kind=="dorm" && it.relation(s) in listOf("inside","boundary") }
            }
            return Prediction("수업",if(sustained)"high" else "medium","시간표의 ${course.room}와 지도 건물 ${building.name}이 연결되고 ${if(sustained)"2분 이상 위치가 확인됐어요" else "위치를 확인 중이에요"}. 출석은 추정이에요.",course.title,place,false)
        }
        val linked=places.filter { it.matchesRoom(course.room) && it.outlines.isNotEmpty() }
        if(linked.isNotEmpty())return Prediction("활동 미확인","low","${course.title} 시간인데 지도에서 연결된 수업 건물 안으로 확인되지 않았어요.",course.title,place,true)
        return Prediction(if(building?.kind in listOf("library","food"))"활동 미확인" else "수업","low","${course.title} 시간이고 $place 부근이에요. 지도에 시간표 장소 코드가 없어 수업 건물을 연결하지 못했어요.",course.title,place,true)
    }
    private fun location(p:MapPlace,relation:String)=if(relation=="inside")p.name else "${p.name} 경계·위치 불확실"
}
