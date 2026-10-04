package kr.heureum.app.core

import kotlin.math.abs
import kotlin.math.roundToInt

data class TextBox(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float, val needsReview: Boolean = false) {
    val x get() = (left + right) / 2
    val y get() = (top + bottom) / 2
}
data class GridAxis(val dayCenters: List<Pair<Int, Float>>, val hourY: Float, val hour: Int, val pixelsPerHour: Float, val gridTop: Float = hourY) {
    fun minuteAt(y: Float): Int = ((hour * 60 + (y - hourY) / pixelsPerHour * 60) / 5).roundToInt() * 5
}
data class ImportDraft(val title: String, val weekday: Int, val startMinute: Int, val endMinute: Int, val room: String, val needsReview: Boolean = false)

object GridParser {
    private val days = mapOf("월" to 1, "화" to 2, "수" to 3, "목" to 4, "금" to 5, "토" to 6, "일" to 7)
    fun detectAxis(boxes: List<TextBox>): GridAxis? {
        val headers = boxes.mapNotNull { box -> days[box.text.trim().removeSuffix("요일")]?.let { Triple(it, box.x, box.y) } }
        if (headers.size < 2) return null
        val row = headers.map { candidate -> headers.filter { abs(it.third - candidate.third) <= 20 } }.maxByOrNull { it.size } ?: return null
        val ds = row.distinctBy { it.first }.sortedBy { it.second }
        if (ds.size < 2 || ds.zipWithNext().any { (a, b) -> a.first >= b.first }) return null
        val spacings = ds.zipWithNext().map { (a, b) -> (b.second - a.second) / (b.first - a.first) }
        val spacing = spacings.average().toFloat()
        if (spacing <= 0 || spacings.any { abs(it - spacing) > spacing * .18 }) return null
        // Only fill gaps between observed headers; never invent cropped outer columns.
        val centers = (ds.first().first..ds.last().first).map { day ->
            day to (ds.first().second + (day - ds.first().first) * spacing)
        }
        val timeX = ds.first().second - spacing / 2
        val readings = boxes.mapNotNull { b ->
            if (b.x >= timeX || b.y <= ds.first().third) return@mapNotNull null
            Regex("^(\\d{1,2})(?::00|시)?$").matchEntire(b.text.trim())?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it in 0..23 }?.let { it to b.top }
        }.distinct().sortedBy { it.second }
        val times = mutableListOf<Pair<Int, Float>>()
        var offset = 0
        for ((rawHour, y) in readings) {
            val previous = times.lastOrNull()
            if (previous != null && rawHour + offset <= previous.first) {
                // Read labels spatially: 9,10,11,12,1,2 is a daytime 12-hour clock.
                if (offset == 0 && previous.first in 9..12 && rawHour in 1..8) offset = 12
                else return null
            }
            val hour = rawHour + offset
            if (hour > 23) return null
            times += hour to y
        }
        if (times.size < 2) return null
        // An isolated 1..8 axis cannot tell us AM versus PM without user calibration.
        if (times.first().first in 1..8 && times.last().first <= 12) return null
        val rates = times.zipWithNext().map { (a, b) -> (b.second - a.second) / (b.first - a.first) }
        if (rates.any { it <= 10 }) return null
        val rate = rates.average().toFloat()
        if (rates.any { abs(it - rate) > rate * .18 }) return null
        return GridAxis(centers, times.first().second, times.first().first, rate,ds.maxOf { it.third }+18)
    }

    /** Colored blocks are detected by the Android bitmap adapter, not inferred from text height. */
    fun drafts(boxes: List<TextBox>, axis: GridAxis, bounds: (Float, Float) -> Pair<Float, Float>?): List<ImportDraft> {
        val width = axis.dayCenters.zipWithNext().map { (a, b) -> (b.second - a.second) / (b.first - a.first) }.average().toFloat()
        val groups = linkedMapOf<Pair<Int, Int>, MutableList<TextBox>>()
        val spans = mutableMapOf<Pair<Int, Int>, Pair<Int, Int>>()
        for (b in boxes) {
            val day = axis.dayCenters.minByOrNull { abs(it.second - b.x) } ?: continue
            if (abs(day.second - b.x) > width * .49 || b.top < axis.gridTop) continue
            val span = bounds(b.x, b.y) ?: continue
            val start = axis.minuteAt(span.first); val end = axis.minuteAt(span.second)
            if (start !in 0..1439 || end !in 1..1440 || end - start !in 15..480) continue
            val key = day.first to start
            groups.getOrPut(key) { mutableListOf() }.add(b)
            spans[key] = start to end
        }
        return groups.map { (key, lines) ->
            val sorted = lines.distinct().sortedWith(compareBy({ it.top }, { it.left })); val span = spans.getValue(key)
            // A wrapped title is still a title. Split off only a recognizable location suffix.
            val roomIndex = sorted.indexOfFirst { isRoomLine(it.text) }.takeIf { it > 0 } ?: sorted.size
            val title = sorted.take(roomIndex).joinToString(" ") { it.text.trim() }
            val room = sorted.drop(roomIndex).joinToString(" ") { it.text.trim() }
            ImportDraft(title, key.first, span.first, span.second, room, sorted.any { it.needsReview })
        }.sortedWith(compareBy({ it.weekday }, { it.startMinute }))
    }

    private fun isRoomLine(text: String): Boolean {
        val value = text.trim()
        return Regex("(?:\\d{2,4}\\s*호|(?:[가-힣A-Za-z]+관|본관|별관|[가-힣A-Za-z]+동)\\s*[-A-Za-z0-9]*\\d{2,4}(?:호)?|[A-Z]{2,5}[- ]?\\d{2,4}|[A-Z]{2,5}\\s+[가-힣]+(?:관|동))").matches(value)
    }
}
