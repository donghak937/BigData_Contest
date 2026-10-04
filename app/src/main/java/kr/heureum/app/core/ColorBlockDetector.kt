package kr.heureum.app.core

import kotlin.math.abs
import kotlin.math.roundToInt

data class CourseCell(val weekday: Int, val left: Int, val top: Int, val right: Int, val bottom: Int, val color: Int)

/** Detect geometry before reading text so OCR cannot merge neighboring weekdays. */
object ColorBlockDetector {
    private fun channels(c: Int) = intArrayOf(c shr 16 and 255, c shr 8 and 255, c and 255)
    private fun colored(c: Int): Boolean {
        val rgb = channels(c); val high = rgb.max(); val low = rgb.min()
        return high > 50 && high - low > high * .08
    }
    private fun similar(a: Int, b: Int): Boolean {
        val x = channels(a); val y = channels(b)
        return x.indices.sumOf { abs(x[it] - y[it]) } < 90
    }
    fun isText(pixel: Int, background: Int): Boolean {
        val rgb=channels(pixel); val base=channels(background)
        val brightness=rgb.average(); val baseBrightness=base.average()
        // Retain antialiased white strokes, which still contain the block's color.
        val spread=rgb.max()-rgb.min(); val baseSpread=base.max()-base.min()
        return brightness>baseBrightness+15 && spread<maxOf(15.0,baseSpread*.85) ||
            brightness<baseBrightness-45 && spread<50
    }
    fun alignAxis(width: Int, height: Int, axis: GridAxis, pixel: (Int, Int) -> Int): GridAxis {
        val lines = mutableListOf<Float>()
        var first: Int? = null
        for (y in axis.gridTop.toInt().coerceAtLeast(0) until height) {
            val votes = axis.dayCenters.count { (_, x) ->
                val rgb = channels(pixel(x.toInt().coerceIn(0, width - 1), y))
                rgb.max() - rgb.min() < 12 && rgb.min() in 170..250
            }
            if (votes >= maxOf(2, (axis.dayCenters.size + 2) / 3)) {
                if (first == null) first = y
            } else if (first != null) {
                if (y - first <= 5) lines += (first + y - 1) / 2f
                first = null
            }
        }
        val nearest = lines.minByOrNull { abs(it - axis.hourY) } ?: return axis
        if (abs(nearest - axis.hourY) > axis.pixelsPerHour * .18) return axis
        val points = lines.mapNotNull { y ->
            val index = ((y - nearest) / axis.pixelsPerHour).roundToInt()
            if (abs(y - (nearest + index * axis.pixelsPerHour)) <= axis.pixelsPerHour * .1) index.toFloat() to y else null
        }.distinctBy { it.first }
        if (points.size < 3) return axis
        val meanX = points.map { it.first }.average().toFloat()
        val meanY = points.map { it.second }.average().toFloat()
        val denominator = points.sumOf { ((it.first - meanX) * (it.first - meanX)).toDouble() }
        if (denominator == 0.0) return axis
        val slope = (points.sumOf { ((it.first - meanX) * (it.second - meanY)).toDouble() } / denominator).toFloat()
        if (abs(slope - axis.pixelsPerHour) > axis.pixelsPerHour * .1) return axis
        return axis.copy(hourY = meanY - slope * meanX, pixelsPerHour = slope)
    }
    fun detect(width: Int, height: Int, axis: GridAxis, pixel: (Int, Int) -> Int): List<CourseCell> {
        val spacing = axis.dayCenters.zipWithNext().map { (a,b) -> (b.second-a.second)/(b.first-a.first) }.average().toFloat()
        if (!spacing.isFinite() || spacing < 12) return emptyList()
        val cells = mutableListOf<CourseCell>()
        for ((day, center) in axis.dayCenters) {
            val left = (center-spacing/2).roundToInt().coerceIn(0,width-1)
            val right = (center+spacing/2).roundToInt().coerceIn(left+1,width)
            val inset = maxOf(3, (spacing * .025).roundToInt())
            val rails = listOf(left+inset, right-inset, right-inset*2).map { it.coerceIn(left,right-1) }.distinct()
            var start: Int? = null; var background = 0
            fun finish(bottom: Int) {
                val top = start
                if (top != null && bottom-top >= axis.pixelsPerHour*.24) cells += CourseCell(day,left,top,right,bottom,background)
                start = null
            }
            for (y in axis.gridTop.toInt().coerceIn(0,height-1) until height) {
                val colors = rails.map { pixel(it,y) }.filter(::colored)
                val candidate = colors.firstOrNull { c -> colors.count { similar(c,it) } >= 2 }
                if (candidate == null) finish(y)
                else {
                    if (start != null && !similar(background,candidate)) finish(y)
                    if (start == null) { start=y; background=candidate }
                }
            }
            finish(height)
        }
        return cells.sortedWith(compareBy({it.weekday},{it.top}))
    }
}
