package kr.heureum.app.core

/** Parsed transiently. Amount is used for duplicate/cancellation matching, never persisted. */
data class ParsedPayment(val merchant: String, val amount: String, val kind: String)
data class PaymentEvent(val id: Long, val observedAt: Long, val source: String, val merchant: String, val category: String, val kind: String, val usable: Boolean = true)
data class PaymentObservation(val payment: PaymentEvent, val activity: String, val reason: String, val from: Long? = null, val until: Long? = null, val place: String? = null) {
    val observedMinutes: Long? get() = if(from != null && until != null) (until-from)/60_000 else null
}

object PaymentParser {
    private val money=Regex("(?<![\\d,])([0-9]{1,9}(?:,[0-9]{3})*)\\s*원")
    private val reject=Regex("인증|비밀번호|일회용|OTP|결제\\s*예정|예정\\s*금액|청구|자동이체|송금|출금|거절|실패|혜택|광고|적립",RegexOption.IGNORE_CASE)
    private val label=Regex("(?:가맹점|사용처|결제처|상호)\\s*[:：]\\s*([^\\n|]+)")
    private val shop=Regex("GS25|CU(?:\\s|$)|씨유|세븐일레븐|7.?ELEVEN|이마트24|식당|구내식당|분식|국밥|김밥|한솥|도시락|버거|맥도날드|롯데리아|카페|커피|스타벅스|투썸",RegexOption.IGNORE_CASE)
    fun parse(title:String, body:String):ParsedPayment? {
        val text=(title+"\n"+body).take(4096)
        if(reject.containsMatchIn(text) || !Regex("승인|결제|취소").containsMatchIn(text))return null
        val amounts=money.findAll(text).toList()
        if(amounts.size!=1)return null // Multiple amounts/statement summaries are ambiguous.
        val amount=amounts.single().groupValues[1].replace(",","").toLongOrNull()?.takeIf{it>0}?.toString()?:return null
        val candidate=label.find(text)?.groupValues?.get(1) ?: text.lines().firstOrNull{shop.containsMatchIn(it)} ?: return null
        val merchant=candidate.replace(money,"").replace(Regex("(?:승인|결제|취소)(?:완료)?"),"").trim(' ',':','：','-','|')
        // Avoid retaining account/card identifiers or mixed notification paragraphs.
        if(merchant.length !in 2..60 || Regex("[0-9*•]{4,}|계좌|카드|잔액|\\n").containsMatchIn(merchant))return null
        return ParsedPayment(merchant,amount,if(text.contains("취소"))"cancel" else "approval")
    }
    fun category(merchant:String)=when {
        Regex("GS25|^CU(?:\\s|$)|씨유|세븐일레븐|7.?ELEVEN|이마트24",RegexOption.IGNORE_CASE).containsMatchIn(merchant)->"convenience"
        Regex("카페|커피|스타벅스|투썸",RegexOption.IGNORE_CASE).containsMatchIn(merchant)->"cafe"
        Regex("식당|분식|국밥|김밥|한솥|도시락|버거|맥도날드|롯데리아").containsMatchIn(merchant)->"food"
        else->"unknown"
    }
}

