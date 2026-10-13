package com.hdlee73.englishstudy

import com.hdlee73.englishstudy.docvoice.core.Exporter
import com.hdlee73.englishstudy.docvoice.core.Segment
import com.hdlee73.englishstudy.docvoice.core.Segmenter
import com.hdlee73.englishstudy.docvoice.core.Sentence
import com.hdlee73.englishstudy.listening.PlaybackService
import com.hdlee73.englishstudy.listening.SubtitleParser
import com.hdlee73.englishstudy.listening.TrackStore
import com.hdlee73.englishstudy.phrases.PhraseBank
import com.hdlee73.englishstudy.speaking.speech.RetryEvaluator
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

class EngHubWinHarnessTest {

    private fun createTempWavFile(durationSeconds: Int = 20): File {
        val sampleRate = 16000
        val totalSamples = sampleRate * durationSeconds
        val pcm = ByteArray(totalSamples * 2)
        val fmt = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
        val bos = ByteArrayOutputStream()
        AudioInputStream(pcm.inputStream(), fmt, totalSamples.toLong()).use { ais ->
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, bos)
        }
        val file = File.createTempFile("enghub_test_", ".wav")
        file.deleteOnExit()
        file.writeBytes(bos.toByteArray())
        return file
    }

    @Test
    fun testModification1_ContinuousPlaybackAcrossTabs() {
        val svc1 = PlaybackService.instance
        val svc2 = PlaybackService.instance
        assertSame("PlaybackService must be a process-wide singleton across tabs", svc1, svc2)

        val wav = createTempWavFile(durationSeconds = 15)
        val entry = TrackStore.Entry(wav.toURI().toString(), "test_lesson.wav")
        svc1.player.setMediaItems(listOf(entry), 0, 4200L)
        svc1.player.prepare()

        assertEquals("Duration should be ~15000ms", 15000L, svc1.player.duration)
        assertEquals("Initial position should be preserved", 4200L, svc1.player.currentPosition)

        // Simulate navigating away from Listening tab to Dictionary/Reading/Speaking and back:
        // PlaybackService.instance remains alive and keeps its queue & position without resetting.
        assertEquals(1, PlaybackService.instance.player.mediaItemCount)
        assertEquals("test_lesson.wav", PlaybackService.instance.currentTitle)
        assertEquals(4200L, PlaybackService.instance.player.currentPosition)
    }

    @Test
    fun testModification2_UnifiedSentenceTimestampsInSrt() {
        // Case A: A single sentence broken across multiple Whisper VAD segments with a 1.2s mid-sentence pause
        val segments = listOf(
            Segment(1.0, 3.5, "When we are learning a new language,"),
            Segment(4.7, 8.2, "consistent listening practice every single day"),
            Segment(8.8, 12.4, "makes a huge difference in fluency and comprehension.")
        )

        val sentences = Segmenter.buildSentences(segments)
        assertEquals(
            "Mid-sentence segments without terminal punctuation must be merged into a single Sentence",
            1,
            sentences.size
        )
        assertEquals(1.0, sentences[0].start, 0.01)
        assertEquals(12.4, sentences[0].end, 0.01)

        // Case B: Exporting a long multi-clause sentence (> 120 chars) to .srt must NOT split it into multiple cues
        val srtBytes = Exporter.srt(sentences)
        val srtText = String(srtBytes, Charsets.UTF_8)
        val parsedCues = SubtitleParser.parse(srtText)

        assertEquals(
            "A single sentence must produce exactly 1 unified SRT cue regardless of character length",
            1,
            parsedCues.size
        )
        assertEquals(1000L, parsedCues[0].startMs)
        assertEquals(12400L, parsedCues[0].endMs)
        assertEquals(
            "When we are learning a new language, consistent listening practice every single day makes a huge difference in fluency and comprehension.",
            parsedCues[0].text
        )
    }

    @Test
    fun testModification3_ResumeAtWordCueTimestampAfterDictionaryLookup() {
        val svc = PlaybackService.instance
        val wav = createTempWavFile(durationSeconds = 30)
        val entry = TrackStore.Entry(wav.toURI().toString(), "bbc_6min.wav")
        svc.player.setMediaItems(listOf(entry), 0, 0L)
        svc.player.prepare()

        val srt = """
            1
            00:00:02,000 --> 00:00:07,500
            Hello and welcome to Six Minute English.

            2
            00:00:14,250 --> 00:00:19,800
            Today we are talking about extraordinary resilience in everyday life.
        """.trimIndent()
        val cues = SubtitleParser.parse(srt)
        assertEquals(2, cues.size)

        // User is reading cue #2 and selects the word "resilience" to look up in the Dictionary
        val selectedCue = cues[1]
        assertTrue(selectedCue.text.contains("resilience"))
        val recordedReturnPositionMs = selectedCue.startMs
        assertEquals(14250L, recordedReturnPositionMs)

        // When user returns from Dictionary to Listening tab, player seeks to recordedReturnPositionMs
        svc.player.seekTo(recordedReturnPositionMs)
        assertEquals(
            "Player must resume at the exact timestamp of the subtitle cue where the word was selected",
            14250L,
            svc.player.currentPosition
        )
    }

    @Test
    fun testBundledAssetsAndCoreModules() {
        val app = android.app.Application.instance
        assertTrue("common_words.txt should be accessible", app.assets.open("common_words.txt").use { it.readBytes().isNotEmpty() })
        assertTrue("phrases_us_uk.txt should be accessible", app.assets.open("phrases_us_uk.txt").use { it.readBytes().isNotEmpty() })

        val phrases = app.assets.open("phrases_us_uk.txt").bufferedReader(Charsets.UTF_8).use {
            PhraseBank.parse(it.readText())
        }
        assertTrue("PhraseBank should parse US/UK phrases", phrases.isNotEmpty())

        val eval = RetryEvaluator.evaluate(
            expected = "I would like a cup of coffee",
            candidates = listOf("I would like a cup of coffee"),
            confirmed = emptyList()
        )
        assertTrue("All words should be matched in RetryEvaluator", eval.matched.all { it })
    }
}
