package kr.heureum.app.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class PlaceInferenceTest {
    private val now=LocalDateTime.of(2026,10,5,10,10).atZone(STUDY_ZONE).toInstant().toEpochMilli()
    private val campus=Campus("학교",37.55,126.95,600f)
    private val dorm=PlaceZone("dorm","기숙사","dorm",37.55,126.95,80f)
    private val building=PlaceZone("class","뉴턴홀","classroom",37.553,126.95,80f,listOf("NTH"))
    private val course=Course(title="수업",weekday=1,startMinute=600,endMinute=660,room="NTH 311",validFrom="2026-09-01",validUntil="2026-12-20")
    private fun fix(zone:PlaceZone,at:Long=now,accuracy:Float=10f)=GeoSample(zone.latitude,zone.longitude,accuracy,at)
    private fun predict(samples:List<GeoSample>,zones:List<PlaceZone> = listOf(dorm,building),courses:List<Course> = listOf(course))=InferenceEngine.predict(now,courses,campus,samples,zones)
    @Test fun dormInsideCampusIsOnlyLowConfidenceClass(){
        val p=predict(listOf(fix(dorm,now-180_000),fix(dorm)))
        assertEquals("수업",p.activity);assertEquals("low",p.confidence);assertEquals("기숙사",p.place);assertTrue(p.needsEma)
    }
    @Test fun dormWithoutTimetableIsLowConfidenceRest(){
        val p=predict(listOf(fix(dorm)),courses=emptyList())
        assertEquals("휴식",p.activity);assertEquals("low",p.confidence);assertTrue(p.needsEma)
    }
    @Test fun courseSpecificBuildingStayCanBecomeHighConfidence(){
        val p=predict(listOf(fix(building,now-180_000),fix(building)))
        assertEquals("수업",p.activity);assertEquals("high",p.confidence);assertEquals("뉴턴홀",p.place);assertFalse(p.needsEma)
    }
    @Test fun singleBuildingFixIsNotHigh(){
        assertEquals("medium",predict(listOf(fix(building))).confidence)
    }
    @Test fun anotherBuildingDoesNotConfirmScheduledCourse(){
        val p=predict(listOf(fix(building)),courses=listOf(course.copy(room="ANH 313")))
        assertEquals("medium",p.confidence);assertTrue(p.needsEma)
    }
    @Test fun linkedBuildingOutsideConflictsEvenInsideCampus(){
        val p=predict(listOf(GeoSample(37.548,126.95,10f,now)))
        assertEquals("low",p.confidence);assertTrue(p.reason.contains("건물"));assertTrue(p.reason.contains("밖"));assertTrue(p.needsEma)
    }
    @Test fun overlappingDormAndClassroomNeedsConfirmation(){
        val p=predict(listOf(fix(building,now-180_000),fix(building)),listOf(building,dorm.copy(latitude=building.latitude)))
        assertEquals("수업",p.activity);assertEquals("medium",p.confidence);assertTrue(p.needsEma)
    }
    @Test fun uncertaintyTouchingDormIsNotConfidentClass(){
        val nearDorm=dorm.copy(latitude=building.latitude+.0008,radiusM=40f)
        val p=predict(listOf(fix(building,accuracy=60f)),listOf(nearDorm,building))
        assertEquals("수업",p.activity);assertNotEquals("high",p.confidence);assertTrue(p.needsEma)
    }
    @Test fun dormObservationBreaksBuildingDwell(){
        assertEquals("medium",predict(listOf(fix(building,now-240_000),fix(dorm,now-120_000),fix(building))).confidence)
    }
    @Test fun staleFixDoesNotIdentifyDorm(){
        val p=predict(listOf(fix(dorm,now-700_000)))
        assertEquals("위치 미확인",p.place);assertEquals("low",p.confidence)
    }
    @Test fun singleCoarseFixCannotProveAttendance(){
        val p=predict(listOf(fix(building,accuracy=100f)))
        assertEquals("수업",p.activity);assertEquals("medium",p.confidence);assertTrue(p.needsEma)
    }
    @Test fun compactRoomCodeAndWhitespaceMatchBuilding(){
        assertTrue(building.matchesRoom("NTH213"));assertTrue(building.matchesRoom("nth 311"))
        assertFalse(building.matchesRoom("ANH 313"));assertFalse(dorm.matchesRoom("NTH 311"))
    }
    @Test fun zoneValidationRejectsUnlinkedBuildingsAndInvalidCoordinates(){
        assertTrue(building.valid());assertFalse(building.copy(roomKeys=emptyList()).valid())
        assertFalse(dorm.copy(radiusM=0f).valid());assertFalse(dorm.copy(latitude=Double.NaN).valid())
    }
}
