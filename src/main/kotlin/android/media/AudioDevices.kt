package android.media

import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * Enumerates the real Windows capture/playback devices through JavaSound, so the shim can report the same
 * information Android's AudioManager does instead of a single hard-coded fake device.
 *
 * Bluetooth headsets / hands-free devices are reported with the Android Bluetooth device types, so the app's
 * existing Bluetooth picker and routing logic (which filters on TYPE_BLUETOOTH_SCO / TYPE_BLE_HEADSET) can see
 * them. Windows exposes no reliable device-type flag through JavaSound, so the type is inferred from the mixer
 * name; when in doubt the device is reported as a built-in mic/speaker (never hidden).
 */
internal object AudioDevices {
    fun inputDevices(): List<AudioDeviceInfo> {
        val out = ArrayList<AudioDeviceInfo>()
        var id = 1
        for (info in AudioSystem.getMixerInfo()) {
            val mixer = runCatching { AudioSystem.getMixer(info) }.getOrNull() ?: continue
            if (!supports(mixer, TargetDataLine::class.java)) continue
            val name = info.name?.takeIf { it.isNotBlank() } ?: "입력 장치"
            out.add(AudioDeviceInfo(id++, typeFor(name, input = true), productName = name, address = name))
        }
        if (out.isEmpty()) out.add(AudioDeviceInfo(1, AudioDeviceInfo.TYPE_BUILTIN_MIC, "Windows 기본 마이크", ""))
        return out
    }

