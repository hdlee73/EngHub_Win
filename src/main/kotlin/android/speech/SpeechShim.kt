package android.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

interface RecognitionListener {
    fun onReadyForSpeech(params: Bundle?)
    fun onBeginningOfSpeech()
    fun onRmsChanged(rmsdB: Float)
    fun onBufferReceived(buffer: ByteArray?)
    fun onEndOfSpeech()
    fun onError(error: Int)
    fun onResults(results: Bundle)
    fun onPartialResults(partialResults: Bundle?)
    fun onSegmentResults(segmentResults: Bundle) {}
    fun onEvent(eventType: Int, params: Bundle?)
}

object RecognizerIntent {
    const val ACTION_RECOGNIZE_SPEECH = "android.speech.action.RECOGNIZE_SPEECH"
    const val EXTRA_LANGUAGE_MODEL = "android.speech.extra.LANGUAGE_MODEL"
    const val LANGUAGE_MODEL_FREE_FORM = "free_form"
    const val EXTRA_LANGUAGE = "android.speech.extra.LANGUAGE"
    const val EXTRA_LANGUAGE_PREFERENCE = "android.speech.extra.LANGUAGE_PREFERENCE"
    const val EXTRA_PARTIAL_RESULTS = "android.speech.extra.PARTIAL_RESULTS"
    const val EXTRA_PREFER_OFFLINE = "android.speech.extra.PREFER_OFFLINE"
    const val EXTRA_MAX_RESULTS = "android.speech.extra.MAX_RESULTS"
    const val EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS = "android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS"
    const val EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS = "android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS"
    const val EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS = "android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS"
    const val EXTRA_BIASING_STRINGS = "android.speech.extra.BIASING_STRINGS"
    const val EXTRA_ENABLE_FORMATTING = "android.speech.extra.ENABLE_FORMATTING"
    const val FORMATTING_OPTIMIZE_QUALITY = "quality"
    const val EXTRA_HIDE_PARTIAL_TRAILING_PUNCTUATION = "android.speech.extra.HIDE_PARTIAL_TRAILING_PUNCTUATION"
    const val EXTRA_AUDIO_SOURCE = "android.speech.extra.AUDIO_SOURCE"
    const val EXTRA_AUDIO_SOURCE_CHANNEL_COUNT = "android.speech.extra.AUDIO_SOURCE_CHANNEL_COUNT"
    const val EXTRA_AUDIO_SOURCE_ENCODING = "android.speech.extra.AUDIO_SOURCE_ENCODING"
    const val EXTRA_AUDIO_SOURCE_SAMPLING_RATE = "android.speech.extra.AUDIO_SOURCE_SAMPLING_RATE"

    /**
     * Windows 확장: 인식에 사용할 입력 장치(JavaSound 믹서 이름). 비어 있으면 Windows 기본 입력 장치.
     * 앱이 설정에서 고른 마이크(블루투스 이어셋 포함)를 그대로 인식에 쓰기 위해 SpeechEngine 이 함께 넘긴다.
     */
    const val EXTRA_WINDOWS_INPUT_DEVICE = "enghub.input_device"
}

class SpeechRecognizer private constructor(private val context: Context) {
    companion object {
        const val RESULTS_RECOGNITION = "results_recognition"
        const val ERROR_INSUFFICIENT_PERMISSIONS = 9
        const val ERROR_NO_MATCH = 7

        /** 이 시간(ms) 동안 소리가 없으면 한 문장이 끝난 것으로 본다. */
        private const val DEFAULT_END_SILENCE_MS = 800

        fun isRecognitionAvailable(context: Context): Boolean = SpeechEngine.isInstalled
        fun createSpeechRecognizer(context: Context): SpeechRecognizer = SpeechRecognizer(context)
    }

    private var listener: RecognitionListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var activeProc: Process? = null
    @Volatile private var cancelled = false

    fun setRecognitionListener(listener: RecognitionListener?) {
        this.listener = listener
    }

