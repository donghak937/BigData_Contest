package kr.heureum.app.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class InferenceEngineTest {
    private val now=LocalDateTime.of(2026,10,5,10,10).atZone(STUDY_ZONE).toInstant().toEpochMilli()
    private val campus=Campus("테스트 학교",37.55,126.95,400f)
    private val course=Course(title="자료구조",weekday=1,startMinute=600,endMinute=660,validFrom="2026-09-01",validUntil="2026-12-20")
    private fun fix(at:Long=now,lat:Double=campus.latitude,lon:Double=campus.longitude,accuracy:Float=10f)=GeoSample(lat,lon,accuracy,at)

    @Test fun timetableAndSustainedCampusProduceEstimatedClass(){
        val p=InferenceEngine.predict(now,listOf(course),campus,listOf(fix(now-180_000),fix()))
        assertEquals("수업",p.activity);assertEquals("medium",p.confidence);assertTrue(p.needsEma)
    }
    @Test fun missingAndStaleGpsNeverBecomeHighConfidence(){
        for(samples in listOf(emptyList(),listOf(fix(now-700_000)),listOf(fix(accuracy=900f)),listOf(fix(now+60_000)))){
            val p=InferenceEngine.predict(now,listOf(course),campus,samples)
            assertEquals("low",p.confidence);assertTrue(p.needsEma);assertEquals("위치 미확인",p.place)
        }
    }
    @Test fun schoolPresenceWithoutScheduleIsLowConfidenceStudyNotClass(){
        val p=InferenceEngine.predict(now,emptyList(),campus,listOf(fix(now-180_000),fix()))
        assertEquals("공부",p.activity);assertEquals("low",p.confidence);assertTrue(p.needsEma)
    }
    @Test fun outsideLocationDuringClassIsLowConfidenceAndAsked(){
        val p=InferenceEngine.predict(now,listOf(course),campus,listOf(fix(lat=37.58)))
        assertNotEquals("수업",p.activity);assertEquals("학교 밖",p.place);assertEquals("low",p.confidence);assertTrue(p.reason.contains("학교 밖"));assertTrue(p.needsEma)
    }
    @Test fun uncertaintyAtBoundaryIsNotHighConfidence(){
        val p=InferenceEngine.predict(now,listOf(course),campus,listOf(fix(lat=37.55355,accuracy=60f)))
        assertEquals("수업",p.activity);assertNotEquals("high",p.confidence);assertTrue(p.needsEma)
    }
    @Test fun conflictingSchedulesPickOneButNeedConfirmation(){
        val p=InferenceEngine.predict(now,listOf(course,course.copy(title="다른 수업")),campus,listOf(fix()))
        assertEquals("수업",p.activity);assertNotEquals("high",p.confidence);assertTrue(p.reason.contains("겹쳐"));assertTrue(p.needsEma)
    }
    @Test fun leavingAndReturningBreaksSustainedStay(){
        val p=InferenceEngine.predict(now,listOf(course),campus,listOf(fix(now-240_000),fix(now-120_000,lat=37.58),fix()))
        assertEquals("medium",p.confidence)
    }
    @Test fun semesterAndExclusiveEndAreRespected(){
        assertFalse(course.copy(validUntil="2026-10-04").activeAt(now))
        assertFalse(course.copy(endMinute=610).activeAt(now))
        assertFalse(course.copy(weekday=2).activeAt(now))
        assertTrue(course.activeAt(now))
    }
    @Test fun invalidDatesAndTimesAreRejected(){
        assertNotNull(course.copy(validFrom="bad").validate());assertNotNull(course.copy(startMinute=700).validate())
        assertNull(parseMinute("24:30"));assertNull(parseMinute("09:99"));assertEquals(1440,parseMinute("24:00"))
    }
    @Test fun emaLimitsIncludePendingAndMissedPrompts(){
        val policy=EmaPolicy()
        assertTrue(policy.canAsk(now,0,null,false));assertFalse(policy.canAsk(now,3,null,false))
        assertFalse(policy.canAsk(now,1,now-60_000,false));assertFalse(policy.canAsk(now,0,null,true))
        assertFalse(EmaPolicy(maxPerDay=0).canAsk(now,0,null,false))
        val night=LocalDateTime.of(2026,10,5,22,0).atZone(STUDY_ZONE).toInstant().toEpochMilli()
        assertFalse(policy.canAsk(night,0,null,false))
    }
}
