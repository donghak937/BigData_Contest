package kr.heureum.app.core

import org.junit.Assert.*
import org.junit.Test

class OcrConsensusTest {
    private fun b(t:String,y:Float=20f,x:Float=30f)=TextBox(t,x,y,x+70,y+25)
    @Test fun agreementIgnoresOcrWhitespace(){
        val result=OcrConsensus.merge(listOf(listOf(b("NTH 213")),listOf(b("NTH213")),listOf(b("NTH 213"))))
        assertEquals("NTH 213",result.single().text);assertFalse(result.single().needsReview)
    }
    @Test fun majorityRetainsDisagreementWarning(){
        val result=OcrConsensus.merge(listOf(listOf(b("GLC 201")),listOf(b("GIC 201")),listOf(b("GLC 201"))))
        assertEquals("GLC 201",result.single().text);assertTrue(result.single().needsReview)
    }
    @Test fun unresolvedTieKeepsOriginalAndRequiresReview(){
        val result=OcrConsensus.merge(listOf(listOf(b("사봉")),listOf(b("사붕")),listOf(b("사붕?"))))
        assertEquals("사봉",result.single().text);assertTrue(result.single().needsReview)
    }
    @Test fun missingLineIsNotSilentlyDiscarded(){
        val result=OcrConsensus.merge(listOf(listOf(b("자료구조"),b("공학관 301",60f)),listOf(b("자료구조")),listOf(b("자료구조"))))
        assertEquals(2,result.size);assertTrue(result[1].needsReview)
    }
    @Test fun neighboringElementsInOneRowAreMergedBeforeVoting(){
        val result=OcrConsensus.merge(listOf(listOf(b("GLC",x=30f),b("201",x=105f)),listOf(b("GLC 201")),listOf(b("GLC201"))))
        assertEquals("GLC 201",result.single().text);assertFalse(result.single().needsReview)
    }
}
