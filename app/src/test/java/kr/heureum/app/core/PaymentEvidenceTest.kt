package kr.heureum.app.core

import org.junit.Assert.*
import org.junit.Test

class PaymentEvidenceTest {
    private val t=1_800_000_000_000L
    private val restaurant=MapPlace("r","학생식당","food",MapPoint(37.0,127.0),emptyList(),aliases=listOf("학생식당"))
    private fun event(merchant:String="학생식당",category:String="food")=PaymentEvent(1,t,"test.card",merchant,category,"approval")
    private fun fix(minute:Int,lat:Double=37.0,accuracy:Float=5f)=GeoSample(lat,127.0,accuracy,t+minute*60_000)
    private val unknown=Prediction("활동 미확인","low","미확인",null,"위치 미확인",true)
    @Test fun explicitMerchantAndAmountAreParsed(){
        assertEquals(ParsedPayment("학생식당","8000","approval"),PaymentParser.parse("카드 승인","가맹점: 학생식당\n8,000원"))
        assertEquals("convenience",PaymentParser.category("GS25 학생회관점"))
        assertEquals("cancel",PaymentParser.parse("결제 취소","가맹점: 학생식당\n8,000원")!!.kind)
    }
    @Test fun merchantInlineAmountIsAcceptedButPrivateIdentifiersAreRejected(){
        assertEquals("GS25 학생회관점",PaymentParser.parse("결제 완료","GS25 학생회관점 5,000원")!!.merchant)
        assertNull(PaymentParser.parse("승인","가맹점: 계좌 123456789\n8,000원"))
        assertNull(PaymentParser.parse("승인","가맹점: 카드 1234\n8,000원"))
    }
    @Test fun otpTransfersStatementsAndFailuresNeverBecomePurchases(){
        for(title in listOf("인증 결제", "OTP 결제", "결제 예정", "송금 완료", "결제 실패", "자동이체 결제", "결제 혜택"))assertNull(title,PaymentParser.parse(title,"가맹점: 학생식당\n8,000원"))
        assertNull(PaymentParser.parse("승인","가맹점: 학생식당\n8,000원\n잔액 10,000원"))
        assertNull(PaymentParser.parse("승인","8,000원"))
    }
    @Test fun restaurantPaymentAndContinuousLocationsProduceObservedSpan(){
        val o=PaymentInference.observation(event(),listOf(fix(-10),fix(-8),fix(-6),fix(-4),fix(-2),fix(0),fix(2),fix(4)),listOf(restaurant))
        assertEquals("식사 추정",o.activity);assertEquals(14L,o.observedMinutes);assertTrue(o.reason.contains("실제 먹은 시간이 아니"))
        assertTrue(o.reason.contains("부근")) // POI nodes must not assert building entry.
    }
    @Test fun conveniencePurchaseDoesNotEstablishFoodOrEating(){
        val place=restaurant.copy(id="c",name="GS25 학생회관점",kind="convenience",aliases=emptyList())
        val o=PaymentInference.observation(event(place.name,"convenience"),listOf(fix(-2),fix(0),fix(2)),listOf(place))
        assertEquals("편의점 구매 추정",o.activity);assertTrue(o.reason.contains("식품 구매 여부는 미확인"))
    }
    @Test fun missingOrAmbiguousOrInaccurateLocationDoesNotProduceDuration(){
        assertNull(PaymentInference.observation(event(),emptyList(),listOf(restaurant)).observedMinutes)
        assertNull(PaymentInference.observation(event(),listOf(fix(0,accuracy=80f)),listOf(restaurant)).observedMinutes)
        assertNull(PaymentInference.observation(event(),listOf(fix(-2),fix(0)),listOf(restaurant,restaurant.copy(id="other"))).observedMinutes)
    }
    @Test fun largeGapAndLeavingPlaceBreakDwell(){
        val o=PaymentInference.observation(event(),listOf(fix(-30),fix(-28),fix(-2),fix(0),fix(2),fix(3,37.01),fix(4)),listOf(restaurant))
        assertEquals(4L,o.observedMinutes)
    }
    @Test fun cancellationsAndUnusableApprovalsNeverInferMeal(){
        val samples=listOf(fix(-2),fix(0),fix(2))
        assertNull(PaymentInference.observation(event().copy(kind="cancel"),samples,listOf(restaurant)).observedMinutes)
        assertNull(PaymentInference.observation(event().copy(usable=false),samples,listOf(restaurant)).observedMinutes)
    }
    @Test fun paymentCannotOverrideClassOrInferMealAfterLeaving(){
        val samples=listOf(fix(-2),fix(0),fix(2))
        assertEquals("식사",PaymentInference.prediction(unknown,t+120_000,listOf(event()),samples,listOf(restaurant)).activity)
        val course=unknown.copy(activity="수업")
        assertEquals(course,PaymentInference.prediction(course,t+120_000,listOf(event()),samples,listOf(restaurant)))
        val dormBase=unknown.copy(place="생활관",mealExcluded=true)
        assertEquals(dormBase,PaymentInference.prediction(dormBase,t+120_000,listOf(event()),samples,listOf(restaurant)))
        assertEquals(unknown,PaymentInference.prediction(unknown,t+180_000,listOf(event()),samples+fix(3,37.01),listOf(restaurant)))
        assertEquals(unknown,PaymentInference.prediction(unknown,t+600_000,listOf(event()),samples,listOf(restaurant)))
        val dorm=restaurant.copy(id="d",kind="dorm",outlines=listOf(listOf(MapPoint(36.999,126.999),MapPoint(36.999,127.001),MapPoint(37.001,127.001),MapPoint(37.001,126.999),MapPoint(36.999,126.999))))
        assertEquals(unknown,PaymentInference.prediction(unknown,t+120_000,listOf(event()),samples,listOf(restaurant,dorm)))
    }
    @Test fun collectionRestartDoesNotJoinTwoVisits(){
        val samples=listOf(fix(-4).copy(sessionStart=1),fix(-2).copy(sessionStart=1),fix(0).copy(sessionStart=2),fix(2).copy(sessionStart=2))
        assertEquals(2L,PaymentInference.observation(event(),samples,listOf(restaurant)).observedMinutes)
    }
}
