package android.media

import android.content.Context
import java.io.BufferedInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.LineEvent
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

class AudioAttributes {
    companion object {
        const val USAGE_MEDIA = 1
        const val CONTENT_TYPE_SPEECH = 1
        const val CONTENT_TYPE_MUSIC = 2
        const val CONTENT_TYPE_SONIFICATION = 4
        const val ALLOW_CAPTURE_BY_ALL = 1
    }

    class Builder {
        fun setUsage(usage: Int): Builder = this
        fun setContentType(contentType: Int): Builder = this
        fun build(): AudioAttributes = AudioAttributes()
    }
}

class AudioDeviceInfo(
    val id: Int = 1,
    val type: Int = TYPE_BUILTIN_MIC,
    val productName: CharSequence = "Windows 기본 마이크",
    val address: String = ""
) {
    companion object {
        const val TYPE_BUILTIN_EARPIECE = 1
        const val TYPE_BUILTIN_SPEAKER = 2
        const val TYPE_BLUETOOTH_SCO = 7
        const val TYPE_BLUETOOTH_A2DP = 8
        const val TYPE_BUILTIN_MIC = 15
        const val TYPE_BLE_HEADSET = 26
    }
}

class AudioFormat(
    val sampleRate: Int = 16000,
    val channelMask: Int = CHANNEL_IN_MONO,
    val encoding: Int = ENCODING_PCM_16BIT,
) {
    /** 채널 수. 모노/스테레오 마스크만 실제로 구분한다(앱은 그 이상을 요구하지 않는다). */
    val channelCount: Int
        get() = if (channelMask == CHANNEL_IN_MONO || channelMask == CHANNEL_OUT_MONO) 1 else 2

    companion object {
        const val CHANNEL_IN_MONO = 16
        const val CHANNEL_IN_STEREO = 12
        const val CHANNEL_OUT_MONO = 4
        const val CHANNEL_OUT_STEREO = 12
        const val ENCODING_PCM_16BIT = 2
        const val ENCODING_PCM_8BIT = 3
        const val ENCODING_PCM_FLOAT = 4
    }

    class Builder {
        private var sampleRate = 16000
        private var channelMask = CHANNEL_IN_MONO
        private var encoding = ENCODING_PCM_16BIT

        fun setSampleRate(sampleRate: Int): Builder {
            this.sampleRate = sampleRate
            return this
        }

        fun setChannelMask(channelMask: Int): Builder {
            this.channelMask = channelMask
            return this
        }

        fun setEncoding(encoding: Int): Builder {
            this.encoding = encoding
            return this
        }

        fun build(): AudioFormat = AudioFormat(sampleRate, channelMask, encoding)
    }
}

class AudioManager {
    companion object {
        const val STREAM_VOICE_CALL = 0
        const val STREAM_SYSTEM = 1
        const val STREAM_NOTIFICATION = 5
        const val STREAM_MUSIC = 3
        const val ADJUST_SAME = 0
        const val ADJUST_MUTE = -100
        const val ADJUST_UNMUTE = 100
        const val FLAG_SHOW_UI = 1
        const val GET_DEVICES_INPUTS = 1
        const val MODE_NORMAL = 0
        const val MODE_IN_COMMUNICATION = 3
    }

    var mode: Int = MODE_NORMAL
    var isBluetoothScoOn: Boolean = false

    private val inputs: List<AudioDeviceInfo> by lazy { AudioDevices.inputDevices() }
    private val outputs: List<AudioDeviceInfo> by lazy { AudioDevices.outputDevices() }
    private var communicationAddress: String? = null

    /** The device chosen with [setCommunicationDevice], or the first real device when none was chosen. */
    val communicationDevice: AudioDeviceInfo?
        get() = inputs.firstOrNull { it.address == communicationAddress } ?: inputs.firstOrNull()

    val availableCommunicationDevices: List<AudioDeviceInfo> get() = inputs

    fun getStreamVolume(streamType: Int): Int = 10
    fun adjustStreamVolume(streamType: Int, direction: Int, flags: Int) {}
    fun isStreamMute(streamType: Int): Boolean = false
    fun setAllowedCapturePolicy(capturePolicy: Int) {}
    fun getDevices(flags: Int): Array<AudioDeviceInfo> = (if (flags == GET_DEVICES_INPUTS) inputs else outputs).toTypedArray()
    fun getAudioDevicesForAttributes(attributes: AudioAttributes): List<AudioDeviceInfo> = outputs
    fun setCommunicationDevice(device: AudioDeviceInfo): Boolean {
        communicationAddress = device.address
        return true
    }
    fun clearCommunicationDevice() { communicationAddress = null }
    fun startBluetoothSco() {}
    fun stopBluetoothSco() {}
}

class MediaRecorder {
    object AudioSource {
        const val MIC = 1
        const val VOICE_RECOGNITION = 6
        const val VOICE_COMMUNICATION = 7
    }
}

