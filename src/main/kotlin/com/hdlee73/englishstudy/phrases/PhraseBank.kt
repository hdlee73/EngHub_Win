package com.hdlee73.englishstudy.phrases

import java.util.Locale

data class Phrase(
    val phrase: String,
    val region: String,
    val category: String,
    val korean: String,
    val note: String,
    val example: String,
    val exampleKo: String,
    val topic: String = ""
)

object PhraseBank {
    val REGIONS = listOf("미국", "영국", "공통")
    val CATEGORIES = listOf("패턴", "일상 표현", "관용구", "슬랭", "미영 차이")

    fun parse(text: String): List<Phrase> = text.lineSequence().mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
        val f = line.split('|')
        if (f.size < 7 || f[0].isBlank()) return@mapNotNull null
        Phrase(f[0].trim(), f[1].trim(), f[2].trim(), f[3].trim(), f[4].trim(), f[5].trim(), f[6].trim(), f.getOrElse(7) { "" }.trim())
    }.toList()

    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()

    fun search(all: List<Phrase>, query: String, region: String? = null, category: String? = null): List<Phrase> {
        val pool = all.filter { (region == null || it.region == region) && (category == null || it.category == category) }
        val q = norm(query)
        if (q.isEmpty()) return pool
        val words = q.split(' ')
        return pool.mapNotNull { p ->
            val head = norm(p.phrase)
            val korean = norm(p.korean)
            val rest = norm(p.note + " " + p.example + " " + p.exampleKo)
            val all3 = "$head $korean $rest"
            if (!words.all { all3.contains(it) }) return@mapNotNull null
            p
        }
    }
}
