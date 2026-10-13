package android.media.audiofx

open class AudioEffect {
    var enabled: Boolean = false
    open fun release() {}
}

class NoiseSuppressor : AudioEffect() {
    companion object {
        fun isAvailable(): Boolean = false
        fun create(audioSession: Int): NoiseSuppressor? = null
    }
}

class AcousticEchoCanceler : AudioEffect() {
    companion object {
        fun isAvailable(): Boolean = false
        fun create(audioSession: Int): AcousticEchoCanceler? = null
    }
}
