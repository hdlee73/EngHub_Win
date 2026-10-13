package android.media

import android.content.Context
import java.io.BufferedInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
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

class AudioFormat {
    companion object {
        const val CHANNEL_IN_MONO = 16
        const val CHANNEL_OUT_MONO = 4
        const val ENCODING_PCM_16BIT = 2
    }

    class Builder {
        fun setSampleRate(sampleRate: Int): Builder = this
        fun setChannelMask(channelMask: Int): Builder = this
        fun setEncoding(encoding: Int): Builder = this
        fun build(): AudioFormat = AudioFormat()
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
    val communicationDevice: AudioDeviceInfo? = AudioDeviceInfo()
    val availableCommunicationDevices: List<AudioDeviceInfo> = listOf(AudioDeviceInfo())

    fun getStreamVolume(streamType: Int): Int = 10
    fun adjustStreamVolume(streamType: Int, direction: Int, flags: Int) {}
    fun isStreamMute(streamType: Int): Boolean = false
    fun setAllowedCapturePolicy(capturePolicy: Int) {}
    fun getDevices(flags: Int): Array<AudioDeviceInfo> = arrayOf(AudioDeviceInfo())
    fun getAudioDevicesForAttributes(attributes: AudioAttributes): List<AudioDeviceInfo> =
        listOf(AudioDeviceInfo(type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, productName = "Windows 스피커"))
    fun setCommunicationDevice(device: AudioDeviceInfo): Boolean = true
    fun clearCommunicationDevice() {}
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
    bufferSizeInBytes: Int
) {
    companion object {
        const val STATE_UNINITIALIZED = 0
        const val STATE_INITIALIZED = 1
        const val RECORDSTATE_STOPPED = 1
        const val RECORDSTATE_RECORDING = 3

        fun getMinBufferSize(sampleRateInHz: Int, channelConfig: Int, audioFormat: Int): Int = 3200
    }

    private var line: TargetDataLine? = null
    var state: Int = STATE_UNINITIALIZED
        private set
    var recordingState: Int = RECORDSTATE_STOPPED
        private set
    val audioSessionId: Int = 1
    var preferredDevice: AudioDeviceInfo? = null

    init {
        runCatching {
            val fmt = javax.sound.sampled.AudioFormat(sampleRateInHz.toFloat(), 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, fmt)
            if (AudioSystem.isLineSupported(info)) {
                line = (AudioSystem.getLine(info) as TargetDataLine).also {
                    it.open(fmt, bufferSizeInBytes.coerceAtLeast(3200))
                }
                state = STATE_INITIALIZED
            }
        }
    }

    fun setPreferredDevice(deviceInfo: AudioDeviceInfo?): Boolean {
        preferredDevice = deviceInfo
        return true
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
        private var rate = 16000
        private var bufSize = 8192

        fun setAudioSource(source: Int): Builder { this.source = source; return this }
        fun setAudioFormat(format: AudioFormat): Builder = this
        fun setBufferSizeInBytes(bufferSizeInBytes: Int): Builder { this.bufSize = bufferSizeInBytes; return this }
        fun build(): AudioRecord = AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
    }
}

class AudioTrack {
    companion object {
        const val MODE_STREAM = 1
        const val STATE_INITIALIZED = 1

        fun getMinBufferSize(sampleRateInHz: Int, channelConfig: Int, audioFormat: Int): Int = 3200
    }

    val state: Int = STATE_INITIALIZED

    fun play() {}
    fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
        Thread.sleep(25)
        return sizeInBytes
    }
    fun stop() {}
    fun release() {}

    class Builder {
        fun setAudioAttributes(attributes: AudioAttributes): Builder = this
        fun setAudioFormat(format: AudioFormat): Builder = this
        fun setBufferSizeInBytes(bufferSizeInBytes: Int): Builder = this
        fun setTransferMode(mode: Int): Builder = this
        fun build(): AudioTrack = AudioTrack()
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
        Thread {
            runCatching {
                AudioSystem.getAudioInputStream(BufferedInputStream(bytes.inputStream())).use { ais ->
                    val clip = AudioSystem.getClip()
                    clip.open(ais)
                    clip.start()
                    Thread.sleep((clip.microsecondLength / 1000L).coerceIn(100L, 3000L))
                    clip.close()
                }
            }
        }.apply { isDaemon = true; start() }
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

    fun setVolume(leftVolume: Float, rightVolume: Float) {}
    fun setOnCompletionListener(listener: (MediaPlayer) -> Unit) { onCompletion = listener }
    fun setOnErrorListener(listener: (MediaPlayer, Int, Int) -> Boolean) { onError = listener }

    fun start() {
        val bytes = rawBytes
        if (bytes == null) {
            onCompletion?.invoke(this)
            return
        }
        Thread {
            runCatching {
                AudioSystem.getAudioInputStream(BufferedInputStream(bytes.inputStream())).use { ais ->
                    val clip: Clip = AudioSystem.getClip()
                    clip.open(ais)
                    clip.start()
                    Thread.sleep((clip.microsecondLength / 1000L).coerceIn(100L, 5000L))
                    clip.close()
                }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onCompletion?.invoke(this)
            }
        }.apply { isDaemon = true; start() }
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
