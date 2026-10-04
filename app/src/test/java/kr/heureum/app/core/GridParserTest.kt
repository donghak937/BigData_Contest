package kr.heureum.app.core

import org.junit.Assert.*
import org.junit.Test

class GridParserTest {
    private fun b(t:String,x:Float,y:Float)=TextBox(t,x-10,y,x+10,y+14)
    private val headers=listOf(b("월",100f,10f),b("화",200f,10f),b("수",300f,10f),b("목",400f,10f),b("금",500f,10f))
    private val markers=listOf(b("9",25f,60f),b("10",25f,120f),b("11",25f,180f))
    @Test fun detectsAxesFromActualDayAndHourMarkers(){
        val axis=GridParser.detectAxis(headers+markers)!!
        assertEquals(5,axis.dayCenters.size);assertEquals(540,axis.minuteAt(60f));assertEquals(630,axis.minuteAt(150f))
    }
    @Test fun missingAxisNeverUsesGuessedTimes(){
        assertNull(GridParser.detectAxis(headers));assertNull(GridParser.detectAxis(markers))
    }
    @Test fun irregularHourScaleRequiresCalibration(){
        assertNull(GridParser.detectAxis(headers+listOf(b("9",25f,60f),b("10",25f,120f),b("11",25f,240f))))
    }
    @Test fun courseUsesBlockBoundsInsteadOfTextHeight(){
        val axis=GridParser.detectAxis(headers+markers)!!
        val boxes=listOf(b("자료구조",100f,70f),b("공학관 301",100f,90f))
        val drafts=GridParser.drafts(boxes,axis){_,_->60f to 150f}
        assertEquals(1,drafts.size);assertEquals(540,drafts[0].startMinute);assertEquals(630,drafts[0].endMinute);assertEquals("공학관 301",drafts[0].room)
    }
    @Test fun uncoloredOrOutOfGridTextDoesNotInventClasses(){
        val axis=GridParser.detectAxis(headers+markers)!!
        assertTrue(GridParser.drafts(listOf(b("학교",100f,70f)),axis){_,_->null}.isEmpty())
    }
    @Test fun missingFirstHourMarkerDoesNotDiscardEarlierCourse(){
        val axis=GridParser.detectAxis(headers+markers.drop(1))!!
        val drafts=GridParser.drafts(listOf(b("자료구조",100f,70f)),axis){_,_->60f to 150f}
        assertEquals(1,drafts.size);assertEquals(540,drafts[0].startMinute);assertEquals(630,drafts[0].endMinute)
    }
    @Test fun missingInteriorDayHeaderKeepsItsCourses(){
        val axis=GridParser.detectAxis(headers.filterNot{it.text=="수"}+markers)!!
        assertEquals((1..5).toList(),axis.dayCenters.map{it.first})
        val drafts=GridParser.drafts(listOf(b("자료구조",300f,70f)),axis){_,_->60f to 150f}
        assertEquals(3,drafts.single().weekday)
    }
    @Test fun croppedOuterDayIsNotInvented(){
        val axis=GridParser.detectAxis(headers.drop(1)+markers)!!
        assertEquals((2..5).toList(),axis.dayCenters.map{it.first})
    }
    @Test fun inconsistentDaySpacingRequiresCalibration(){
        assertNull(GridParser.detectAxis(listOf(b("월",100f,10f),b("화",200f,10f),b("수",500f,10f))+markers))
    }
    @Test fun wrappedCourseTitleIsNotMistakenForRoom(){
        val axis=GridParser.detectAxis(headers+markers)!!
        val boxes=listOf(b("인공지능",100f,70f),b("개론",100f,90f),b("공학관 301",100f,110f))
        val draft=GridParser.drafts(boxes,axis){_,_->60f to 150f}.single()
        assertEquals("인공지능 개론",draft.title);assertEquals("공학관 301",draft.room)
    }
    @Test fun titleEndingInNumberIsNotMistakenForRoom(){
        val axis=GridParser.detectAxis(headers+markers)!!
        val draft=GridParser.drafts(listOf(b("영어",100f,70f),b("회화 101",100f,90f)),axis){_,_->60f to 150f}.single()
        assertEquals("영어 회화 101",draft.title);assertEquals("",draft.room)
    }
    @Test fun numericRoomSuffixIsSeparated(){
        val axis=GridParser.detectAxis(headers+markers)!!
        val draft=GridParser.drafts(listOf(b("통계학",100f,70f),b("301호",100f,90f)),axis){_,_->60f to 150f}.single()
        assertEquals("통계학",draft.title);assertEquals("301호",draft.room)
    }
    @Test fun twelveHourLabelsContinueThroughAfternoon(){
        val labels=(9..20).map{hour->b((if(hour>12)hour-12 else hour).toString(),25f,60f+(hour-9)*60)}
        val axis=GridParser.detectAxis(headers+labels)!!
        assertEquals(13*60,axis.minuteAt(300f));assertEquals(20*60,axis.minuteAt(720f))
    }
    @Test fun twelveHourClockStillWorksWhenNoonMarkerIsMissing(){
        val labels=listOf(b("10",25f,120f),b("11",25f,180f),b("1",25f,300f),b("2",25f,360f))
        val axis=GridParser.detectAxis(headers+labels)!!
        assertEquals(13*60,axis.minuteAt(300f))
    }
    @Test fun isolatedEarlyHourLabelsRequireAmPmCalibration(){
        assertNull(GridParser.detectAxis(headers+listOf(b("1",25f,60f),b("2",25f,120f),b("3",25f,180f))))
    }
    @Test fun compactBuildingCodeIsSeparatedFromTitle(){
        val axis=GridParser.detectAxis(headers+markers)!!
        val draft=GridParser.drafts(listOf(b("웹서개 TA",100f,70f),b("NTH213",100f,90f)),axis){_,_->60f to 150f}.single()
        assertEquals("웹서개 TA",draft.title);assertEquals("NTH213",draft.room)
    }
    @Test fun buildingCodeAndKoreanHallNameAreSeparated(){
        val axis=GridParser.detectAxis(headers+markers)!!
        val draft=GridParser.drafts(listOf(b("채플(한국어)",100f,70f),b("6",100f,90f),b("HCA 효암본관",100f,110f)),axis){_,_->60f to 150f}.single()
        assertEquals("채플(한국어) 6",draft.title);assertEquals("HCA 효암본관",draft.room)
    }
}
