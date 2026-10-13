package android.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

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
}

class SpeechRecognizer private constructor(private val context: Context) {
    companion object {
        const val RESULTS_RECOGNITION = "results_recognition"
        const val ERROR_INSUFFICIENT_PERMISSIONS = 9
        const val ERROR_NO_MATCH = 7

        fun isRecognitionAvailable(context: Context): Boolean = true
        fun createSpeechRecognizer(context: Context): SpeechRecognizer = SpeechRecognizer(context)
    }

    private var listener: RecognitionListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var activeProc: Process? = null
    @Volatile private var cancelled = false

    fun setRecognitionListener(listener: RecognitionListener?) {
        this.listener = listener
    }

    fun startListening(recognizerIntent: Intent) {
        cancel()
        cancelled = false
        val lang = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE) ?: "en-US"
        Thread({
            try {
                mainHandler.post {
                    if (!cancelled) listener?.onReadyForSpeech(Bundle())
                }
                val psScript = """
                    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                    Add-Type -AssemblyName System.Speech
                    ${'$'}culture = try { [System.Globalization.CultureInfo]::GetCultureInfo('$lang') } catch { [System.Globalization.CultureInfo]::InvariantCulture }
                    ${'$'}rec = try { New-Object System.Speech.Recognition.SpeechRecognitionEngine(${'$'}culture) } catch { New-Object System.Speech.Recognition.SpeechRecognitionEngine }
                    ${'$'}rec.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))
                    ${'$'}rec.SetInputToDefaultAudioDevice()
                    ${'$'}res = ${'$'}rec.Recognize([TimeSpan]::FromSeconds(7))
                    if (${'$'}null -ne ${'$'}res -and ${'$'}res.Text) {
                        Write-Output ${'$'}res.Text
                        foreach (${'$'}alt in ${'$'}res.Alternates) {
                            if (${'$'}alt.Text -ne ${'$'}res.Text) { Write-Output ${'$'}alt.Text }
                        }
                    }
                    ${'$'}rec.Dispose()
                """.trimIndent()
                val proc = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", psScript)
                    .redirectErrorStream(false)
                    .start()
                activeProc = proc
                val lines = proc.inputStream.bufferedReader(Charsets.UTF_8).readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                proc.waitFor()
                if (cancelled) return@Thread
                mainHandler.post {
                    if (cancelled) return@post
                    val bundle = Bundle().apply {
                        putStringArrayList(RESULTS_RECOGNITION, ArrayList(lines))
                    }
                    listener?.onResults(bundle)
                }
            } catch (_: Throwable) {
                if (!cancelled) {
                    mainHandler.post { listener?.onError(ERROR_NO_MATCH) }
                }
            }
        }, "EngHub-SpeechRecognizer").apply { isDaemon = true; start() }
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
