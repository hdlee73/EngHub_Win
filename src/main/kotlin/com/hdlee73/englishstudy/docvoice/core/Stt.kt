package com.hdlee73.englishstudy.docvoice.core

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

data class SttOptions(
    val language: String = "",          // "" = 자동 감지, "ko", "en"
    val size: WhisperSize = WhisperSize.SMALL,
    val diarize: Boolean = true,
)

class SttResult(
    val segments: List<Segment>,
    val turns: List<Triple<Double, Double, Int>>,
    val diarizationNote: String? = null,
)

/** 온디바이스 받아쓰기 (Windows SAPI SpeechRecognitionEngine / Whisper). */
object Stt {
    const val MAX_DIARIZE_SECONDS = 50 * 60

    fun transcribe(
        store: ModelStore,
        pcm: PcmSource,
        opts: SttOptions,
        isActive: () -> Boolean,
        onStage: (String, Float) -> Unit,
    ): SttResult {
        onStage("받아쓰는 중", 0.05f)
        val total = pcm.size
        val floats = pcm.toFloats(0, total)
        val shorts = ShortArray(total) { i -> (floats[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
        val tmpWav = File.createTempFile("enghub_stt_", ".wav")
        try {
            tmpWav.writeBytes(Wav.encode(shorts, total, AudioDecoder.SAMPLE_RATE))
            val culture = if (opts.language == "ko") "ko-KR" else "en-US"
            val escapedPath = tmpWav.absolutePath.replace("'", "''")
            val psScript = """
                [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                Add-Type -AssemblyName System.Speech
                ${'$'}culture = try { [System.Globalization.CultureInfo]::GetCultureInfo('$culture') } catch { [System.Globalization.CultureInfo]::InvariantCulture }
                ${'$'}rec = try { New-Object System.Speech.Recognition.SpeechRecognitionEngine(${'$'}culture) } catch { New-Object System.Speech.Recognition.SpeechRecognitionEngine }
                ${'$'}rec.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))
                ${'$'}rec.SetInputToWaveFile('$escapedPath')
                while (${'$'}true) {
                    ${'$'}r = ${'$'}rec.Recognize()
                    if (${'$'}null -eq ${'$'}r) { break }
                    ${'$'}s = ${'$'}r.Audio.AudioPosition.TotalSeconds
                    ${'$'}d = ${'$'}r.Audio.Duration.TotalSeconds
                    ${'$'}e = ${'$'}s + ${'$'}d
                    ${'$'}t = ${'$'}r.Text.Trim()
                    if (${'$'}t.Length -gt 0) {
                        Write-Output ("{0:F3}|{1:F3}|{2}" -f ${'$'}s, ${'$'}e, ${'$'}t)
                    }
                }
                ${'$'}rec.Dispose()
            """.trimIndent()
            val proc = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", psScript)
                .redirectErrorStream(false)
                .start()
            val segments = ArrayList<Segment>()
            proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (!isActive()) {
                        proc.destroyForcibly()
                        throw kotlinx.coroutines.CancellationException("취소됨")
                    }
                    val parts = line.split('|', limit = 3)
                    if (parts.size == 3) {
                        val start = parts[0].toDoubleOrNull() ?: 0.0
                        val end = parts[1].toDoubleOrNull() ?: (start + 1.0)
                        val text = parts[2].trim()
                        if (text.isNotEmpty()) {
                            val punctText = if (text.last() in ".!?。！？") text else "$text."
                            segments.add(Segment(start, maxOf(end, start + 0.3), punctText))
                            if (pcm.seconds > 0) {
                                onStage("받아쓰는 중", (end / pcm.seconds).toFloat().coerceIn(0.05f, 0.99f))
                            }
                        }
                    }
                }
            }
            proc.waitFor()
            onStage("받아쓰는 중", 1f)
            return SttResult(segments, emptyList(), null)
        } finally {
            tmpWav.delete()
        }
    }

    fun newRecognizer(store: ModelStore, opts: SttOptions, threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)): OfflineRecognizer {
        val wf = store.whisper(opts.size)
        return OfflineRecognizer(
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = wf.encoder.path,
                        decoder = wf.decoder.path,
                        language = opts.language,
                        task = "transcribe",
                        tailPaddings = 1000,
                    ),
                    tokens = wf.tokens.path,
                    numThreads = threads,
                    provider = "cpu",
                ),
            )
        )
    }

    fun newLive(store: ModelStore, lang: LiveLang): OnlineRecognizer {
        val f = store.live(lang)
        return OnlineRecognizer(
            config = OnlineRecognizerConfig(
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(encoder = f.encoder.path, decoder = f.decoder.path, joiner = f.joiner.path),
                    tokens = f.tokens.path,
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                    provider = "cpu",
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.0f, 0.0f),
                    rule2 = EndpointRule(true, 0.8f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 15.0f),
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
            )
        )
    }

    fun newVad(store: ModelStore): Vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = store.vadFile.path,
                threshold = 0.5f,
                minSilenceDuration = 0.4f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 25f,
            ),
            sampleRate = AudioDecoder.SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
        )
    )

    fun decodeSegment(recognizer: OfflineRecognizer, samples: FloatArray, startSample: Int): Segment? {
        val sr = AudioDecoder.SAMPLE_RATE
        val start = startSample / sr.toDouble()
        val dur = samples.size / sr.toDouble()
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sr)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text.trim()
            if (text.isEmpty()) return null
            if (Segmenter.englishWordCount(text) + (if (Segmenter.hasHangul(text)) 1 else 0) == 0) return null
            return Segment(start, start + dur, text)
        } finally {
            stream.release()
        }
    }

    fun diarize(store: ModelStore, pcm: PcmSource, threads: Int): List<Triple<Double, Double, Int>> {
        val sd = OfflineSpeakerDiarization(
            config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = store.segFile.path),
                    numThreads = threads,
                    provider = "cpu",
                ),
                embedding = SpeakerEmbeddingExtractorConfig(model = store.embFile.path, numThreads = threads, provider = "cpu"),
                clustering = FastClusteringConfig(numClusters = -1, threshold = 0.5f),
                minDurationOn = 0.3f,
                minDurationOff = 0.5f,
            )
        )
        try {
            val result = sd.process(pcm.toFloats())
            return result.map { Triple(it.start.toDouble(), it.end.toDouble(), it.speaker) }
        } finally {
            sd.release()
        }
    }

    /** 받아쓰기 결과 → 문장 → (화자 매핑) → 문단 */
    fun toDocument(r: SttResult, gap: Double): Pair<List<Sentence>, List<Paragraph>> {
        val sentences = Segmenter.buildSentences(r.segments)
        if (r.turns.isNotEmpty()) Segmenter.assignSpeakers(sentences, r.turns)
        val paragraphs = Segmenter.groupParagraphs(sentences, gap = gap, useSpeaker = r.turns.isNotEmpty())
        return sentences to paragraphs
    }
}
