package com.hdlee73.englishstudy.docvoice.core

import android.content.Context
import android.net.Uri
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.UnsupportedAudioFileException

/** 16kHz 모노 16bit PCM 을 구간별로 읽을 수 있는 소스. */
interface PcmSource {
    val size: Int
    val seconds: Double get() = size / AudioDecoder.SAMPLE_RATE.toDouble()
    fun toFloats(from: Int = 0, to: Int = size): FloatArray
}

/**
 * 디코딩 결과를 힙 대신 임시 파일에 쌓는 PCM. 아주 긴 오디오도 메모리 부족 없이 처리한다.
 * [add] 로 다 쓴 뒤 [finishWriting] 을 부르고 나서 읽는다. 다 쓰면 [close] 로 임시 파일을 지운다.
 */
class FilePcm(private val file: java.io.File) : PcmSource, java.io.Closeable {
    private var out: java.io.DataOutputStream? =
        java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(file), 1 shl 16))
    private var raf: java.io.RandomAccessFile? = null
    override var size = 0
        private set

    fun add(v: Short) {
        out!!.writeShort(java.lang.Short.reverseBytes(v).toInt()) // little-endian
        size++
    }

    fun finishWriting() {
        out?.close(); out = null
        if (raf == null) raf = java.io.RandomAccessFile(file, "r")
    }

    override fun toFloats(from: Int, to: Int): FloatArray {
        val r = raf ?: throw IllegalStateException("finishWriting() 먼저")
        val n = to - from
        val bytes = ByteArray(n * 2)
        r.seek(from * 2L)
        r.readFully(bytes)
        val sb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = sb.get(i) / 32768f
        return out
    }

    override fun close() {
        try { out?.close() } catch (_: Exception) {}
        try { raf?.close() } catch (_: Exception) {}
        file.delete()
    }
}

/**
 * 16kHz 모노 16bit PCM 을 담는 늘어나는 버퍼.
 * [maxSamples] 를 넘기면 더 받지 않고 [full] 을 true 로 세운다 (무한 녹음으로 메모리가 터지는 것을 막는다).
 */
class PcmBuffer(initial: Int = 1 shl 20, private val maxSamples: Int = Int.MAX_VALUE) : PcmSource {
    var data = ShortArray(minOf(initial.coerceAtLeast(1), maxSamples.coerceAtLeast(1)))
        private set
    override var size = 0
        private set

    /** [maxSamples] 까지 다 찼으면 true. 호출한 쪽에서 녹음을 멈추고 사용자에게 알린다. */
    var full = false
        private set

    fun add(v: Short) {
        if (size >= maxSamples) { full = true; return }
        if (size == data.size) {
            val next = minOf(data.size.toLong() * 2, maxSamples.toLong()).toInt().coerceAtLeast(data.size + 1)
            data = data.copyOf(next)
        }
        data[size++] = v
    }

    override fun toFloats(from: Int, to: Int): FloatArray {
        val out = FloatArray(to - from)
        for (i in out.indices) out[i] = data[from + i] / 32768f
        return out
    }
}

/** 선형 보간 스트리밍 리샘플러 (입력 → 16kHz). */
internal class Resampler(private val inRate: Int, private val outRate: Int = AudioDecoder.SAMPLE_RATE) {
    private val step = inRate.toDouble() / outRate
    private var nextPos = 0.0
    private var consumed = 0L
    private var carry: Short = 0
    private var hasCarry = false

    fun push(m: ShortArray, n: Int, out: (Short) -> Unit) {
        if (n <= 0) return
        if (inRate == outRate) {
            for (i in 0 until n) out(m[i])
            return
        }
        val extra = if (hasCarry) 1 else 0
        val startAbs = consumed - extra
        fun at(idx: Int): Short = if (hasCarry) (if (idx == 0) carry else m[idx - 1]) else m[idx]
        val len = n + extra
        while (true) {
            val p = nextPos
            val i = Math.floor(p).toLong() - startAbs
            if (i + 1 >= len) break
            val frac = p - Math.floor(p)
            val a = at(i.toInt()).toInt()
            val b = at(i.toInt() + 1).toInt()
            out((a + (b - a) * frac).toInt().toShort())
            nextPos += step
        }
        carry = m[n - 1]
        hasCarry = true
        consumed += n
    }
}

object AudioDecoder {
    const val SAMPLE_RATE = 16000

    class DecodeException(message: String) : Exception(message)

