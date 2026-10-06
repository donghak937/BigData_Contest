package kr.heureum.app.ui

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kr.heureum.app.core.*
import kotlin.math.*

/**
 * 24-hour activity wheel: midnight at the top, clockwise. Each arc is an estimated (or participant-confirmed)
 * activity; the pale track is time that was not recorded. Tap an arc to see its time range in the centre.
 */
class DayWheelView(context: Context) : View(context) {
    companion object {
        /** Categorical slots in DayWheel.order (validated palette; fixed order, never cycled). */
        val colors = mapOf(
            "수업" to Color.parseColor("#2a78d6"), "식사" to Color.parseColor("#eb6834"), "이동" to Color.parseColor("#1baf7a"),
            "공부" to Color.parseColor("#eda100"), "휴식" to Color.parseColor("#e87ba4"), "업무" to Color.parseColor("#008300"),
            "수면" to Color.parseColor("#4a3aa7"), "기타" to Color.parseColor("#e34948"), DayWheel.UNKNOWN to Color.parseColor("#b5b3ad")
        )
        private val ink = Color.rgb(34, 35, 42)
        private val muted = Color.rgb(110, 111, 123)
        private val track = Color.rgb(236, 235, 231)
        fun color(activity: String) = colors[activity] ?: colors.getValue("기타")
        /** Dark ink on light fills, white on dark fills, so arc labels stay readable. */
        fun labelColor(fill: Int): Int {
            fun ch(c: Int) = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
            val l = 0.2126 * ch(Color.red(fill)) + 0.7152 * ch(Color.green(fill)) + 0.0722 * ch(Color.blue(fill))
            return if (l > 0.3) ink else Color.WHITE
        }
    }

    var arcs: List<DayArc> = emptyList(); set(v) { field = v; selected = null; invalidate() }
    /** Minutes since midnight for the "now" tick, or null for past days. */
    var nowMinute: Double? = null; set(v) { field = v; invalidate() }
    var onArcSelected: ((DayArc?) -> Unit)? = null
    private var selected: DayArc? = null
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val side = min(w, (340 * density).toInt())
        setMeasuredDimension(w, side)
    }

    private fun geometry(): Triple<Float, Float, Float> {
        val r = min(width, height) / 2f - 22 * density // room for hour labels outside the ring
        return Triple(width / 2f, height / 2f, r)
    }
    private fun angle(minute: Double) = (minute / 1440.0 * 360.0 - 90.0).toFloat()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val (cx, cy, r) = geometry()
        val thickness = r * 0.30f
        val mid = r - thickness / 2
        oval.set(cx - mid, cy - mid, cx + mid, cy + mid)
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.BUTT; paint.strokeWidth = thickness
        paint.color = track; canvas.drawArc(oval, 0f, 360f, false, paint)

        // Arcs with a 2 dp surface gap between neighbours.
        val gapDeg = Math.toDegrees((2 * density / mid).toDouble()).toFloat()
        for (a in arcs) {
            val sweep = (a.minutes / 1440.0 * 360.0).toFloat()
            val drawn = if (sweep > gapDeg * 2) sweep - gapDeg else sweep
            paint.color = color(a.activity)
            paint.strokeWidth = if (a == selected) thickness + 6 * density else thickness
            canvas.drawArc(oval, angle(a.startMin) + if (sweep > gapDeg * 2) gapDeg / 2 else 0f, drawn, false, paint)
        }
        paint.strokeWidth = thickness

        // Hour ticks and labels outside the ring.
        paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER
        for (h in 0 until 24) {
            val rad = Math.toRadians(angle(h * 60.0).toDouble())
            val major = h % 6 == 0
            paint.color = if (major) ink else muted; paint.strokeWidth = if (major) 2 * density else density
            val inner = r + 2 * density; val outer = r + (if (major) 7 else 4) * density
            canvas.drawLine(cx + inner * cos(rad).toFloat(), cy + inner * sin(rad).toFloat(), cx + outer * cos(rad).toFloat(), cy + outer * sin(rad).toFloat(), paint)
            if (h % 3 == 0) {
                paint.textSize = (if (major) 12 else 10) * density; paint.typeface = if (major) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                val lr = r + 15 * density
                canvas.drawText("$h", cx + lr * cos(rad).toFloat(), cy + lr * sin(rad).toFloat() + paint.textSize / 3, paint)
            }
        }

        // Direct labels on arcs long enough to hold them (identity is never colour alone).
        paint.textSize = 11 * density; paint.typeface = Typeface.DEFAULT_BOLD
        for (a in arcs) {
            val arcLength = a.minutes / 1440.0 * 2 * PI * mid
            if (arcLength < paint.measureText(a.activity) + 8 * density) continue
            val rad = Math.toRadians(angle((a.startMin + a.endMin) / 2).toDouble())
            paint.color = labelColor(color(a.activity))
            canvas.drawText(a.activity, cx + mid * cos(rad).toFloat(), cy + mid * sin(rad).toFloat() + paint.textSize / 3, paint)
        }

        // "Now" tick.
        nowMinute?.let { m ->
            val rad = Math.toRadians(angle(m).toDouble())
            paint.color = ink; paint.strokeWidth = 2 * density
            val a = r - thickness - 2 * density; val b = r + 2 * density
            canvas.drawLine(cx + a * cos(rad).toFloat(), cy + a * sin(rad).toFloat(), cx + b * cos(rad).toFloat(), cy + b * sin(rad).toFloat(), paint)
            canvas.drawCircle(cx + a * cos(rad).toFloat(), cy + a * sin(rad).toFloat(), 3 * density, paint)
        }

        // Centre: the selected arc, or the day's recorded total.
        val s = selected
        paint.color = ink; paint.typeface = Typeface.DEFAULT_BOLD
        if (s != null) {
            paint.textSize = 20 * density; canvas.drawText(s.activity, cx, cy - 4 * density, paint)
            paint.typeface = Typeface.DEFAULT; paint.color = muted; paint.textSize = 12 * density
            canvas.drawText("${minuteText(s.startMin.toInt())}–${minuteText(s.endMin.roundToInt().coerceAtMost(1440))} · ${DayWheel.duration(s.minutes.roundToInt())}", cx, cy + 14 * density, paint)
            s.place?.takeIf { it.isNotBlank() }?.let { canvas.drawText(it.take(16), cx, cy + 30 * density, paint) }
        } else {
            val recorded = arcs.sumOf { it.minutes }.roundToInt()
            paint.textSize = 20 * density; canvas.drawText(if (recorded > 0) DayWheel.duration(recorded) else "기록 없음", cx, cy, paint)
            paint.typeface = Typeface.DEFAULT; paint.color = muted; paint.textSize = 12 * density
            canvas.drawText(if (recorded > 0) "기록된 시간 · 원을 눌러 보기" else "기록을 켜면 채워져요", cx, cy + 18 * density, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) return true
        if (event.actionMasked != MotionEvent.ACTION_UP) return super.onTouchEvent(event)
        performClick()
        val (cx, cy, r) = geometry()
        val dx = event.x - cx; val dy = event.y - cy
        val dist = hypot(dx, dy)
        val hit = if (dist in r * 0.55f..r + 12 * density) {
            val deg = (Math.toDegrees(atan2(dy, dx).toDouble()) + 90 + 360) % 360
            val minute = deg / 360 * 1440
            arcs.firstOrNull { minute >= it.startMin - 4 && minute <= it.endMin + 4 }
        } else null
        selected = if (hit == selected) null else hit
        onArcSelected?.invoke(selected); invalidate()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
