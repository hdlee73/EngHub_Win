package android.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 인식 파이프라인 전체를 실제 코드로 확인한다: 앱이 넘긴 캡처 파이프 → 발화 구간 감지 → WAV → System.Speech →
 * 결과 콜백. 마이크 없이도 검증되도록, 녹음할 때와 같은 형식(16kHz 모노 16bit)의 실제 영어 음성을 파이프로
 * 흘려 넣는다(테스트 리소스는 Windows TTS 로 만든 문장).
 *
 * Windows 음성 인식 엔진이 없는 PC(Windows 11 24H2 이후에는 기본 포함되지 않는다)에서는 건너뛴다.
 */
class SpeechRecognitionPipelineTest {
    private fun samplePcm(): ByteArray {
        val wav = javaClass.getResourceAsStream("/speech_sample_16k.wav")!!.use { it.readBytes() }
        // 표준 PCM WAV 헤더(44바이트)를 떼고 실제 소리만 쓴다.
        return wav.copyOfRange(44, wav.size)
    }

    @Test
    fun `recognizes speech fed through the app capture pipe`() {
        if (!SpeechEngine.detectNow()) {
            println("Windows 음성 인식 엔진이 없어 인식 파이프라인 테스트를 건너뜁니다.")
            return
        }
        val pcm = samplePcm()
        val pipe = ParcelFileDescriptor.createPipe()
        val done = CountDownLatch(1)
        var recognized: List<String> = emptyList()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(Context())
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) = done.countDown()
            override fun onResults(results: Bundle) {
                recognized = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                done.countDown()
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        // 실제 마이크 입력처럼 앞뒤에 무음을 넣는다. 발화가 끝난 뒤의 무음은 인식이 잘라 낸다.
        val silenceHalfSecond = ByteArray(SpeechVad.SAMPLE_RATE * 2 / 2)
        val silenceTwoSeconds = ByteArray(SpeechVad.SAMPLE_RATE * 2 * 2)
        Thread({
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                output.write(silenceHalfSecond)
                output.write(pcm)
                output.write(silenceTwoSeconds)
            }
        }, "EngHub-TestMic").apply { isDaemon = true; start() }

        val intent = Intent().apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
        }
        recognizer.startListening(intent)
        assertTrue(done.await(60, TimeUnit.SECONDS), "인식 결과가 돌아오지 않았습니다")
        println("recognized=$recognized")
        assertTrue(recognized.isNotEmpty(), "인식된 문장이 없습니다")
        assertTrue(recognized.any { it.contains("commute", ignoreCase = true) }, "예상과 다른 결과: $recognized")
        recognizer.destroy()
    }
}