    /** 컨테이너 형식. 순수 JVM 으로 풀 수 있는 것과 ffmpeg 이 있어야 하는 것을 나눈다. */
    private enum class Kind(val label: String) {
        JAVA_SOUND("WAV/AIFF"),
        MP3("MP3"),
        NEEDS_FFMPEG("압축 오디오"),
    }

    /**
     * 오디오/동영상 파일을 16kHz 모노 PCM 으로 디코딩한다.
     *
     * - WAV / AIFF / AU: JavaSound 로 직접 디코딩
     * - MP3: JLayer 로 디코딩
     * - m4a / webm / ogg 등: ffmpeg 이 있으면 변환, 없으면 형식을 밝혀 분명한 오류를 던진다
     */
    fun decode(
        context: Context,
        uri: Uri,
        isActive: () -> Boolean = { true },
        onProgress: (Float) -> Unit = {},
    ): FilePcm {
        val rawBytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw DecodeException("파일을 열 수 없습니다.")
        } catch (e: DecodeException) {
            throw e
        } catch (e: Exception) {
            throw DecodeException("파일을 열 수 없습니다. (${e.message})")
        }
        val pcm = FilePcm(java.io.File.createTempFile("stt-", ".pcm", context.cacheDir))
        var ok = false
        try {
            when (detect(rawBytes)) {
                Kind.JAVA_SOUND -> decodeJavaSound(rawBytes, pcm, isActive, onProgress)
                Kind.MP3 -> decodeMp3(rawBytes, pcm, isActive, onProgress)
                Kind.NEEDS_FFMPEG -> decodeFfmpeg(context, rawBytes, uri, pcm, isActive, onProgress)
            }
            ok = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: DecodeException) {
            throw e
        } catch (e: Exception) {
            throw DecodeException("오디오 디코딩 실패: ${e.message}")
        } finally {
            if (!ok) pcm.close()
        }
        pcm.finishWriting()
        if (pcm.size == 0) { pcm.close(); throw DecodeException("오디오에서 소리를 읽지 못했습니다.") }
        onProgress(1f)
        return pcm
    }

    // ---- 형식 판별 -----------------------------------------------------------

    private fun detect(bytes: ByteArray): Kind {
        if (bytes.size >= 12 && match(bytes, 0, 'R', 'I', 'F', 'F')) return Kind.JAVA_SOUND // RIFF/WAV
        if (bytes.size >= 12 && match(bytes, 0, 'F', 'O', 'R', 'M')) return Kind.JAVA_SOUND // AIFF/AIFC
        if (bytes.size >= 4 && match(bytes, 0, '.', 's', 'n', 'd')) return Kind.JAVA_SOUND // AU
        if (bytes.size >= 3 && match(bytes, 0, 'I', 'D', '3')) return Kind.MP3 // ID3 태그
        if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xE0) == 0xE0) return Kind.MP3
        return Kind.NEEDS_FFMPEG
    }

    private fun match(bytes: ByteArray, at: Int, vararg sig: Char): Boolean {
        for (i in sig.indices) if (bytes[at + i] != sig[i].code.toByte()) return false
        return true
    }

    /** 오류 메시지에 넣을 사람이 읽는 형식 이름. */
    private fun label(uri: Uri, kind: Kind): String {
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return if (ext.isNotEmpty() && ext.length <= 5) ".$ext" else kind.label
    }

    /** 순수 JVM 으로 풀 수 없는 형식은 ffmpeg 이 있어야 한다. 없으면 조용히 넘어가지 않고 분명히 알린다. */
    private fun decodeFfmpeg(
        context: Context,
        rawBytes: ByteArray,
        uri: Uri,
        pcm: FilePcm,
        isActive: () -> Boolean,
        onProgress: (Float) -> Unit,
    ) {
        val cmd = Ffmpeg.command
            ?: throw DecodeException(
                "${label(uri, Kind.NEEDS_FFMPEG)} 파일은 ffmpeg 없이 열 수 없어요. ffmpeg 을 설치한 뒤 다시 시도해 주세요."
            )
        val input = java.io.File.createTempFile("stt-", ".bin", context.cacheDir)
        val proc = try {
            input.writeBytes(rawBytes)
            ProcessBuilder(
                cmd, "-v", "error", "-i", input.absolutePath, "-f", "s16le", "-ac", "1", "-ar", "16000", "-"
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: Exception) {
            input.delete()
            throw DecodeException("ffmpeg 을 실행하지 못했어요. (${e.message})")
        }
        try {
            proc.outputStream.close()
            val ins = proc.inputStream
            val buf = ByteArray(1 shl 16)
            var readBytes = 0L
            while (true) {
                if (!isActive()) { proc.destroyForcibly(); throw kotlinx.coroutines.CancellationException("취소됨") }
                val n = ins.read(buf)
                if (n <= 0) break
                readBytes += n
                val sb = java.nio.ByteBuffer.wrap(buf, 0, n).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                while (sb.hasRemaining()) pcm.add(sb.get())
                onProgress((readBytes.toFloat() / rawBytes.size.coerceAtLeast(1)).coerceIn(0f, 1f))
            }
            if (pcm.size == 0) {
                throw DecodeException(
                    "${label(uri, Kind.NEEDS_FFMPEG)} 파일을 읽지 못했어요. ffmpeg 이 이 형식을 지원하는지 확인해 주세요."
                )
            }
        } finally {
            try { proc.destroy() } catch (_: Exception) {}
            input.delete()
        }
    }

    // ---- JavaSound (WAV / AIFF / AU) ----------------------------------------

    private fun decodeJavaSound(
        rawBytes: ByteArray,
        pcm: FilePcm,
        isActive: () -> Boolean,
        onProgress: (Float) -> Unit,
    ) {
        val ais = try {
            AudioSystem.getAudioInputStream(ByteArrayInputStream(rawBytes))
        } catch (e: UnsupportedAudioFileException) {
            throw DecodeException("지원하지 않는 오디오 형식이에요. (${e.message})")
        }
        ais.use {
            val base = ais.format
            val decodedFormat = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                base.sampleRate,
                16,
                base.channels,
                base.channels * 2,
                base.sampleRate,
                false
            )
            AudioSystem.getAudioInputStream(decodedFormat, ais).use { pcmStream ->
                val ch = decodedFormat.channels.coerceAtLeast(1)
                val resampler = Resampler(decodedFormat.sampleRate.toInt().coerceAtLeast(8000))
                val buf = ByteArray(8192)
                val mono = ShortArray(4096)
                var readBytes = 0L
                while (true) {
                    if (!isActive()) throw kotlinx.coroutines.CancellationException("취소됨")
                    val n = pcmStream.read(buf)
                    if (n <= 0) break
                    readBytes += n
                    val bb = java.nio.ByteBuffer.wrap(buf, 0, n).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val frames = bb.remaining() / ch
                    for (f in 0 until frames) {
                        var sum = 0
                        for (c in 0 until ch) sum += bb.get(f * ch + c).toInt()
                        mono[f] = (sum / ch).toShort()
                    }
                    resampler.push(mono, frames) { pcm.add(it) }
                    onProgress((readBytes.toFloat() / rawBytes.size.coerceAtLeast(1)).coerceIn(0f, 1f))
                }
            }
        }
    }

    // ---- MP3 (JLayer) -------------------------------------------------------

    private fun decodeMp3(
        rawBytes: ByteArray,
        pcm: FilePcm,
        isActive: () -> Boolean,
        onProgress: (Float) -> Unit,
    ) {
        val bitstream = Bitstream(BufferedInputStream(ByteArrayInputStream(rawBytes)))
        try {
            val decoder = Decoder()
            var resampler: Resampler? = null
            var mono = ShortArray(4096)
            var framesDecoded = 0
            while (true) {
                if (!isActive()) throw kotlinx.coroutines.CancellationException("취소됨")
                val header = bitstream.readFrame() ?: break
                val output = decoder.decodeFrame(header, bitstream) as? SampleBuffer
                if (output != null) {
                    val rate = output.sampleFrequency.coerceAtLeast(8000)
                    val ch = output.channelCount.coerceAtLeast(1)
                    if (resampler == null) resampler = Resampler(rate)
                    val buf = output.buffer
                    val len = output.bufferLength
                    val frames = len / ch
                    if (mono.size < frames) mono = ShortArray(frames)
                    for (f in 0 until frames) {
                        var sum = 0
                        for (c in 0 until ch) sum += buf[f * ch + c].toInt()
                        mono[f] = (sum / ch).toShort()
                    }
                    resampler.push(mono, frames) { pcm.add(it) }
                }
                bitstream.closeFrame()
                framesDecoded++
                if (framesDecoded % 50 == 0) {
                    onProgress((framesDecoded.toFloat() / (rawBytes.size / 400f).coerceAtLeast(1f)).coerceIn(0f, 0.99f))
                }
            }
        } finally {
            bitstream.close()
        }
    }
}
