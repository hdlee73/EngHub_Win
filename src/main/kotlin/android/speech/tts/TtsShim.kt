package android.speech.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

abstract class UtteranceProgressListener {
    abstract fun onStart(utteranceId: String?)
    abstract fun onDone(utteranceId: String?)
    @Deprecated("Deprecated in Java")
    abstract fun onError(utteranceId: String?)
    open fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
}

class Voice(
    val name: String,
    val locale: Locale,
    val quality: Int = 400,
    val latency: Int = 100,
    val isNetworkConnectionRequired: Boolean = false,
    val features: Set<String> = emptySet()
)

class TextToSpeech(
    private val context: Context,
    private val initListener: OnInitListener? = null
) {
    fun interface OnInitListener {
        fun onInit(status: Int)
    }

    object Engine {
        const val KEY_PARAM_VOLUME = "volume"
        const val KEY_FEATURE_NOT_INSTALLED = "notInstalled"
        const val ACTION_INSTALL_TTS_DATA = "android.speech.tts.engine.INSTALL_TTS_DATA"
    }

    companion object {
        const val SUCCESS = 0
        const val ERROR = -1
        const val QUEUE_FLUSH = 0
        const val QUEUE_ADD = 1
        const val LANG_AVAILABLE = 0
        const val LANG_COUNTRY_AVAILABLE = 1
        const val LANG_COUNTRY_VAR_AVAILABLE = 2
        const val LANG_MISSING_DATA = -1
        const val LANG_NOT_SUPPORTED = -2
    }

    private var progressListener: UtteranceProgressListener? = null
    private var speechRate: Float = 1.0f
    private var currentLocale: Locale = Locale.US
    private val generation = AtomicInteger(0)
    @Volatile private var activeProc: Process? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "EngHub-TTS").apply { isDaemon = true }
    }

    val voices: Set<Voice> = setOf(
        Voice("en-US-Default", Locale.US, 500, 50, false),
        Voice("en-GB-Default", Locale.UK, 500, 50, false),
        Voice("ko-KR-Default", Locale.KOREA, 500, 50, false)
    )

    var voice: Voice? = voices.first()

    init {
        initListener?.let { listener ->
            SwingUtilities.invokeLater {
                try {
                    listener.onInit(SUCCESS)
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }
        }
    }

    fun setLanguage(loc: Locale?): Int {
        if (loc != null) currentLocale = loc
        return LANG_COUNTRY_AVAILABLE
    }

    fun setVoice(v: Voice): Int {
        voice = v
        currentLocale = v.locale
        return SUCCESS
    }

    fun setAudioAttributes(audioAttributes: AudioAttributes): Int = SUCCESS
    fun setSpeechRate(rate: Float): Int {
        speechRate = rate.coerceIn(0.3f, 2.5f)
        return SUCCESS
    }

    fun setPitch(pitch: Float): Int = SUCCESS

    fun setOnUtteranceProgressListener(listener: UtteranceProgressListener?): Int {
        progressListener = listener
        return SUCCESS
    }

    fun speak(
        text: CharSequence?,
        queueMode: Int,
        params: Bundle?,
        utteranceId: String?
    ): Int {
        val raw = text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return SUCCESS
        if (queueMode == QUEUE_FLUSH) {
            stopCurrentProcess()
        }
        val gen = generation.get()
        val rateVal = ((speechRate - 1.0f) * 8f).toInt().coerceIn(-6, 6)
        val langHint = if (currentLocale.language == "ko") "ko-KR" else if (currentLocale.country == "GB" || currentLocale.country == "UK") "en-GB" else "en-US"
        executor.submit {
            if (gen != generation.get()) return@submit
            progressListener?.onStart(utteranceId)
            try {
                val safeText = raw.replace("'", "''").replace("\r", " ").replace("\n", " ")
                val cmd = """
                    Add-Type -AssemblyName System.Speech
                    ${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
                    try { ${'$'}s.SelectVoiceByHints([System.Speech.Synthesis.VoiceGender]::NotSet, [System.Speech.Synthesis.VoiceAge]::NotSet, 0, [System.Globalization.CultureInfo]::GetCultureInfo('$langHint')) } catch {}
                    ${'$'}s.Rate = $rateVal
                    ${'$'}s.Speak('$safeText')
                    ${'$'}s.Dispose()
                """.trimIndent()
                val proc = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", cmd).start()
                activeProc = proc
                proc.waitFor()
                if (gen == generation.get()) {
                    progressListener?.onDone(utteranceId)
                }
            } catch (_: Throwable) {
                if (gen == generation.get()) {
                    progressListener?.onError(utteranceId)
                }
            }
        }
        return SUCCESS
    }

    fun playSilentUtterance(durationInMs: Long, queueMode: Int, utteranceId: String?): Int {
        if (queueMode == QUEUE_FLUSH) {
            stopCurrentProcess()
        }
        val gen = generation.get()
        executor.submit {
            if (gen != generation.get()) return@submit
            progressListener?.onStart(utteranceId)
            try {
                if (durationInMs > 0) Thread.sleep(durationInMs)
                if (gen == generation.get()) {
                    progressListener?.onDone(utteranceId)
                }
            } catch (_: InterruptedException) {
            }
        }
        return SUCCESS
    }

    private fun stopCurrentProcess() {
        generation.incrementAndGet()
        runCatching { activeProc?.destroyForcibly() }
        activeProc = null
    }

    fun stop(): Int {
        stopCurrentProcess()
        return SUCCESS
    }

    fun shutdown() {
        stopCurrentProcess()
        executor.shutdownNow()
    }
}
