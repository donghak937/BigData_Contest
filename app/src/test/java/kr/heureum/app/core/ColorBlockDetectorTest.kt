package kr.heureum.app.core

import org.junit.Assert.*
import org.junit.Test

class ColorBlockDetectorTest {
    private val axis=GridAxis(listOf(1 to 60f,2 to 160f),40f,9,100f,30f)
    private val white=0xffffff
    private val purple=0x9c82d6
    private val green=0x7dc99a
    @Test fun blankTimetableNeverProducesCells(){
        assertTrue(ColorBlockDetector.detect(210,340,axis){_,_->white}.isEmpty())
    }
    @Test fun separateAdjacentColorsAndRepeatedNonadjacentClasses(){
        val cells=ColorBlockDetector.detect(210,340,axis){x,y->
            when{ x in 12..107 && y in 40..139->purple
                x in 12..107 && y in 140..214->green
                x in 12..107 && y in 265..339->green
                else->white }
        }
        assertEquals(listOf(40,140,265),cells.map{it.top})
        assertEquals(listOf(140,215,340),cells.map{it.bottom})
    }
    @Test fun thinSeparatorKeepsSameColorCoursesSeparate(){
        val cells=ColorBlockDetector.detect(210,340,axis){x,y->if(x in 12..107 && (y in 40..139 || y in 142..239))purple else white}
        assertEquals(2,cells.size)
    }
    @Test fun textOnOneRailDoesNotSplitAClass(){
        val cells=ColorBlockDetector.detect(210,340,axis){x,y->
            if(x in 12..107 && y in 40..239 && !(x<=15 && y in 50..80))purple else white
        }
        assertEquals(1,cells.size);assertEquals(40,cells.single().top);assertEquals(240,cells.single().bottom)
    }
    @Test fun alignHourLabelToVisibleGridWithoutChangingTheHour(){
        val marked=axis.copy(hourY=52f)
        val aligned=ColorBlockDetector.alignAxis(210,340,marked){_,y->if(y in listOf(40,140,240))0xededed else white}
        assertEquals(40f,aligned.hourY,.1f);assertEquals(100f,aligned.pixelsPerHour,.1f)
        assertEquals(540,aligned.minuteAt(40f));assertEquals(630,aligned.minuteAt(190f))
    }
    @Test fun absentGridLinesDoNotChangeManualAxis(){
        assertEquals(axis,ColorBlockDetector.alignAxis(210,340,axis){_,_->white})
    }
    @Test fun antialiasedWhiteTextRetainsThinStrokes(){
        assertTrue(ColorBlockDetector.isText(0xbba7e2,purple))
        assertTrue(ColorBlockDetector.isText(white,purple))
        assertTrue(ColorBlockDetector.isText(0x303030,purple))
        assertFalse(ColorBlockDetector.isText(purple,purple))
    }
}
