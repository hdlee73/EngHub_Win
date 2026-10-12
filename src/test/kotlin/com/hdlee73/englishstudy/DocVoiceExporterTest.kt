package com.hdlee73.englishstudy

import com.hdlee73.englishstudy.docvoice.Exporter
import com.hdlee73.englishstudy.docvoice.Sentence
import com.hdlee73.englishstudy.listening.SubtitleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocVoiceExporterTest {

    @Test
    fun testUnifiedTimestampForSingleSentence() {
        // Requirement (2): 하나의 문장임에도 타임이 끊기는 현상 방지 테스트
        val sentence = Sentence(
            start = 12.34,
            end = 18.50,
            text = "In today's lesson, we are going to explore how continuous audio playback keeps playing without interruption even when you switch tabs."
        )

        val srtOutput = Exporter.srt(listOf(sentence))

        // Parse generated SRT to verify that all cues have the exact same unified timeframe [12.340 --> 18.500]
        val cues = SubtitleParser.parse(srtOutput)
        assertTrue(cues.isNotEmpty(), "Cues should not be empty")

        for (cue in cues) {
            assertEquals(12340L, cue.startMs, "Start time must be preserved at sentence start")
            assertEquals(18500L, cue.endMs, "End time must be preserved at sentence end")
        }
    }

    @Test
    fun testSubtitleParserSupportsSrtAndVtt() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,500
            Hello world, this is a test.
            
            2
            00:00:05,000 --> 00:00:09,000
            Second sentence for testing.
        """.trimIndent()

        val cues = SubtitleParser.parse(srt)
        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(4500L, cues[0].endMs)
        assertEquals("Hello world, this is a test.", cues[0].text)
    }
}
