package kr.heureum.app

import android.graphics.*
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.heureum.app.data.TimetableOcr
import kr.heureum.app.data.OcrResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class OcrDeviceTest {
    @Test fun koreanModelReadsSyntheticTimetableAndColoredDurations(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap=Bitmap.createBitmap(1000,1200,Bitmap.Config.ARGB_8888)
        val c=Canvas(bitmap);c.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(45,45,50);textSize=28f;typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
        val centers=(1..5).map{100f+(it-.5f)*180f}
        listOf("월","화","수","목","금").forEachIndexed{i,s->c.drawText(s,centers[i]-12,60f,paint)}
        val top=120f;val hourly=90f
        for(hour in 9..20){
            val y=top+(hour-9)*hourly
            paint.color=Color.LTGRAY;c.drawLine(90f,y,995f,y,paint)
            paint.color=Color.DKGRAY;c.drawText(hour.toString(),30f,y+21,paint)
        }
        for(i in 0..5){paint.color=Color.LTGRAY;c.drawLine(100f+i*180f,top,100f+i*180f,top+11*hourly,paint)}
        paint.color=Color.rgb(213,205,249);c.drawRect(102f,top+1,278f,top+135,paint)
        paint.color=Color.rgb(184,227,219);c.drawRect(282f,top+180,458f,top+270,paint)
        paint.color=Color.rgb(45,45,50);paint.textSize=27f
        c.drawText("자료구조",112f,top+40,paint);c.drawText("공학관 301",112f,top+80,paint)
        c.drawText("통계학",292f,top+220,paint);c.drawText("본관 201",292f,top+255,paint)
        val file=File(context.cacheDir,"ocr-test/timetable_fixture.png").apply{parentFile!!.mkdirs()}
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        val latch=CountDownLatch(1);var result:Result<OcrResult>?=null
        val recognizer=TimetableOcr(context)
        InstrumentationRegistry.getInstrumentation().runOnMainSync{recognizer.read(Uri.fromFile(file)){result=it;latch.countDown()}}
        try{
            assertTrue("OCR timed out",latch.await(60,TimeUnit.SECONDS))
            val read=result!!.getOrThrow()
            File(context.cacheDir,"ocr-test/result.txt").writeText("${read.axis}\n${read.boxes.joinToString("\n")}\n${read.drafts}")
            assertNotNull("Expected readable day/hour axis: ${read.boxes}",read.axis)
            assertEquals("Expected two colored course blocks: ${read.drafts}; axis=${read.axis}; boxes=${read.boxes}",2,read.drafts.size)
            val monday=read.drafts.first{it.weekday==1}
            assertTrue(monday.title.contains("자료"));assertTrue(abs(monday.startMinute-540)<=10);assertTrue(abs(monday.endMinute-630)<=10)
            val tuesday=read.drafts.first{it.weekday==2}
            assertTrue(abs(tuesday.startMinute-660)<=10);assertTrue(abs(tuesday.endMinute-720)<=10)
        }finally{recognizer.close()}
    }
}