object PaymentInference {
    private fun normalized(s:String)=s.replace(Regex("[\\s()·._-]"),"").lowercase(java.util.Locale.ROOT)
    private fun matches(merchant:String,p:MapPlace):Boolean {
        val m=normalized(merchant)
        return (p.aliases+p.name).any { val n=normalized(it); n.length>=3 && (m==n || m.startsWith(n) || n.startsWith(m)) }
    }
    private fun near(p:MapPlace,s:GeoSample):Boolean {
        if(!s.latitude.isFinite() || !s.longitude.isFinite() || !s.accuracyM.isFinite() || s.accuracyM !in 0f..50f)return false
        return if(p.outlines.isNotEmpty())p.relation(s)=="inside" else InferenceEngine.distanceM(s.latitude,s.longitude,p.center.latitude,p.center.longitude)+s.accuracyM<=50
    }
    fun observation(e:PaymentEvent,samples:List<GeoSample>,places:List<MapPlace>):PaymentObservation {
        if(e.kind=="cancel")return PaymentObservation(e,"결제 취소 알림","취소 알림은 구매·식사의 근거로 사용하지 않아요.")
        if(!e.usable)return PaymentObservation(e,"결제 확인 필요","일치할 수 있는 취소 알림이 있어 구매·식사의 근거에서 제외했어요.")
        val nearby=samples.filter { kotlin.math.abs(it.measuredAt-e.observedAt)<=300_000 }
        val candidates=places.filter { it.kind in listOf("food","convenience") && matches(e.merchant,it) && nearby.any { s->near(it,s) } }
        val purchase=if(e.category=="convenience")"편의점 구매 추정" else "구매 추정"
        if(candidates.size!=1)return PaymentObservation(e,purchase,"결제 알림을 관측했어요. 장소 연결이 ${if(candidates.size>1)"겹쳐요" else "확인되지 않았어요"}. 품목과 실제 식사 여부는 알 수 없어요.")
        val p=candidates.single()
        val ordered=samples.filter { kotlin.math.abs(it.measuredAt-e.observedAt)<=3*3_600_000 }.sortedBy{it.measuredAt}.distinctBy{it.measuredAt}
        val anchor=ordered.indices.filter { kotlin.math.abs(ordered[it].measuredAt-e.observedAt)<=300_000 && near(p,ordered[it]) }.minByOrNull { kotlin.math.abs(ordered[it].measuredAt-e.observedAt) } ?: return PaymentObservation(e,purchase,"결제 부근의 위치가 미확인이에요.")
        var left=anchor;var right=anchor
        while(left>0 && ordered[left].sessionStart==ordered[left-1].sessionStart && ordered[left].measuredAt-ordered[left-1].measuredAt<=300_000 && near(p,ordered[left-1]))left--
        while(right<ordered.lastIndex && ordered[right+1].sessionStart==ordered[right].sessionStart && ordered[right+1].measuredAt-ordered[right].measuredAt<=300_000 && near(p,ordered[right+1]))right++
        val from=ordered[left].measuredAt;val until=ordered[right].measuredAt
        val sustained=until-from>=120_000
        val meal=p.kind=="food" && e.category=="food" && sustained
        return PaymentObservation(e,if(meal)"식사 추정" else purchase,
            "가맹점 이름과 ${p.name}${if(p.outlines.isEmpty())" 부근" else " 내부"} 위치를 연결했어요. ${if(sustained)"표시 시간은 연속 GPS 관측 범위이며 입장·퇴장이나 실제 먹은 시간이 아니에요." else "체류 시간은 충분히 관측되지 않았어요."} ${if(e.category=="convenience")"식품 구매 여부는 미확인이에요." else "포장·대기·음료 구매일 수도 있어요."}",
            if(sustained)from else null,if(sustained)until else null,p.name)
    }
    fun prediction(base:Prediction,now:Long,events:List<PaymentEvent>,samples:List<GeoSample>,places:List<MapPlace>):Prediction {
        // A transaction cannot override class/movement/dorm evidence or a confident estimate, or infer eating later at home.
        if(base.activity in listOf(InferenceEngine.CLASS,InferenceEngine.MOVE,InferenceEngine.MEAL) || base.confidence=="high" || base.mealExcluded || places.any { p->p.kind=="dorm" && samples.maxByOrNull{it.measuredAt}?.let{p.relation(it) in listOf("inside","boundary")}==true })return base
        val e=events.filter{it.kind=="approval" && it.usable && now-it.observedAt in 0..30*60_000}.maxByOrNull{it.observedAt}?:return base
        val o=observation(e,samples,places)
        val p=places.firstOrNull{it.name==o.place}?:return base
        val fix=samples.maxByOrNull{it.measuredAt}?:return base
        if(now-fix.measuredAt !in 0..180_000 || !near(p,fix))return base
        return if(o.activity=="식사 추정")Prediction("식사","medium",o.reason,null,p.name,true,e.id) else base
    }
}
