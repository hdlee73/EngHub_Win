package com.hdlee73.englishstudy.dictionary

import java.io.File
import java.net.URLEncoder
import java.util.Locale
import okhttp3.OkHttpClient
import okhttp3.Request

data class WordEntry(
    val word: String,
    val ipa: String = "",
    val korean: String = "",
    val english: String = "",
    val examples: String = ""
)

/** Minimal JSON reader */
internal object MiniJson {
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value()
        reader.skipSpace()
        return value
    }

    private class Reader(val s: String) {
        var index = 0
        fun skipSpace() { while (index < s.length && s[index].isWhitespace()) index++ }
        private fun fail(): Nothing = throw IllegalArgumentException("Invalid JSON at $index")
        fun value(): Any? {
            skipSpace()
            if (index >= s.length) fail()
            return when (val c = s[index]) {
                '[' -> list()
                '{' -> obj()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else fail()
            }
        }
        private fun literal(word: String, result: Any?): Any? {
            if (!s.startsWith(word, index)) fail()
            index += word.length
            return result
        }
        private fun number(): Double {
            val start = index
            while (index < s.length && (s[index].isDigit() || s[index] in "+-.eE")) index++
            return s.substring(start, index).toDoubleOrNull() ?: fail()
        }
        private fun list(): List<Any?> {
            index++
            val out = mutableListOf<Any?>()
            skipSpace()
            if (index < s.length && s[index] == ']') { index++; return out }
            while (true) {
                out += value()
                skipSpace()
                if (index >= s.length) fail()
                when (s[index++]) { ',' -> continue; ']' -> return out; else -> fail() }
            }
        }
        private fun obj(): Map<String, Any?> {
            index++
            val out = linkedMapOf<String, Any?>()
            skipSpace()
            if (index < s.length && s[index] == '}') { index++; return out }
            while (true) {
                skipSpace()
                if (index >= s.length || s[index] != '"') fail()
                val key = string()
                skipSpace()
                if (index >= s.length || s[index++] != ':') fail()
                out[key] = value()
                skipSpace()
                if (index >= s.length) fail()
                when (s[index++]) { ',' -> continue; '}' -> return out; else -> fail() }
            }
        }
        private fun string(): String {
            index++
            val out = StringBuilder()
            while (index < s.length) {
                val c = s[index++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (index >= s.length) fail()
                        when (val e = s[index++]) {
                            'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r')
                            'b' -> out.append('\b'); 'f' -> out.append('\u000c')
                            'u' -> {
                                if (index + 4 > s.length) fail()
                                out.append(s.substring(index, index + 4).toIntOrNull(16)?.toChar() ?: fail())
                                index += 4
                            }
                            else -> out.append(e)
                        }
                    }
                    else -> out.append(c)
                }
            }
            fail()
        }
    }
}

object DictionaryEngine {
    private val http = OkHttpClient()

    private val parts = mapOf(
        "noun" to "명사", "verb" to "동사", "adjective" to "형용사", "adverb" to "부사",
        "pronoun" to "대명사", "preposition" to "전치사", "conjunction" to "접속사", "interjection" to "감탄사",
        "article" to "관사", "phrase" to "숙어"
    )

