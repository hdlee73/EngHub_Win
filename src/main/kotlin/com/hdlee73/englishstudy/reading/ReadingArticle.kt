package com.hdlee73.englishstudy.reading

data class ReadingArticle(
    val id: String,
    val topic: String,
    val title: String,
    val paragraphs: List<String>
) {
    val wordCount: Int get() = paragraphs.sumOf { p -> p.split(Regex("\\s+")).count { it.isNotBlank() } }
    val minutes: Int get() = maxOf(1, (wordCount + 74) / 150)
}

object ReadingLibrary {
    fun parse(text: String): List<ReadingArticle> {
        val out = ArrayList<ReadingArticle>()
        var topic = ""
        var title = ""
        var paragraphs = ArrayList<String>()
        fun flush() {
            if (title.isNotEmpty() && paragraphs.isNotEmpty()) {
                out += ReadingArticle("a" + (out.size + 1).toString().padStart(3, '0'), topic, title, paragraphs)
            }
            title = ""; topic = ""; paragraphs = ArrayList()
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("###")) {
                flush()
                val head = line.removePrefix("###").trim()
                topic = head.substringBefore('|', "").trim()
                title = head.substringAfter('|', head).trim()
            } else if (line.isNotEmpty() && title.isNotEmpty()) {
                paragraphs += line
            }
        }
        flush()
        return out
    }
}
