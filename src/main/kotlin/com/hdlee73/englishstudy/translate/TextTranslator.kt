package com.hdlee73.englishstudy.translate

import com.hdlee73.englishstudy.dictionary.MiniJson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

object TextTranslator {
    private val client = OkHttpClient()

    fun translate(text: String, from: String = "auto", to: String = "ko"): String {
        if (text.isBlank()) return ""
        return try {
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$from&tl=$to&dt=t&q=" +
                    URLEncoder.encode(text.trim(), "UTF-8")
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return "번역 서버 응답 실패 (${resp.code})"
                val json = resp.body?.string().orEmpty()
                val root = MiniJson.parse(json) as? List<*> ?: return "응답 형식 오류"
                val segments = root.firstOrNull() as? List<*>
                val translated = segments?.mapNotNull { (it as? List<*>)?.firstOrNull() as? String }?.joinToString("")?.trim().orEmpty()
                if (translated.isNotEmpty()) translated else "번역 결과를 찾을 수 없습니다."
            }
        } catch (e: Exception) {
            "번역 오류: ${e.message}"
        }
    }
}
