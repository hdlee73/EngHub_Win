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

/** 16kHz 모노 16bit PCM 을 담는 늘어나는 버퍼. */
class PcmBuffer(initial: Int = 1 shl 20) : PcmSource {
    var data = ShortArray(initial)
        private set
    override var size = 0
        private set

    fun add(v: Short) {
        if (size == data.size) data = data.copyOf(data.size * 2)
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

    /**
     * 오디오/동영상 파일을 16kHz 모노 PCM 으로 디코딩한다.
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
            val isWav = rawBytes.size >= 12 &&
                rawBytes[0] == 'R'.code.toByte() && rawBytes[1] == 'I'.code.toByte() &&
                rawBytes[2] == 'F'.code.toByte() && rawBytes[3] == 'F'.code.toByte()
            if (isWav) {
                AudioSystem.getAudioInputStream(ByteArrayInputStream(rawBytes)).use { ais ->
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
            } else {
                val bitstream = Bitstream(BufferedInputStream(ByteArrayInputStream(rawBytes)))
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
                bitstream.close()
            }
            ok = true
        } catch (e: kotlinx.coroutines.CancellationException) {
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
}
