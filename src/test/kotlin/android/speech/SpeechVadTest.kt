package android.speech

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 발화 구간 감지(VAD) 회귀 테스트.
 *
 * Windows 인식 경로([SpeechShim])는 마이크(또는 앱이 넘긴 파이프)에서 직접 받은 소리를 이 감지기로 잘라
 * WAV 로 만들어 인식한다. 여기서 빈 배열이 나오면 사용자에게는 "발음을 인식하지 못함"으로 보이므로,
 * 조용한 방/시끄러운 방 양쪽에서 문장을 잡아내는지 확인한다.
 */
class SpeechVadTest {
    /** 16bit little-endian PCM 을 만든다. [amplitude] 가 0 이면 무음. */
    private fun pcm(ms: Int, amplitude: Int): ByteArray {
        val samples = SpeechVad.SAMPLE_RATE * ms / 1000
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            // 200Hz 사인파: 말소리처럼 일정한 크기를 가진 신호.
            val value = (amplitude * sin(2 * PI * 200 * i / SpeechVad.SAMPLE_RATE)).toInt()
            out[i * 2] = (value and 0xFF).toByte()
            out[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun silence(ms: Int) = ByteArray(SpeechVad.SAMPLE_RATE * 2 * ms / 1000)

    private fun source(vararg parts: ByteArray): (ByteArray) -> Int {
        val all = parts.fold(ByteArray(0)) { acc, part -> acc + part }
        var position = 0
        return { buffer ->
            if (position >= all.size) 0 else {
                val n = minOf(buffer.size, all.size - position)
                System.arraycopy(all, position, buffer, 0, n)
                position += n
                n
            }
        }
    }

    private fun keptMs(pcm: ByteArray) = pcm.size / SpeechVad.BYTES_PER_MS

    @Test
    fun `captures one sentence and drops the long silence after it`() {
        val result = SpeechVad.readUtterance(
            source(silence(500), pcm(1000, 6000), silence(2000)),
            endSilenceMs = 800,
            cancelled = { false },
        )
        // 앞 무음 + 발화 1초 + 끝 여운(약 0.3초) 정도만 남고, 뒤의 2초 무음은 잘려 나간다(전체 3.5초).
        assertTrue(keptMs(result) in 1500..2000, "kept=${keptMs(result)}ms")
    }

    @Test
    fun `returns empty when nothing is said`() {
        val result = SpeechVad.readUtterance(source(silence(3000)), endSilenceMs = 800, cancelled = { false })
        assertEquals(0, result.size)
    }

    @Test
    fun `still finds speech over a high steady noise floor`() {
        // 배경 잡음(RMS 약 700)이 있어도 말소리(약 4200)를 잡아낸다.
        val result = SpeechVad.readUtterance(
            source(pcm(1000, 1000), pcm(1000, 6000), pcm(1500, 1000)),
            endSilenceMs = 800,
            cancelled = { false },
        )
        assertTrue(keptMs(result) >= 1000, "kept=${keptMs(result)}ms")
    }

    @Test
    fun `captures speech that starts immediately`() {
        // 사용자가 듣기 시작하자마자 말하는 경우(앞 무음 없음).
        val result = SpeechVad.readUtterance(source(pcm(1200, 6000), silence(2000)), 800, { false })
        assertTrue(keptMs(result) >= 1200, "kept=${keptMs(result)}ms")
    }

    @Test
    fun `stops when the caller cancels`() {
        var calls = 0
        val result = SpeechVad.readUtterance(source(silence(500), pcm(1000, 6000), silence(2000)), 800) {
            calls++ > 2
        }
        assertTrue(result.isEmpty(), "cancelled run should not return audio")
    }
}
