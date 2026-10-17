package com.hdlee73.englishstudy.docvoice

import com.hdlee73.englishstudy.R

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.hdlee73.englishstudy.docvoice.core.AudioDecoder
import com.hdlee73.englishstudy.docvoice.core.ModelStore
import com.hdlee73.englishstudy.docvoice.core.PcmBuffer
import com.hdlee73.englishstudy.docvoice.core.Storage
import com.hdlee73.englishstudy.docvoice.core.Stt
import com.hdlee73.englishstudy.docvoice.core.LiveLang
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** 마이크 녹음 + 실시간 받아쓰기 (Silero VAD 로 말 구간을 잘라 Whisper 로 바로 인식). */
class RecorderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wake: PowerManager.WakeLock? = null

    private class Speech(val samples: FloatArray, val start: Int)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { RecorderHub.stopRequested.set(true); return START_NOT_STICKY }
            ACTION_PAUSE -> { RecorderHub.paused.set(!RecorderHub.paused.get()); return START_NOT_STICKY }
            ACTION_START -> {}
            else -> return START_NOT_STICKY
        }
        if (RecorderHub.job?.isActive == true) return START_NOT_STICKY
        ensureChannel()
        startForeground(NOTIF_ID, notification("준비 중"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DocVoice:rec").apply { acquire(4 * 60 * 60 * 1000L) }
        RecorderHub.stopRequested.set(false)
        RecorderHub.paused.set(false)
        RecorderHub.pcm = null
        RecorderHub.state.value = RecState(phase = RecPhase.Preparing, message = "준비 중")

        RecorderHub.job = scope.launch {
            try {
                record()
            } catch (e: CancellationException) {
                RecorderHub.upd { copy(phase = RecPhase.Failed, message = "녹음을 취소했습니다.") }
            } catch (e: SecurityException) {
                RecorderHub.state.value = RecState(phase = RecPhase.Failed, message = "마이크 권한이 필요합니다.")
            } catch (e: Throwable) {
                RecorderHub.upd { copy(
                    phase = RecPhase.Failed, message = "녹음 중 오류가 발생했습니다: ${e.message ?: e.javaClass.simpleName}",
                ) }
            } finally {
                wake?.let { if (it.isHeld) it.release() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private suspend fun record() {
        val sr = AudioDecoder.SAMPLE_RATE
        val store = ModelStore(this)
        val lang = LiveLang.of(RecorderHub.language)

        // 이 셰임의 sherpa-onnx 스트리밍 엔진(OnlineRecognizer)은 아무것도 하지 않으므로, 실시간으로
        // 글자를 띄우는 대신 마이크로 녹음한 PCM 을 녹음이 끝난 뒤 Windows 음성 인식기(SAPI)로 받아쓴다.
        // 파일 변환(MP3→문서)과 같은 경로라서 실제로 글자가 나온다. 준비할 모델도 없다.
        prep("녹음 준비 중", null)

        // 무한 녹음으로 메모리가 터지지 않게 상한을 둔다. 다 차면 [pcm.full] 이 서고 아래 캡처 루프가 멈춘다.
        val pcm = PcmBuffer(sr * 60 * 5, sr * MAX_RECORD_SECONDS)
        RecorderHub.pcm = pcm

        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val route = routeInput(RecorderHub.bluetooth)
        val rec = AudioRecord(
            if (route.bt) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, sr * 4),
        )
        route.device?.let { rec.preferredDevice = it }
        RecorderHub.upd { copy(source = route.name) }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); route.cleanup()
            throw IllegalStateException("마이크를 시작할 수 없습니다.")
        }
        var captureNotice: String? = null
        try {
            rec.startRecording()
            RecorderHub.upd { copy(phase = RecPhase.Recording, message = null, fraction = null) }
            val buf = ShortArray(1600) // 0.1초
            var lastNotify = 0L
            var peak = 0f
            withContext(Dispatchers.IO) {
                while (!RecorderHub.stopRequested.get() && scope.isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (RecorderHub.paused.get()) {
                        RecorderHub.upd { copy(phase = RecPhase.Paused, level = 0f) }
                        continue
                    }
                    var sum = 0.0
                    for (i in 0 until n) {
                        pcm.add(buf[i])
                        val v = buf[i] / 32768f
                        sum += v * v
                        val a = kotlin.math.abs(v)
                        if (a > peak) peak = a
                    }
                    val rms = sqrt(sum / n).toFloat()
                    RecorderHub.upd { copy(phase = RecPhase.Recording, seconds = pcm.seconds, level = (rms * 6f).coerceIn(0f, 1f), peak = peak) }
                    val now = System.currentTimeMillis()
                    if (now - lastNotify > 1000) {
                        lastNotify = now
                        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(fmt(pcm.seconds)))
                    }
                    // 상한(MAX_RECORD_SECONDS)까지 다 녹음했으면 여기서 멈추고 사용자에게 알린다.
                    if (pcm.full) { captureNotice = MAX_RECORD_MESSAGE; break }
                }
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            route.cleanup()
        }

        // 녹음이 끝났으면 Windows 음성 인식기(SAPI)로 받아쓴다. 파일 변환(MP3→문서)과 같은 경로라서 실제로 글이 나온다.
        if (pcm.size <= 0) {
            RecorderHub.upd { copy(phase = RecPhase.Failed, level = 0f, message = "녹음된 소리가 없어요.") }
            return
        }
        // 녹음이 끝났으면 Windows 음성 인식기(SAPI)로 받아쓴다. 인식기가 없는 PC(Windows 11 24H2 이후 기본 미포함)에서는
        // 결과가 비어 나오므로, 조용히 실패하지 않고 원인을 알린다.
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            RecorderHub.upd {
                copy(
                    phase = RecPhase.Failed, level = 0f, fraction = null,
                    message = "이 PC에는 Windows 음성 인식 엔진이 없어 받아쓰기를 할 수 없습니다. 도움말 → 음성 인식 엔진 설치 안내를 확인해 주세요.",
                )
            }
            return
        }
        RecorderHub.upd { copy(phase = RecPhase.Finishing, message = "받아쓰는 중", level = 0f, fraction = 0f) }
        val stt = try {
            Stt.transcribe(
                store, pcm,
                com.hdlee73.englishstudy.docvoice.core.SttOptions(if (lang == LiveLang.KO) "ko" else "", com.hdlee73.englishstudy.docvoice.core.WhisperSize.SMALL, false),
                { scope.isActive },
            ) { m, f -> RecorderHub.upd { copy(message = m, fraction = f) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            RecorderHub.upd { copy(phase = RecPhase.Failed, level = 0f, fraction = null, message = "받아쓰기 오류: ${e.message ?: e.javaClass.simpleName}") }
            return
        }

        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        RecorderHub.upd { copy(phase = RecPhase.Stopped, seconds = pcm.seconds, segments = stt.segments, wav = null, fraction = null, message = captureNotice, title = "녹음_$stamp") }
    }

    private class Route(val bt: Boolean, val name: String, val device: android.media.AudioDeviceInfo?, val cleanup: () -> Unit)

    /** 블루투스 마이크가 있으면(설정에 따라) 통화용 경로로 연결하고, 없으면 내장 마이크를 쓴다. */
    private suspend fun routeInput(useBt: Boolean): Route {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val inputs = try { am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).toList() } catch (_: Exception) { emptyList() }
        // Windows 기본 입력 장치가 소리를 내보내지 않는 PC 가 있어(사용자 PC 의 USB 마이크), 앱 시작 때 확인해 둔
        // 소리가 실제로 들어오는 장치를 우선 쓴다. 없으면 Windows 기본(내장) 마이크를 쓴다.
        val working = android.media.AudioDevices.autoInputDevice()
        val builtin = working ?: inputs.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val builtinName = working?.productName?.toString()?.takeIf { it.isNotBlank() } ?: "내장 마이크"
        val builtinRoute = Route(false, builtinName, builtin) {}
        if (!useBt) return builtinRoute
        val bt = inputs.firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (android.os.Build.VERSION.SDK_INT >= 31 && it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
        } ?: return builtinRoute
        val label = "블루투스 · " + (bt.productName?.toString()?.takeIf { it.isNotBlank() } ?: "마이크")
        return try {
            am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val dev = am.availableCommunicationDevices.firstOrNull { it.id == bt.id }
                    ?: am.availableCommunicationDevices.firstOrNull { it.type == bt.type }
                if (dev == null || !am.setCommunicationDevice(dev)) { am.mode = android.media.AudioManager.MODE_NORMAL; return builtinRoute }
                kotlinx.coroutines.delay(600)
                Route(true, label, bt) { try { am.clearCommunicationDevice(); am.mode = android.media.AudioManager.MODE_NORMAL } catch (_: Exception) {} }
            } else {
                @Suppress("DEPRECATION") am.startBluetoothSco()
                @Suppress("DEPRECATION") am.isBluetoothScoOn = true
                kotlinx.coroutines.delay(1500)
                Route(true, label, bt) {
                    @Suppress("DEPRECATION") try { am.stopBluetoothSco(); am.isBluetoothScoOn = false; am.mode = android.media.AudioManager.MODE_NORMAL } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
            try { am.mode = android.media.AudioManager.MODE_NORMAL } catch (_: Exception) {}
            builtinRoute
        }
    }

    private fun prep(msg: String, f: Float?) {
        RecorderHub.upd { copy(phase = RecPhase.Preparing, message = msg, fraction = f) }
    }

    private fun fmt(sec: Double): String {
        val s = sec.toInt()
        return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "녹음", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, com.hdlee73.englishstudy.MainActivity::class.java).putExtra(DocVoiceLink.EXTRA_OPEN, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.dv_ic_notif)
            .setContentTitle("녹음 중")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "중지", stop)
            .build()
    }

    companion object {
        const val ACTION_START = "com.hdlee73.englishstudy.docvoice.REC_START"
        const val ACTION_STOP = "com.hdlee73.englishstudy.docvoice.REC_STOP"
        const val ACTION_PAUSE = "com.hdlee73.englishstudy.docvoice.REC_PAUSE"
        private const val CHANNEL = "rec"
        private const val NOTIF_ID = 3
        /** 한 번에 녹음할 수 있는 최대 길이(초). 넘으면 자동으로 멈추고 알린다. */
        private const val MAX_RECORD_SECONDS = 60 * 60
        private const val MAX_RECORD_MESSAGE = "녹음 최대 길이(60분)에 도달해 자동으로 멈췄어요."
    }
}