    fun outputDevices(): List<AudioDeviceInfo> {
        val out = ArrayList<AudioDeviceInfo>()
        var id = 1
        for (info in AudioSystem.getMixerInfo()) {
            val mixer = runCatching { AudioSystem.getMixer(info) }.getOrNull() ?: continue
            if (!supports(mixer, SourceDataLine::class.java)) continue
            val name = info.name?.takeIf { it.isNotBlank() } ?: "출력 장치"
            out.add(AudioDeviceInfo(id++, typeFor(name, input = false), productName = name, address = name))
        }
        if (out.isEmpty()) out.add(AudioDeviceInfo(1, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Windows 스피커", ""))
        return out
    }

    /** The mixer named by [device] (its [AudioDeviceInfo.address]), or null for the system default. */
    fun mixerFor(device: AudioDeviceInfo?): Mixer? = mixerForName(device?.address.orEmpty())

    /** The mixer with this JavaSound name (a stored [AudioDeviceInfo.address]), or null when it is gone. */
    fun mixerForName(name: String): Mixer? {
        if (name.isBlank()) return null
        val info = AudioSystem.getMixerInfo().firstOrNull { it.name == name } ?: return null
        return runCatching { AudioSystem.getMixer(info) }.getOrNull()
    }

    private fun supports(mixer: Mixer, lineClass: Class<*>): Boolean = runCatching {
        val infos = if (lineClass == TargetDataLine::class.java) mixer.targetLineInfo else mixer.sourceLineInfo
        infos.any { it is DataLine.Info && lineClass.isAssignableFrom(it.lineClass) }
    }.getOrDefault(false)

    private fun typeFor(name: String, input: Boolean): Int {
        val bluetooth = looksBluetooth(name)
        return when {
            bluetooth && input -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            bluetooth -> AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            input -> AudioDeviceInfo.TYPE_BUILTIN_MIC
            else -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
    }

    private val bluetoothWords = listOf(
        // English Windows naming
        "bluetooth", "wireless", "hands-free", "handsfree", "hands free", "headset", "headphone",
        "earphone", "hfp", "a2dp", "sco", "avrcp", "airpods", "buds", "jabra", "bose", "sony",
        "sennheiser", "soundcore", "beats", "jbl", "wh-1000", "wf-1000",
        // 한국어 Windows 표기: 블루투스 헤드셋은 "머리에 거는 수화기(WH-1000XM6)"(통화/HFP),
        // 재생 쪽은 "헤드폰(...)"(A2DP) 로 나온다. 예전에는 이 이름을 몰라 블루투스 마이크가
        // 목록에 아예 안 보였다(사용자 신고).
        "블루투스", "무선", "머리에 거는 수화기", "수화기", "헤드셋", "헤드폰", "이어폰", "핸즈프리", "에어팟", "버즈",
    )

    fun looksBluetooth(name: String): Boolean {
        val n = name.lowercase()
        return bluetoothWords.any { n.contains(it) }
    }

    /** 16bit little-endian PCM 의 RMS. */
    private fun rmsOf(buffer: ByteArray, length: Int): Double {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < length) {
            val sample = ((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort().toInt()
            sum += sample.toDouble() * sample
            count++
            i += 2
        }
        return if (count == 0) 0.0 else kotlin.math.sqrt(sum / count)
    }

    /**
     * 이 장치([mixer] 가 null 이면 Windows 기본 장치)에서 실제로 소리가 들어오는지 잠깐 열어 확인한다.
     *
     * 이 PC 처럼 Windows 기본 입력 장치(USB 마이크)가 소리를 전혀 내보내지 않는 경우가 있다. 그대로 녹음하면
     * 조용한 파일이 만들어져 "녹음이 안 된다/인식이 안 된다"로 보이므로, 자동 선택에서는 소리가 들어오는
     * 장치를 골라 쓴다.
     */
    fun hasSignal(mixer: Mixer?, probeMillis: Int = 500): Boolean {
        val format = javax.sound.sampled.AudioFormat(16_000f, 16, 1, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        val line = runCatching {
            val opened = if (mixer != null) mixer.getLine(info) as TargetDataLine else AudioSystem.getLine(info) as TargetDataLine
            opened.open(format)
            opened
        }.getOrNull() ?: return false
        return try {
            line.start()
            val buffer = ByteArray(3200)
            var peak = 0.0
            val deadline = System.currentTimeMillis() + probeMillis
            while (System.currentTimeMillis() < deadline) {
                val read = line.read(buffer, 0, buffer.size)
                if (read > 0) peak = maxOf(peak, rmsOf(buffer, read))
            }
            peak > 25.0
        } finally {
            runCatching { line.stop() }
            runCatching { line.close() }
        }
    }

    /**
     * 소리가 들어오는 입력 장치를 고른다. [preferredName] 이 있으면 그 장치를 먼저 본다.
     * 돌려주는 값이 null 이면 "Windows 기본 장치를 그대로 써도 된다"는 뜻이다.
     */
    fun pickWorkingInput(preferredName: String? = null, probeMillis: Int = 500): AudioDeviceInfo? {
        if (!preferredName.isNullOrBlank()) {
            val device = inputDevices().firstOrNull { it.address == preferredName }
            if (device != null && hasSignal(mixerForName(preferredName), probeMillis)) return device
        }
        if (hasSignal(null, probeMillis)) return null
        for (device in inputDevices()) {
            // 블루투스 장치는 여는 순간 통화(HFP) 모드로 바뀌므로 자동 선택에서는 건너뛴다.
            if (looksBluetooth(device.address)) continue
            if (hasSignal(mixerForName(device.address), probeMillis)) return device
        }
        return null
    }

    @Volatile private var autoDevice: AudioDeviceInfo? = null
    @Volatile private var autoProbed = false

    /** 앱 시작 때 확인해 둔 "소리가 들어오는 입력 장치". null 이면 Windows 기본 장치에 소리가 들어온다. */
    fun autoInputDevice(): AudioDeviceInfo? = autoDevice

    /** 앱 시작 직후 백그라운드에서 한 번만 확인해 둔다(인식 시작 때 지연되지 않도록). */
    fun probeAutoInput(probeMillis: Int = 500) {
        if (autoProbed) return
        autoProbed = true
        autoDevice = runCatching { pickWorkingInput(null, probeMillis) }.getOrNull()
    }
}
