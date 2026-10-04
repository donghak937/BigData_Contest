package kr.heureum.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.heureum.app.core.*
import kr.heureum.app.data.LocalStore
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceTests {
    private lateinit var store:LocalStore
    private val now=System.currentTimeMillis()
    @Before fun setup(){store=LocalStore(InstrumentationRegistry.getInstrumentation().targetContext,"heureum-test.db");store.clearAll()}
    @After fun teardown(){store.clearAll();store.close()}
    private fun segment()=Segment(now/BUCKET_MS*BUCKET_MS,now,"수업","high","테스트 추정","자료구조","학교",30_000,"test.app",true,37.55,126.95,10f,now)
    private fun course(source:String)=Course(title="자료구조",weekday=1,startMinute=600,endMinute=660,validFrom="2026-09-01",validUntil="2026-12-20",source=source)

    @Test fun measurementRefreshPreservesEmaCorrection(){
        val s=segment();store.saveSegment(s);val id=store.createPrompt(s.start,now,"수업","test","uncertainty")
        assertTrue(store.answerPrompt(id,"휴식"));store.saveSegment(s.copy(reason="새로운 관측",screenMs=90_000))
        val saved=store.segments(s.start,s.start+BUCKET_MS).single()
        assertEquals("휴식",saved.reportedActivity);assertEquals("수업",saved.activity);assertEquals("corrected",saved.verification);assertEquals(90_000L,saved.screenMs)
    }
    @Test fun skippingNeverConfirmsPrediction(){
        val s=segment();store.saveSegment(s);val id=store.createPrompt(s.start,now,"수업","test","uncertainty")
        assertTrue(store.answerPrompt(id,null,true));val saved=store.segments(s.start,s.start+BUCKET_MS).single()
        assertNull(saved.reportedActivity);assertEquals("estimated",saved.verification);assertEquals("skipped",store.prompts().single().status)
    }
    @Test fun duplicateAndExpiredPromptsCannotOverwriteResponses(){
        val s=segment();store.saveSegment(s);val id=store.createPrompt(s.start,now-3*3_600_000L,"수업","test","uncertainty")
        assertEquals(-1L,store.createPrompt(s.start,now,"수업","test","uncertainty"))
        store.maintenance(now);assertFalse(store.answerPrompt(id,"수업"));assertNull(store.segments(s.start,s.start+BUCKET_MS).single().reportedActivity)
    }
    @Test fun reimportOnlyReplacesItsOwnSource(){
        store.saveCourse(course("manual"));store.replaceCourses("everytime_image",listOf(course("everytime_image")))
        store.replaceCourses("everytime_image",listOf(course("everytime_image").copy(title="통계학")))
        assertEquals(2,store.courses().size);assertTrue(store.courses().any{it.source=="manual" && it.title=="자료구조"})
    }
    @Test fun exportSeparatesEstimatedAndReportedAndOmitsGpsByDefault(){
        val s=segment();store.saveSegment(s);store.addGeo(GeoSample(37.55,126.95,10f,now));store.correctSegment(s.start,"휴식")
        val safe=JSONObject(store.export(false));assertFalse(safe.has("geo"));val row=safe.getJSONArray("segments").getJSONObject(0)
        assertFalse(row.has("latitude"));assertEquals("수업",row.getString("activity"));assertEquals("휴식",row.getString("reportedActivity"))
        assertTrue(JSONObject(store.export(true)).getJSONArray("segments").getJSONObject(0).has("latitude"))
    }
    @Test fun retentionExpiresRawDataButKeepsTimetable(){
        val old=now-31*86_400_000L;store.saveSegment(segment().copy(start=old,end=old+BUCKET_MS));store.addGeo(GeoSample(37.55,126.95,10f,old));store.saveCourse(course("manual"))
        store.maintenance(now);assertTrue(store.segments(0,now).isEmpty());assertTrue(store.geoSince(0).isEmpty());assertEquals(1,store.courses().size)
    }
    @Test fun mainActivityLaunchesAndShowsAllNavigation(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try{instrumentation.runOnMainSync{
            val decor=activity.window.decorView
            fun texts(view:android.view.View):List<String> = when(view){is TextView->listOf(view.text.toString());is android.view.ViewGroup->(0 until view.childCount).flatMap{texts(view.getChildAt(it))};else->emptyList()}
            val all=texts(decor);assertTrue(all.containsAll(listOf("흐름","오늘","시간표","EMA","설정")))
        }}finally{instrumentation.runOnMainSync{activity.finish()}}
    }
}
