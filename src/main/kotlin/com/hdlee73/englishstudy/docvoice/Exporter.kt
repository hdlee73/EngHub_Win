package com.hdlee73.englishstudy.docvoice

data class Segment(val start: Double, val end: Double, val text: String)

data class Sentence(
    val start: Double,
    val end: Double,
    val text: String,
    var speaker: Int? = null
)

object Segmenter {
    private val TERMINATORS = ".?!。！？…"
    private val CLOSERS = "\"'”’)]»"

    /** 문장 분리 */
    fun splitSentences(text: String): List<String> {
        val regex = Regex("(?<=[.?!。！？])\\s+")
        return text.split(regex).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Whisper 또는 오디오 세그먼트로부터 하나의 온전한 문장 목록을 만듭니다.
     * 동일 문장이 여러 세그먼트로 쪼개져 있는 경우, 중간 타임이 끊기지 않도록 문장이 끝날 때까지 병합합니다.
     */
    fun buildSentences(segments: List<Segment>): List<Sentence> {
        if (segments.isEmpty()) return emptyList()
        val sentences = mutableListOf<Sentence>()

        var currentStart = -1.0
        var currentEnd = -1.0
        val currentText = StringBuilder()

        for (seg in segments) {
            val text = seg.text.trim()
            if (text.isEmpty()) continue

            if (currentStart < 0.0) {
                currentStart = seg.start
            }
            currentEnd = maxOf(currentEnd, seg.end)
            if (currentText.isNotEmpty()) currentText.append(" ")
            currentText.append(text)

            val endsWithTerminator = text.any { it in TERMINATORS }
            if (endsWithTerminator) {
                sentences.add(Sentence(currentStart, currentEnd, currentText.toString()))
                currentStart = -1.0
                currentEnd = -1.0
                currentText.clear()
            }
        }

        if (currentText.isNotEmpty()) {
            sentences.add(Sentence(currentStart.coerceAtLeast(0.0), currentEnd.coerceAtLeast(currentStart), currentText.toString()))
        }

        return sentences
    }

    fun fmtTime(seconds: Double): String {
        val ms = (seconds * 1000).toLong().coerceAtLeast(0L)
        val m = ms / 60000
        val s = (ms % 60000) / 1000
        val c = (ms % 1000) / 10
        return "%02d:%02d.%02d".format(m, s, c)
    }
}

object Exporter {
    fun srtTime(seconds: Double): String {
        val ms = (seconds * 1000).toLong().coerceAtLeast(0L)
        return String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
    }

    /**
     * 자막(.srt) 생성:
     * [요구사항 (2) 반영]
     * - 하나의 문장임에도 타임이 끊기는 현상을 방지하여, 동일한 문장은 동일한 타임랩([s.start --> s.end])을
     *   온전히 유지하고 내부 텍스트를 줄바꿈 포맷팅하여 자막 파일로 출력합니다.
     */
    fun srt(sentences: List<Sentence>, showSpeaker: Boolean = false, maxCharsPerLine: Int = 45): String {
        val sb = StringBuilder()
        var index = 1

        for (i in sentences.indices) {
            val s = sentences[i]
            val nextStart = sentences.getOrNull(i + 1)?.start ?: Double.MAX_VALUE
            // 동일 문장에 대해 온전하고 일관된 타임랩 유지
            val startTime = s.start
            val endTime = minOf(maxOf(s.end, s.start + 0.8), maxOf(nextStart, s.start + 0.5))

            val spk = s.speaker
            val speakerPrefix = if (showSpeaker && spk != null) "화자 ${spk + 1}: " else ""
            val fullText = speakerPrefix + s.text.trim()

            // 가독성을 위한 단어 기반 줄바꿈
            val words = fullText.split(Regex("\\s+"))
            val lines = mutableListOf<String>()
            var curLine = StringBuilder()

            for (w in words) {
                if (curLine.isNotEmpty() && curLine.length + 1 + w.length > maxCharsPerLine) {
                    lines.add(curLine.toString())
                    curLine = StringBuilder()
                }
                if (curLine.isNotEmpty()) curLine.append(' ')
                curLine.append(w)
            }
            if (curLine.isNotEmpty()) lines.add(curLine.toString())

            // 자막 블록 생성 (동일 문장은 단일 타임랩)
            sb.append(index++).append("\n")
            sb.append(srtTime(startTime)).append(" --> ").append(srtTime(endTime)).append("\n")
            sb.append(lines.joinToString("\n")).append("\n\n")
        }

        return sb.toString()
    }
}
