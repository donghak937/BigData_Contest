package kr.heureum.app.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class AutomaticPlaceTest {
    private val now=ZonedDateTime.of(2026,10,5,10,20,0,0,STUDY_ZONE).toInstant().toEpochMilli()
    private val course=Course(title="강의",weekday=1,startMinute=600,endMinute=660,room="NTH 311",validFrom="2026-09-01",validUntil="2026-12-31")
    private fun ring(lat:Double=37.0,lon:Double=127.0,size:Double=.001)=listOf(MapPoint(lat-size,lon-size),MapPoint(lat-size,lon+size),MapPoint(lat+size,lon+size),MapPoint(lat+size,lon-size),MapPoint(lat-size,lon-size))
    private fun place(kind:String="classroom",aliases:List<String> = listOf("NTH"),lat:Double=37.0)=MapPlace(kind,kind,kind,MapPoint(lat,127.0),listOf(ring(lat)),aliases=aliases)
    private fun fix(lat:Double=37.0,lon:Double=127.0,accuracy:Float=5f,time:Long=now)=GeoSample(lat,lon,accuracy,time)
    @Test fun dormDuringClassIsOnlyLowConfidenceClass(){
        val p=InferenceEngine.predict(now,listOf(course),null,listOf(fix()),mapPlaces=listOf(place("dorm")))
        assertEquals("수업",p.activity);assertEquals("low",p.confidence);assertEquals("dorm",p.place);assertTrue(p.needsEma);assertTrue(p.mealExcluded)
    }
    @Test fun linkedFootprintAndDwellGiveClass(){
        val p=InferenceEngine.predict(now,listOf(course),null,listOf(fix(time=now-120_000),fix()),mapPlaces=listOf(place()))
        assertEquals("수업",p.activity);assertEquals("high",p.confidence);assertFalse(p.needsEma)
    }
    @Test fun circleReachingBoundaryIsUncertain(){assertEquals("boundary",place().relation(fix(lon=127.00095,accuracy=20f)))}
    @Test fun fixDriftingJustOutsideDormStillCountsAsDorm(){
        val p=InferenceEngine.predict(now,listOf(course),null,listOf(fix(lon=127.0011,accuracy=20f)),mapPlaces=listOf(place("dorm")))
        assertEquals("low",p.confidence);assertTrue(p.place.contains("부근"));assertTrue(p.mealExcluded)
    }
    @Test fun nearbyNodeNeverProvesBuildingContainment(){
        val p=place().copy(outlines=emptyList());assertEquals("label",p.relation(fix()))
        val prediction=InferenceEngine.predict(now,listOf(course),null,listOf(fix(time=now-180_000),fix()),mapPlaces=listOf(p))
        assertNotEquals("high",prediction.confidence);assertTrue(prediction.needsEma)
    }
    @Test fun unknownAbbreviationNeverCreatesRoomLink(){
        val p=InferenceEngine.predict(now,listOf(course),null,listOf(fix(time=now-180_000),fix()),mapPlaces=listOf(place(aliases=listOf("뉴턴홀"))))
        assertEquals("수업",p.activity);assertEquals("medium",p.confidence);assertTrue(p.needsEma)
    }
    @Test fun linkedBuildingWinsOverOverlappingFootprint(){
        val p=InferenceEngine.predict(now,listOf(course),null,listOf(fix()),mapPlaces=listOf(place(),place("library")))
        assertEquals("수업",p.activity);assertEquals("medium",p.confidence)
    }
    @Test fun courtyardHoleIsOutside(){
        val p=place().copy(holes=listOf(ring(size=.0003)))
        assertEquals("outside",p.relation(fix()));assertEquals("inside",p.relation(fix(lat=37.0006)))
    }
    @Test fun staleFixCannotUseBuilding(){
        val p=InferenceEngine.predict(now,listOf(course),null,listOf(fix(time=now-600_001)),mapPlaces=listOf(place()))
        assertEquals("low",p.confidence)
    }
    @Test fun libraryWithoutTimetableIsStudy(){
        val p=InferenceEngine.predict(now,emptyList(),null,listOf(fix()),mapPlaces=listOf(place("library")))
        assertEquals("공부",p.activity);assertEquals("library",p.place);assertEquals("medium",p.confidence)
    }
    @Test fun mapsProvideAliasesButDoNotInventThem(){
        assertEquals("dorm",MapPlaceTags.kind(mapOf("name" to "학생생활관","building" to "yes")))
        assertEquals("classroom",MapPlaceTags.kind(mapOf("building" to "university")))
        assertEquals(listOf("뉴턴홀","NTH"),MapPlaceTags.aliases(mapOf("name" to "뉴턴홀","ref" to "NTH")))
        assertFalse(place().matchesRoom("NTHETA 311"));assertTrue(place().matchesRoom("NTH213"))
    }
    @Test fun invalidOpenAndDegenerateRingsRejected(){assertFalse(MapPlace.closed(ring().dropLast(1)));assertFalse(MapPlace.closed(List(4){MapPoint(37.0,127.0)}))}
    @Test fun numericApartmentNameCannotMatchRoomNumber(){assertFalse(place("building",listOf("207")).matchesRoom("207"))}
    @Test fun optionalAliasCorrectionStillWorksWithAutomaticMap(){
        val zone=PlaceZone("override","뉴턴홀","classroom",37.0,127.0,100f,listOf("NTH"))
        val p=InferenceEngine.predict(now,listOf(course),null,listOf(fix()),listOf(zone),listOf(place(aliases=listOf("뉴턴홀"))))
        assertEquals("수업",p.activity);assertEquals("medium",p.confidence)
        // The participant's own correction outranks the map's dorm tag, but an overlap is never "high".
        val dorm=InferenceEngine.predict(now,listOf(course),null,listOf(fix(time=now-180_000),fix()),listOf(zone),listOf(place("dorm")))
        assertEquals("수업",dorm.activity);assertEquals("medium",dorm.confidence)
    }
}