    /**
     * Windows 구현: **앱이 고른 마이크에서 직접** 소리를 받아(블루투스 이어셋 마이크 포함) 한 문장이 끝나면
     * WAV 로 만들어 System.Speech 로 받아쓴다.
     *
     * 예전에는 System.Speech 의 `SetInputToDefaultAudioDevice()` 만 썼는데, 그러면 Windows 기본 입력 장치만
     * 듣게 되어 설정에서 블루투스 이어셋을 골라도 그 마이크로 한 말은 인식되지 않았다(사용자 신고).
     * 반대로 System.Speech 에 실시간 스트림을 직접 물리면(SetInputToAudioStream) 타임아웃 때문에 즉시 끝나
     * 버리는 것도 확인해서, "직접 캡처 + 발화 끝 감지 + 파일 인식" 조합으로 바꿨다.
     * 앱이 이미 캡처 파이프를 넘겨 준 경우(녹음/야외 입력)에는 그 파이프를 입력으로 쓴다.
     */
    fun startListening(recognizerIntent: Intent) {
        cancel()
        cancelled = false
        val lang = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE) ?: "en-US"
        val pipe = recognizerIntent.getParcelableExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE) as? android.os.ParcelFileDescriptor
        val inputDevice = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_WINDOWS_INPUT_DEVICE).orEmpty()
        val endSilence = (recognizerIntent.getIntExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 0)
            .takeIf { it > 0 }
            ?: recognizerIntent.getIntExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 0)
                .takeIf { it > 0 }
            ?: DEFAULT_END_SILENCE_MS).coerceIn(200, 5000)
        // Expected-sentence biasing (EXTRA_BIASING_STRINGS): load the phrases as an extra grammar so the engine
        // prefers them. Wrapped in try/catch in the script so a failure never breaks plain dictation.
        val biasing = recognizerIntent.getStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS)
        val biasLine = if (!biasing.isNullOrEmpty()) {
            val array = biasing.take(24).joinToString(",") { "'" + it.replace("'", "''") + "'" }
            """
            try {
                ${'$'}bias = @($array)
                ${'$'}choices = New-Object System.Speech.Recognition.Choices -ArgumentList (,${'$'}bias)
                ${'$'}rec.LoadGrammar((New-Object System.Speech.Recognition.Grammar -ArgumentList ${'$'}choices))
            } catch {}
            """.trimIndent() + "\n"
        } else ""
        Thread({
            try {
                mainHandler.post {
                    if (!cancelled) listener?.onReadyForSpeech(Bundle())
                }
                val pcm = runCatching { captureUtterance(pipe, inputDevice, endSilence) }.getOrDefault(ByteArray(0))
                if (cancelled) return@Thread
                if (pcm.isEmpty()) {
                    mainHandler.post {
                        if (cancelled) return@post
                        listener?.onResults(Bundle().apply { putStringArrayList(RESULTS_RECOGNITION, ArrayList()) })
                    }
                    return@Thread
                }
                val lines = runCatching { recognizePcm(pcm, lang, biasLine) }.getOrDefault(emptyList())
                if (cancelled) return@Thread
                mainHandler.post {
                    if (cancelled) return@post
                    listener?.onResults(Bundle().apply { putStringArrayList(RESULTS_RECOGNITION, ArrayList(lines)) })
                }
            } catch (_: Throwable) {
                if (!cancelled) {
                    mainHandler.post { listener?.onError(ERROR_NO_MATCH) }
                }
            }
        }, "EngHub-SpeechRecognizer").apply { isDaemon = true; start() }
    }

    /**
     * 한 문장 분량의 PCM(16kHz 모노 16bit)을 모은다. 앱이 넘긴 [pipe] 가 있으면 그걸 쓰고, 없으면 설정에서
     * 고른 장치([deviceName], JavaSound 믹서 이름)를 직접 연다. 블루투스 이어셋은 캡처를 열고 약 2초 뒤에야
     * 소리가 들어오므로, 발화 시작을 최대 8초까지 기다린다.
     *
     * 발화가 시작된 뒤 [endSilenceMs] 만큼 조용하면 문장이 끝난 것으로 보고, 끝부분의 여운만 남기고 잘라 낸다
     * (긴 무음이 섞이면 인식 정확도가 떨어진다). 소리가 전혀 없으면 빈 배열을 돌려준다.
     */
    private fun captureUtterance(pipe: android.os.ParcelFileDescriptor?, deviceName: String, endSilenceMs: Int): ByteArray {
        val fromApp = pipe != null
        var line: javax.sound.sampled.TargetDataLine? = null
        var stream: InputStream? = null
        try {
            val readBlock: (ByteArray) -> Int
            if (fromApp) {
                stream = android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe!!)
                val input = stream
                readBlock = { buf -> input.read(buf) }
            } else {
                line = openCaptureLine(deviceName) ?: return ByteArray(0)
                line.start()
                val dataLine = line
                readBlock = { buf -> dataLine.read(buf, 0, buf.size) }
            }
            return SpeechVad.readUtterance(readBlock, endSilenceMs) { cancelled }
        } finally {
            runCatching { line?.stop() }
            runCatching { line?.close() }
            // 앱이 넘긴 파이프는 끝까지 비워 준다. 읽지 않으면 앱의 캡처 쓰레드가 막힌다.
            if (fromApp) drainQuietly(stream)
        }
    }

    /** 설정에서 고른 장치(JavaSound 믹서 이름)로 16kHz 모노 캡처 라인을 연다. 없으면 Windows 기본 장치. */
    private fun openCaptureLine(deviceName: String): javax.sound.sampled.TargetDataLine? {
        val fmt = javax.sound.sampled.AudioFormat(SpeechVad.SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val info = javax.sound.sampled.DataLine.Info(javax.sound.sampled.TargetDataLine::class.java, fmt)
        fun open(mixer: javax.sound.sampled.Mixer?): javax.sound.sampled.TargetDataLine? = runCatching {
            val line = if (mixer != null) {
                mixer.getLine(info) as javax.sound.sampled.TargetDataLine
            } else {
                javax.sound.sampled.AudioSystem.getLine(info) as javax.sound.sampled.TargetDataLine
            }
            line.open(fmt)
            line
        }.getOrNull()
        if (deviceName.isNotBlank()) {
            val mixer = runCatching { android.media.AudioDevices.mixerForName(deviceName) }.getOrNull()
            open(mixer)?.let { return it }
        } else {
            // "자동"일 때 Windows 기본 장치가 소리를 내보내지 않는 PC 가 있다(사용자 PC 의 USB 마이크).
            // 앱 시작 때 확인해 둔, 소리가 실제로 들어오는 장치를 쓴다.
            val auto = runCatching { android.media.AudioDevices.autoInputDevice() }.getOrNull()
            if (auto != null) {
                open(runCatching { android.media.AudioDevices.mixerForName(auto.address) }.getOrNull())?.let { return it }
            }
        }
        return open(null)
    }

    /** 남은 파이프 내용을 조용히 비운다(앱이 닫을 때까지). */
    private fun drainQuietly(stream: InputStream?) {
        if (stream == null) return
        Thread({
            runCatching {
                val buffer = ByteArray(4096)
                while (!cancelled && stream.read(buffer) >= 0) {
                    // Discard.
                }
            }
            runCatching { stream.close() }
        }, "EngHub-SpeechDrain").apply { isDaemon = true; start() }
    }

    /** WAV 파일을 System.Speech 로 받아쓰고 문장별 텍스트를 돌려준다. */
    private fun recognizePcm(pcm: ByteArray, lang: String, biasLine: String): List<String> {
        val wav = writeWav(pcm)
        try {
            val path = wav.absolutePath.replace("'", "''")
            val script = buildString {
                appendLine("[Console]::OutputEncoding = [System.Text.Encoding]::UTF8")
                appendLine("Add-Type -AssemblyName System.Speech")
                appendLine("\$culture = try { [System.Globalization.CultureInfo]::GetCultureInfo('$lang') } catch { [System.Globalization.CultureInfo]::InvariantCulture }")
                appendLine("\$rec = try { New-Object System.Speech.Recognition.SpeechRecognitionEngine -ArgumentList \$culture } catch { New-Object System.Speech.Recognition.SpeechRecognitionEngine }")
                appendLine("\$rec.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))")
                append(biasLine)
                appendLine("\$rec.MaxAlternates = 3")
                appendLine("\$rec.SetInputToWaveFile('$path')")
                appendLine("while (\$true) {")
                // 발화 끝은 앱이 VAD 로 이미 잘라 두었으므로 시간 제한을 걸지 않는다. 파일 입력에서
                // EndSilenceTimeout 을 걸면 인식이 시작도 못 하고 빈 결과로 끝나는 경우가 있었다.
                appendLine("  try { \$res = \$rec.Recognize() } catch { break }")
                appendLine("  if (\$null -eq \$res) { break }")
                appendLine("  if (\$res.Text) { Write-Output \$res.Text.Trim() }")
                appendLine("  foreach (\$alt in \$res.Alternates) { if (\$alt.Text -and \$alt.Text -ne \$res.Text) { Write-Output \$alt.Text.Trim() } }")
                appendLine("}")
                appendLine("\$rec.Dispose()")
            }
            val proc = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(false)
                .start()
            activeProc = proc
            val lines = proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { seq ->
                seq.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }
            proc.waitFor()
            return lines
        } finally {
            activeProc = null
            runCatching { wav.delete() }
        }
    }

    /** 16kHz 모노 16bit PCM 바이트를 WAV 파일로 만든다. */
    private fun writeWav(pcm: ByteArray): File {
        val file = File.createTempFile("enghub_utterance_", ".wav")
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcm.size)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort())
            putShort(1.toShort())
            putInt(SpeechVad.SAMPLE_RATE)
            putInt(SpeechVad.SAMPLE_RATE * 2)
            putShort(2.toShort())
            putShort(16.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcm.size)
        }.array()
        file.outputStream().use { output ->
            output.write(header)
            output.write(pcm)
        }
        return file
    }

    fun cancel() {
        cancelled = true
        runCatching { activeProc?.destroyForcibly() }
        activeProc = null
    }

    fun destroy() {
        cancel()
        listener = null
    }
}