class AudioRecord(
    audioSource: Int,
    private val sampleRateInHz: Int,
    channelConfig: Int,
    audioFormat: Int,
    private val bufferSizeInBytes: Int
) {
    companion object {
        const val STATE_UNINITIALIZED = 0
        const val STATE_INITIALIZED = 1
        const val RECORDSTATE_STOPPED = 1
        const val RECORDSTATE_RECORDING = 3

        fun getMinBufferSize(sampleRateInHz: Int, channelConfig: Int, audioFormat: Int): Int = 3200
    }

    private var line: TargetDataLine? = null
    private val format = javax.sound.sampled.AudioFormat(sampleRateInHz.toFloat(), 16, 1, true, false)
    var state: Int = STATE_UNINITIALIZED
        private set
    var recordingState: Int = RECORDSTATE_STOPPED
        private set
    val audioSessionId: Int = 1
    var preferredDevice: AudioDeviceInfo? = null

    init {
        openLine(null)
    }

    /** Opens the capture line on [device]'s mixer, falling back to the system default when that fails. */
    private fun openLine(device: AudioDeviceInfo?) {
        runCatching {
            runCatching { line?.close() }
            line = null
            state = STATE_UNINITIALIZED
            val info = DataLine.Info(TargetDataLine::class.java, format)
            val mixer = AudioDevices.mixerFor(device)
            val opened = runCatching {
                if (mixer != null && mixer.isLineSupported(info)) mixer.getLine(info) as TargetDataLine
                else AudioSystem.getLine(info) as TargetDataLine
            }.getOrElse {
                if (mixer != null) AudioSystem.getLine(info) as TargetDataLine else throw it
            }
            opened.open(format, bufferSizeInBytes.coerceAtLeast(3200))
            line = opened
            state = STATE_INITIALIZED
        }
    }

    /**
     * Routes capture to [deviceInfo] by reopening the line on that device's mixer, so the microphone the user
     * picked (including a Bluetooth headset) is actually used instead of being recorded-but-ignored.
     */
    fun setPreferredDevice(deviceInfo: AudioDeviceInfo?): Boolean {
        preferredDevice = deviceInfo
        if (recordingState == RECORDSTATE_RECORDING) return true
        openLine(deviceInfo)
        return line != null
    }

    fun startRecording() {
        runCatching {
            line?.start()
            recordingState = RECORDSTATE_RECORDING
        }
    }

    fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
        val l = line ?: return -1
        return runCatching { l.read(audioData, offsetInBytes, sizeInBytes) }.getOrDefault(-1)
    }

    fun read(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int): Int {
        val l = line ?: return -1
        val bytes = ByteArray(sizeInShorts * 2)
        val n = runCatching { l.read(bytes, 0, bytes.size) }.getOrDefault(-1)
        if (n <= 0) return n
        val shortsRead = n / 2
        val bb = java.nio.ByteBuffer.wrap(bytes, 0, shortsRead * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        bb.get(audioData, offsetInShorts, shortsRead)
        return shortsRead
    }

    fun stop() {
        recordingState = RECORDSTATE_STOPPED
        runCatching { line?.stop() }
    }

    fun release() {
        stop()
        runCatching { line?.close() }
        line = null
    }

    class Builder {
        private var source = MediaRecorder.AudioSource.MIC
        private var format = AudioFormat()
        private var bufSize = 8192

        fun setAudioSource(source: Int): Builder {
            this.source = source
            return this
        }

        fun setAudioFormat(format: AudioFormat): Builder {
            this.format = format
            return this
        }

        fun setBufferSizeInBytes(bufferSizeInBytes: Int): Builder {
            this.bufSize = bufferSizeInBytes
            return this
        }

        fun build(): AudioRecord =
            AudioRecord(source, format.sampleRate, format.channelMask, format.encoding, bufSize)
    }
}

class AudioTrack {
    companion object {
        const val MODE_STREAM = 1
        const val STATE_INITIALIZED = 1

        fun getMinBufferSize(sampleRateInHz: Int, channelConfig: Int, audioFormat: Int): Int = 3200
    }

    val state: Int = STATE_INITIALIZED

    private var line: SourceDataLine? = null
    private var sampleRate = 16000
    private var channels = 1

    /** 빌더가 알려 준 형식. 예전에는 항상 16kHz 모노로 열어서 다른 형식의 PCM 이 잡음으로 들렸다. */
    internal fun configure(sampleRateHz: Int, channelCount: Int) {
        val rate = sampleRateHz.coerceIn(8000, 192_000)
        val ch = channelCount.coerceIn(1, 2)
        if (rate != sampleRate || ch != channels) {
            runCatching { line?.close() }
            line = null
        }
        sampleRate = rate
        channels = ch
    }

    private fun javaFormat(): javax.sound.sampled.AudioFormat =
        javax.sound.sampled.AudioFormat(sampleRate.toFloat(), 16, channels, true, false)

