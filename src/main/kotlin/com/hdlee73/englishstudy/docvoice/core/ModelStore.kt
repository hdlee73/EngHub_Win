package com.hdlee73.englishstudy.docvoice.core

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 받아쓰기 정확도 선택 (Whisper int8). */
enum class WhisperSize(val key: String, val label: String, val hint: String) {
    BASE("base", "빠름", "base · 약 150MB"),
    SMALL("small", "정확 (권장)", "small · 약 370MB"),
    /** English-only small model: more accurate than the multilingual small one on English speech (e.g. Listening subtitles), same size. */
    SMALL_EN("small.en", "영어 전용 정확", "small.en · 약 370MB");

    val englishOnly: Boolean get() = key.endsWith(".en")
}

/** 실시간(스트리밍) 인식 언어 */
enum class LiveLang(val key: String, val label: String, val archive: String, val mb: Int) {
    EN("en", "English", "sherpa-onnx-streaming-zipformer-en-2023-06-26", 300),
    KO("ko", "한국어", "sherpa-onnx-streaming-zipformer-korean-2024-06-16", 400),
    MIX("mix", "한·영", "", 1100);

    /** 실제로 필요한 단일 언어 모델들 */
    val members: List<LiveLang> get() = if (this == MIX) listOf(EN, KO) else listOf(this)

    companion object {
        fun of(key: String) = values().firstOrNull { it.key == key } ?: EN
    }
}

class ModelException(message: String) : Exception(message)

/** 음성 인식 · VAD · 화자 구분 모델을 앱 저장공간에 내려받아 보관한다 (최초 1회, 이후 오프라인 사용). */
class ModelStore(context: Context) {
    private val root = File(context.filesDir, "models").apply { mkdirs() }
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val HF = "https://huggingface.co/csukuangfj"
        private const val GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
        const val VAD_URL = "$GH/asr-models/silero_vad.onnx"
        const val SEG_URL = "$GH/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"
        const val EMB_URL = "$GH/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
    }

    class WhisperFiles(val encoder: File, val decoder: File, val tokens: File)

    fun whisper(size: WhisperSize): WhisperFiles {
        val d = File(root, "whisper-${size.key}")
        return WhisperFiles(File(d, "encoder.onnx"), File(d, "decoder.onnx"), File(d, "tokens.txt"))
    }

    class LiveFiles(val encoder: File, val decoder: File, val joiner: File, val tokens: File)

    fun live(l: LiveLang): LiveFiles {
        val d = File(root, "live-${l.key}")
        return LiveFiles(File(d, "encoder.onnx"), File(d, "decoder.onnx"), File(d, "joiner.onnx"), File(d, "tokens.txt"))
    }

    // ---- sherpa-onnx 모델: Windows 셰임에는 엔진이 없다 --------------------------------
    // 이 프로젝트의 sherpa-onnx 셰임(com/k2fsa/sherpa/onnx)은 아무것도 하지 않는다. 그래서 스트리밍
    // 인식·Whisper·화자 구분 모델은 내려받아도 쓰이지 않는다. 수백 MB 를 헛되이 받지 않도록 아래
    // ensure* 는 아무 일도 하지 않고, liveReady 는 항상 false 다. 받아쓰기는 Windows 음성 인식기(SAPI)
    // 를 쓰는 Stt.transcribe 가 담당한다 (파일 변환·녹음 문서화 모두 같은 경로).

    fun liveReady(l: LiveLang): Boolean = false

    val vadFile get() = File(root, "silero_vad.onnx")
    val segFile get() = File(root, "diar/segmentation.onnx")
    val embFile get() = File(root, "diar/embedding.onnx")

    fun whisperReady(size: WhisperSize) = whisper(size).let { it.encoder.exists() && it.decoder.exists() && it.tokens.exists() }
    fun vadReady() = vadFile.exists()
    fun diarReady() = segFile.exists() && embFile.exists()

    /** 이미 있으면 즉시 반환, 없으면 내려받는다. onProgress(설명, 0..1) */
    fun ensureWhisper(size: WhisperSize, isActive: () -> Boolean, onProgress: (String, Float) -> Unit) {
        // Whisper 셰임(OfflineRecognizer)이 동작하지 않으므로 모델을 받지 않는다 (위 주석 참고).
    }

    fun ensureVad(isActive: () -> Boolean, onProgress: (String, Float) -> Unit) {
        // VAD 셰임이 동작하지 않으므로 모델을 받지 않는다 (위 주석 참고).
    }

    fun ensureDiar(isActive: () -> Boolean, onProgress: (String, Float) -> Unit) {
        // 화자 구분 셰임이 동작하지 않으므로 모델을 받지 않는다 (위 주석 참고).
    }

    private fun download(url: String, dest: File, isActive: () -> Boolean, onFraction: (Float) -> Unit) {
        if (dest.exists() && dest.length() > 0) { onFraction(1f); return }
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        try {
            http.newCall(Request.Builder().url(url).header("User-Agent", "DocVoice-Android").build()).execute().use { resp ->
                if (!resp.isSuccessful) throw ModelException("모델 내려받기 실패 (HTTP ${resp.code}). 인터넷 연결을 확인하세요.")
                val body = resp.body ?: throw ModelException("빈 응답입니다.")
                val total = body.contentLength()
                var done = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (!isActive()) throw kotlinx.coroutines.CancellationException("취소됨")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onFraction((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (total > 0 && done != total) throw ModelException("내려받기가 중간에 끊겼습니다.")
            }
            if (!part.renameTo(dest)) throw ModelException("모델 파일을 저장하지 못했습니다.")
            onFraction(1f)
        } catch (e: IOException) {
            part.delete()
            throw ModelException("모델 내려받기 실패: ${e.message}")
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }
}