/**
 * Windows 음성 인식 엔진(System.Speech 인식기)이 설치되어 있는지 확인한다.
 *
 * Windows 11 24H2(빌드 26100) 이후에는 레거시 음성 인식기가 기본 포함되지 않는다(사용자 PC: build 26300,
 * `InstalledRecognizers()` 0개). 이때는 발음 인식도 받아쓰기도 결과가 나오지 않으므로, 앱이 아무 말 없이
 * 실패하는 대신 원인을 알려 줄 수 있도록 미리 확인해 둔다. 확인은 PowerShell 한 번(1~2초)이라
 * [warmUp] 으로 앱 시작 직후 백그라운드에서 돌린다.
 */
object SpeechEngine {
    @Volatile private var installed: Boolean? = null

    /** 아직 확인 전이면 기능을 막지 않는다(확인 전 첫 시도에 실패 메시지를 띄우지 않기 위해). */
    val isInstalled: Boolean get() = installed ?: true

    /** 앱 시작 시 백그라운드에서 한 번 확인해 둔다. */
    fun warmUp() {
        if (installed != null) return
        Thread({ detect() }, "EngHub-SpeechEngineProbe").apply { isDaemon = true }.start()
    }

    /** 지금 바로 확인한다(진단/테스트용). 결과는 캐시된다. */
    fun detectNow(): Boolean = installed ?: detect()

    private fun detect(): Boolean {
        val found = runCatching {
            val proc = ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Add-Type -AssemblyName System.Speech; " +
                    "[System.Speech.Recognition.SpeechRecognitionEngine]::InstalledRecognizers().Count"
            ).redirectErrorStream(true).start()
            val text = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            proc.waitFor()
            val count = text.lineSequence().mapNotNull { it.trim().toIntOrNull() }.firstOrNull() ?: 0
            count > 0
        }.getOrDefault(false)
        installed = found
        return found
    }
}