    private fun ensureLine(): SourceDataLine? {
        line?.let { if (it.isOpen) return it }
        line = null
        return runCatching {
            val fmt = javaFormat()
            (AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, fmt)) as SourceDataLine).also {
                it.open(fmt)
                line = it
            }
        }.getOrNull()
    }

    fun play() {
        runCatching { ensureLine()?.start() }
    }

    /** Writes [audioData] to the real output line; the write throttles to playback speed, keeping the path warm. */
    fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
        val l = ensureLine() ?: return sizeInBytes
        return runCatching { l.write(audioData, offsetInBytes, sizeInBytes) }.getOrDefault(sizeInBytes)
    }

    fun stop() {
        runCatching { line?.drain() }
    }

    fun release() {
        runCatching { line?.close() }
        line = null
    }

    class Builder {
        private var sampleRate = 16000
        private var channels = 1

        fun setAudioAttributes(attributes: AudioAttributes): Builder = this
        fun setAudioFormat(format: AudioFormat): Builder {
            sampleRate = format.sampleRate
            channels = format.channelCount
            return this
        }

        fun setBufferSizeInBytes(bufferSizeInBytes: Int): Builder = this
        fun setTransferMode(mode: Int): Builder = this
        fun build(): AudioTrack = AudioTrack().also { it.configure(sampleRate, channels) }
    }
}

class SoundPool {
    fun interface OnLoadCompleteListener {
        fun onLoadComplete(soundPool: SoundPool, sampleId: Int, status: Int)
    }

    private var loadListener: OnLoadCompleteListener? = null
    private val sounds = mutableMapOf<Int, ByteArray>()
    private var nextId = 1

    fun setOnLoadCompleteListener(listener: OnLoadCompleteListener?) {
        loadListener = listener
    }

    fun load(context: Context, resId: Int, priority: Int): Int {
        val id = nextId++
        runCatching {
            val bytes = context.resources.openRawResource(resId).use { it.readBytes() }
            sounds[id] = bytes
            loadListener?.onLoadComplete(this, id, 0)
        }
        return id
    }

    fun play(soundID: Int, leftVolume: Float, rightVolume: Float, priority: Int, loop: Int, rate: Float): Int {
        val bytes = sounds[soundID] ?: return 0
        ClipPlayer.play(bytes, loop = loop, leftVolume = leftVolume, rightVolume = rightVolume)
        return 1
    }

    fun release() {
        sounds.clear()
    }

    class Builder {
        fun setMaxStreams(maxStreams: Int): Builder = this
        fun setAudioAttributes(attributes: AudioAttributes): Builder = this
        fun build(): SoundPool = SoundPool()
    }
}

class MediaPlayer private constructor(private val rawBytes: ByteArray?) {
    private var onCompletion: ((MediaPlayer) -> Unit)? = null
    private var onError: ((MediaPlayer, Int, Int) -> Boolean)? = null
    private var leftVolume: Float = 1f
    private var rightVolume: Float = 1f

    fun setVolume(leftVolume: Float, rightVolume: Float) {
        this.leftVolume = leftVolume
        this.rightVolume = rightVolume
    }

    fun setOnCompletionListener(listener: (MediaPlayer) -> Unit) { onCompletion = listener }
    fun setOnErrorListener(listener: (MediaPlayer, Int, Int) -> Boolean) { onError = listener }

    fun start() {
        val bytes = rawBytes
        if (bytes == null) {
            onCompletion?.invoke(this)
            return
        }
        ClipPlayer.play(bytes, loop = 0, leftVolume = leftVolume, rightVolume = rightVolume) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onCompletion?.invoke(this)
            }
        }
    }

    fun release() {}

    companion object {
        fun create(context: Context, resid: Int, audioAttributes: AudioAttributes?, audioSessionId: Int): MediaPlayer? =
            runCatching {
                val bytes = context.resources.openRawResource(resid).use { it.readBytes() }
                MediaPlayer(bytes)
            }.getOrNull()
    }
}

/**
 * Plays a whole encoded clip and reports when it actually finishes (through a line listener), so clips are no
 * longer cut off at a fixed 3 s / 5 s cap and completion no longer fires early. Loop semantics match Android:
 * [loop] == 0 plays once, a positive value repeats that many extra times, a negative value repeats forever.
 */
private object ClipPlayer {
    fun play(bytes: ByteArray, loop: Int, leftVolume: Float, rightVolume: Float, onFinished: (() -> Unit)? = null) {
        Thread {
            try {
                AudioSystem.getAudioInputStream(BufferedInputStream(bytes.inputStream())).use { ais ->
                    val clip: Clip = AudioSystem.getClip()
                    clip.open(ais)
                    applyVolume(clip, leftVolume, rightVolume)
                    clip.addLineListener { event ->
                        if (event.type == LineEvent.Type.STOP) {
                            runCatching { clip.close() }
                            onFinished?.invoke()
                        }
                    }
                    clip.loop(if (loop < 0) Clip.LOOP_CONTINUOUSLY else loop)
                    clip.start()
                }
            } catch (_: Throwable) {
                onFinished?.invoke()
            }
        }.apply { isDaemon = true; start() }
    }

    private fun applyVolume(clip: Clip, left: Float, right: Float) {
        runCatching {
            val gain = clip.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
            val linear = ((left + right) / 2f).coerceIn(0.0001f, 1f)
            val dB = (20.0 * kotlin.math.log10(linear.toDouble())).toFloat().coerceIn(gain.minimum, gain.maximum)
            if (gain.value != dB) gain.value = dB
        }
    }
}
