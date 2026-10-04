package kr.heureum.app.core

import kotlin.math.abs

object OcrConsensus {
    fun merge(variants: List<List<TextBox>>): List<TextBox> {
        val groups=mutableListOf<MutableList<Pair<Int,TextBox>>>()
        for ((version, lines) in variants.withIndex()) for (line in lines.sortedBy { it.top }) {
            val group=groups.minByOrNull { abs(it.first().second.y-line.y) }
                ?.takeIf { abs(it.first().second.y-line.y)<maxOf(it.first().second.bottom-it.first().second.top,line.bottom-line.top)*.65 }
            if(group==null)groups+=mutableListOf(version to line)else group+=version to line
        }
        fun normalized(text:String)=text.replace(Regex("\\s+"),"")
        return groups.map { group ->
            val perVersion=group.groupBy { it.first }.map { (version, pieces) ->
                val parts=pieces.map { it.second }.sortedBy { it.left }
                version to TextBox(parts.joinToString(" "){it.text},parts.minOf{it.left},parts.minOf{it.top},parts.maxOf{it.right},parts.maxOf{it.bottom})
            }
            val votes=perVersion.groupBy { normalized(it.second.text) }
            val chosen=votes.values.sortedWith(compareByDescending<List<Pair<Int,TextBox>>>{it.size}.thenBy{it.minOf{item->item.first}}).first().minBy{it.first}.second
            chosen.copy(needsReview=votes.size>1 || perVersion.size<variants.size)
        }.sortedBy { it.top }
    }
}