    private val reviewed = listOf(
        WordEntry("catch up", "", "1. (앞서가는 사람 등을) 따라잡다\n2. (밀린 일을) 따라 하다\n3. (만나서) 소식을 나누다", "1. To reach someone after falling behind.\n2. To do work that should have been done earlier.\n3. To talk with someone about what happened.", "I ran faster to catch up.\t따라잡으려고 더 빨리 달렸다.\nLet's meet and catch up.\t만나서 이야기하자."),
        WordEntry("look forward to", "", "~을 고대하다, 기대하다", "To anticipate something with pleasure.", "I'm looking forward to seeing you.\t너를 다시 만나길 기대해."),
        WordEntry("give up", "", "1. 포기하다\n2. 그만두다", "1. To stop trying.\n2. To stop a habit.", "Don't give up on your dream.\t꿈을 포기하지 마."),
        WordEntry("put off", "", "미루다, 연기하다", "To postpone something.", "We put off the meeting until Friday.\t우리는 회의를 금요일로 미뤘다."),
        WordEntry("figure out", "", "알아내다, 이해하다; 해결하다", "To understand or solve something.", "I finally figured it out.\t마침내 방법을 알아냈다."),
        WordEntry("run out of", "", "다 써서 바닥나다", "To have none left.", "We've run out of coffee.\t커피가 다 떨어졌어."),
        WordEntry("take off", "", "1. 벗다\n2. (비행기가) 이륙하다\n3. 급성장하다", "1. To remove clothes.\n2. To leave the ground.", "The plane took off on time.\t비행기는 정시에 이륙했다."),
        WordEntry("get along", "", "1. 사이좋게 지내다\n2. 해 나가다", "1. To have a friendly relationship.\n2. To manage.", "She gets along well with others.\t그녀는 다른 사람들과 잘 지낸다."),
        WordEntry("break down", "", "1. 고장 나다\n2. 무너지다\n3. 나누어 분석하다", "1. To stop functioning.\n2. To fail.", "Our car broke down on the highway.\t차가 고속도로에서 고장 났다."),
        WordEntry("stuck", "", "1. 끼어서 움직이지 못하는\n2. 막혀서 진전이 없는", "1. Unable to move.\n2. Unable to make progress.", "I'm stuck on this problem.\t이 문제에서 막혔어.")
    ).associateBy { it.word.lowercase() }

    fun lookup(query: String): WordEntry {
        val q = query.trim()
        if (q.isEmpty()) return WordEntry("", "", "", "", "")

        val curated = reviewed[q.lowercase(Locale.ROOT)]
        if (curated != null) return curated

        // Online Google Dictionary & Translation lookup
        return lookupOnline(q)
    }

    private fun lookupOnline(text: String): WordEntry {
        return try {
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ko&dt=t&dt=bd&q=" +
                    URLEncoder.encode(text, "UTF-8")
            val req = Request.Builder().url(url).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return WordEntry(text, "", "사전 결과를 가져오지 못했습니다.", "", "")
                val jsonStr = resp.body?.string().orEmpty()
                parseGoogleResponse(text, jsonStr)
            }
        } catch (e: Exception) {
            WordEntry(text, "", "네트워크 오류: ${e.message}", "", "")
        }
    }

    private fun parseGoogleResponse(query: String, json: String): WordEntry {
        val root = MiniJson.parse(json) as? List<*> ?: return WordEntry(query, "", "결과 없음", "", "")

        // Meaning from bilingual dictionary (dt=bd)
        val bd = root.getOrNull(1) as? List<*>
        val senses = bd.orEmpty().mapNotNull { entry ->
            val row = entry as? List<*> ?: return@mapNotNull null
            val part = parts[(row.getOrNull(0) as? String).orEmpty().lowercase()]
            val words = (row.getOrNull(1) as? List<*>).orEmpty().mapNotNull { it as? String }
                .map { it.trim() }.distinct().take(4)
            if (words.isEmpty()) null else (if (part != null) "[$part] " else "") + words.joinToString(", ")
        }

        val koreanMeaning = if (senses.isNotEmpty()) {
            senses.mapIndexed { idx, s -> "${idx + 1}. $s" }.joinToString("\n")
        } else {
            // Fallback to translation (dt=t)
            val segments = root.firstOrNull() as? List<*>
            val translated = segments?.mapNotNull { (it as? List<*>)?.firstOrNull() as? String }?.joinToString("")?.trim().orEmpty()
            if (translated.isNotEmpty()) "1. $translated" else "의미를 찾을 수 없습니다."
        }

        return WordEntry(
            word = query,
            ipa = "",
            korean = koreanMeaning,
            english = "Google Dictionary / Translation",
            examples = ""
        )
    }
}
