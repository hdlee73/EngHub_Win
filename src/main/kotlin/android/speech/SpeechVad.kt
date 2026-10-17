package android.speech

import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * 한 문장 분량의 PCM(16kHz 모노 16bit)을 모으는 발화 구간 감지기.
 *
 * Windows 인식 경로([SpeechShim])는 마이크에서 직접 받은 소리를 여기서 잘라 WAV 로 만들어 System.Speech 에
 * 넘긴다. 여기서 빈 배열이 나오면 사용자에게는 "발음을 인식하지 못함"으로 보이므로, 잡음 바닥을 적응적으로
 * 추정해 조용한 방에서도, 블루투스 이어셋의 약 2초 워밍업 뒤에도 문장을 잡아낸다.
 */
internal object SpeechVad {
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_MS = SAMPLE_RATE * 2 / 1000

    /** 발화를 기다리는 최대 시간(블루투스 이어셋은 캡처를 열고 약 2초 뒤에야 소리가 들어온다). */
    private const val WAIT_SPEECH_MS = 8_000

    /** 한 문장으로 볼 최대 길이. */
    private const val MAX_SPEECH_MS = 15_000

    /** 이보다 짧으면 말소리로 보지 않는다(기침/딸깍 소리 걸러내기). */
    private const val MIN_SPEECH_MS = 250

    /** 발화 끝에 남겨 두는 여운. 인식 정확도를 위해 조금 남긴다. */
    private const val TAIL_MS = 300

    /**
     * [readBlock] 로 100ms 씩 읽으며 발화 시작/끝을 판단한다. 발화가 시작된 뒤 [endSilenceMs] 만큼 조용하면
     * 문장이 끝난 것으로 보고 돌아온다. 소리가 전혀 없으면 빈 배열을 돌려준다(재시도 안내로 이어진다).
     */
    fun readUtterance(readBlock: (ByteArray) -> Int, endSilenceMs: Int, cancelled: () -> Boolean): ByteArray {
        val block = ByteArray(BYTES_PER_MS * 100)
        val out = ByteArrayOutputStream(block.size * 40)
        var noiseRms = -1.0
        var speechMs = 0
        var silenceMs = 0
        var waitedMs = 0
        var voicedBytes = 0
        var started = false
        while (!cancelled()) {
            val n = readBlock(block)
            if (n <= 0) break
            out.write(block, 0, n)
            val ms = n / BYTES_PER_MS
            val rms = rmsOf(block, n)
            // 잡음 바닥은 "가장 조용했던 블록"으로 본다(말소리는 잡음보다 크므로 최솟값이 안전하다). 사용자가
            // 시작하자마자 말해도 단어 사이 짧은 틈에서 바닥이 내려간다. 조용하지 않은 방에서도 동작하도록
            // 2배 기준과 하한(200)을 둔다.
            val threshold = maxOf(if (noiseRms > 0) noiseRms * 2.0 else 200.0, 200.0)
            if (rms > threshold) {
                started = true
                speechMs += ms
                silenceMs = 0
                voicedBytes = out.size()
                if (speechMs >= MAX_SPEECH_MS) break
            } else if (!started) {
                waitedMs += ms
                noiseRms = if (noiseRms < 0) rms else minOf(noiseRms, rms)
                if (waitedMs >= WAIT_SPEECH_MS) break
            } else {
                silenceMs += ms
                if (silenceMs >= endSilenceMs) break
            }
        }
        if (!started || speechMs < MIN_SPEECH_MS) return ByteArray(0)
        val keep = minOf(out.size(), voicedBytes + BYTES_PER_MS * TAIL_MS)
        return out.toByteArray().copyOf(keep)
    }

    /** 16bit little-endian PCM 블록의 RMS. */
    fun rmsOf(block: ByteArray, length: Int): Double {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < length) {
            val sample = ((block[i].toInt() and 0xFF) or (block[i + 1].toInt() shl 8)).toShort().toInt()
            sum += sample.toDouble() * sample
            count++
            i += 2
        }
        return if (count == 0) 0.0 else sqrt(sum / count)
    }
}
