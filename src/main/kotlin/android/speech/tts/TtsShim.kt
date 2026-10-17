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

    private val fallbackVoices = setOf(
        Voice("en-US-Default", Locale.US, 500, 50, false),
        Voice("en-GB-Default", Locale.UK, 500, 50, false),
        Voice("ko-KR-Default", Locale.KOREA, 500, 50, false)
    )

    /** (voice name, culture name) of the voices actually installed on this PC, enumerated once. */
    private val installedVoices: List<Pair<String, String>> by lazy { queryInstalledVoices() }

    /** The installed voices, or a small fallback set when none could be enumerated. */
    val voices: Set<Voice>
        get() = installedVoices.takeIf { it.isNotEmpty() }
            ?.map { (name, culture) ->
                Voice(name, runCatching { Locale.forLanguageTag(culture) }.getOrDefault(Locale.US), 500, 50, false)
            }
            ?.toSet()
            ?: fallbackVoices

    var voice: Voice? = fallbackVoices.first()

    init {
        // Enumerate voices off the UI thread so setLanguage/setVoice can answer truthfully without a stall.
        executor.submit { runCatching { installedVoices } }
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

    /** Reports whether the PC really has a voice for [loc], so the app can warn instead of speaking gibberish. */
    fun setLanguage(loc: Locale?): Int {
        if (loc != null) currentLocale = loc
        val wanted = loc ?: return LANG_COUNTRY_AVAILABLE
        val installed = installedVoices
        if (installed.isEmpty()) return LANG_COUNTRY_AVAILABLE   // could not enumerate: assume available
        val tag = wanted.toLanguageTag().lowercase()
        val language = tag.substringBefore('-')
        return when {
            installed.any { it.second.lowercase() == tag } -> LANG_COUNTRY_AVAILABLE
            installed.any { it.second.lowercase().startsWith(language) } -> LANG_AVAILABLE
            else -> LANG_MISSING_DATA
        }
    }

    fun setVoice(v: Voice): Int {
        val available = voices.firstOrNull { it.name == v.name } ?: voices.firstOrNull { it.locale == v.locale }
            ?: return ERROR
        voice = available
        currentLocale = available.locale
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
                // Prefer the exact installed voice for the current language, so Korean is not read by an English voice.
                val chosen = voice?.name?.takeIf { name -> installedVoices.any { it.first == name } }
                val select = if (chosen != null) {
                    "try { ${'$'}s.SelectVoice('${chosen.replace("'", "''")}') } catch {}"
                } else {
                    "try { ${'$'}s.SelectVoiceByHints([System.Speech.Synthesis.VoiceGender]::NotSet, [System.Speech.Synthesis.VoiceAge]::NotSet, 0, [System.Globalization.CultureInfo]::GetCultureInfo('$langHint')) } catch {}"
                }
                val cmd = """
                    Add-Type -AssemblyName System.Speech
                    ${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
                    $select
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

    /** Reads the SAPI voice list ("name|culture" per line). Returns empty when PowerShell/System.Speech is missing. */
    private fun queryInstalledVoices(): List<Pair<String, String>> = runCatching {
        val script = """
            Add-Type -AssemblyName System.Speech
            ${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
            ${'$'}s.GetInstalledVoices() | ForEach-Object { if (${'$'}_.Enabled) { Write-Output (${'$'}_.VoiceInfo.Name + '|' + ${'$'}_.VoiceInfo.Culture.Name) } }
            ${'$'}s.Dispose()
        """.trimIndent()
        val proc = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script).start()
        val lines = proc.inputStream.bufferedReader(Charsets.UTF_8).readLines()
        proc.waitFor()
        lines.mapNotNull { line ->
            val bar = line.indexOf('|')
            if (bar <= 0) null else line.substring(0, bar).trim() to line.substring(bar + 1).trim()
        }.filter { it.first.isNotBlank() && it.second.isNotBlank() }
    }.getOrDefault(emptyList())

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
