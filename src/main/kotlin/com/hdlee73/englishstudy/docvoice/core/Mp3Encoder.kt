package com.hdlee73.englishstudy.docvoice.core

import java.io.File
import java.io.OutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * 소리 파일(m4a, webm 등)을 읽어 MP3 출력 스트림으로 내보낸다.
 *
 * ffmpeg 이 있으면 MP3 로 변환한다. 없을 때는 **절대 잘못된 파일을 쓰지 않는다**:
 * MP3 입력은 그대로 복사하고, JavaSound 이 읽을 수 있는 WAV/AIFF/AU 는 유효한 WAV 로 다시 쓴다.
 * 그 밖의 형식(m4a/webm/ogg …)은 순수 JVM 으로 바꿀 수 없으므로 조용히 넘어가지 않고 [EncodeException] 을 던진다.
 */
object Mp3Encoder {
    class EncodeException(message: String) : Exception(message)

    const val MP3 = "mp3"
    const val WAV = "wav"

    fun mimeFor(extension: String): String = if (extension == WAV) "audio/wav" else "audio/mpeg"

    private enum class Format { WAV, AIFF, AU, MP3, OTHER }

    /**
     * [encode] 가 [input] 을 실제로 어떤 확장자로 내보내는지 미리 알려 준다 ([MP3] 또는 [WAV]).
     * 어떤 유효한 파일도 만들 수 없으면 [EncodeException] 을 던진다.
     */
    fun outputExtension(input: File): String {
        if (!input.exists() || input.length() == 0L) throw EncodeException("소리가 비어 있어요.")
        if (Ffmpeg.available) return MP3
        return when (sniff(input)) {
            Format.MP3 -> MP3
            Format.WAV, Format.AIFF, Format.AU -> WAV
            Format.OTHER -> throw EncodeException(
                "${label(input)} 파일은 ffmpeg 없이 MP3 로 바꿀 수 없어요. ffmpeg 을 설치한 뒤 다시 시도해 주세요."
            )
        }
    }

    /**
     * [outputExtension] 이 알려 준 확장자로 유효한 소리 파일을 [out] 에 쓴다. 실제로 쓴 확장자를 돌려준다.
     * [active] 가 false 가 되면 [kotlinx.coroutines.CancellationException] 을 던진다.
     */
    fun encode(input: File, out: OutputStream, active: () -> Boolean, progress: (Float) -> Unit): String {
        val ext = outputExtension(input)
        if (ext == WAV) {
            writeWav(input, out, active, progress)
            return WAV
        }
        val tmpOut = File(input.parentFile ?: File(System.getProperty("java.io.tmpdir")), input.nameWithoutExtension + "_conv.mp3")
        val converted = runCatching {
            val cmd = Ffmpeg.command ?: return@runCatching false
            val proc = ProcessBuilder(
                cmd, "-y", "-i", input.absolutePath, "-vn", "-acodec", "libmp3lame", "-q:a", "2", tmpOut.absolutePath
            ).redirectErrorStream(true).start()
            while (proc.isAlive) {
                if (!active()) {
                    proc.destroyForcibly()
                    throw kotlinx.coroutines.CancellationException("취소됨")
                }
                Thread.sleep(100)
            }
            proc.exitValue() == 0 && tmpOut.exists() && tmpOut.length() > 0L
        }.getOrDefault(false)

        try {
            // ffmpeg 이 없거나 실패했으면 MP3 원본만 그대로 복사한다. 그 밖의 형식을 .mp3 로 쓰면 잘못된 파일이 된다.
            val src = if (converted) tmpOut else {
                if (sniff(input) != Format.MP3) throw EncodeException("ffmpeg 변환에 실패해 유효한 MP3 를 만들지 못했어요.")
                input
            }
            copy(src, out, active, progress)
        } finally {
            tmpOut.delete()
        }
        return MP3
    }

    // ---- 입력 형식 판별 -------------------------------------------------------

    private fun sniff(file: File): Format {
        val head = ByteArray(16)
        val n = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(0)
        if (n >= 12 && head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() &&
            head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte()) return Format.WAV
        if (n >= 12 && head[0] == 'F'.code.toByte() && head[1] == 'O'.code.toByte() &&
            head[2] == 'R'.code.toByte() && head[3] == 'M'.code.toByte()) return Format.AIFF
        if (n >= 4 && head[0] == '.'.code.toByte() && head[1] == 's'.code.toByte() &&
            head[2] == 'n'.code.toByte() && head[3] == 'd'.code.toByte()) return Format.AU
        if (n >= 3 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) return Format.MP3
        if (n >= 2 && (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0) return Format.MP3
        return Format.OTHER
    }

    private fun label(file: File): String {
        val ext = file.extension.lowercase()
        return if (ext.isNotEmpty()) ".$ext" else "이"
    }

    // ---- 출력 ---------------------------------------------------------------

    private fun writeWav(input: File, out: OutputStream, active: () -> Boolean, progress: (Float) -> Unit) {
        AudioSystem.getAudioInputStream(input).use { ais ->
            val base = ais.format
            val target = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED, base.sampleRate, 16, base.channels, base.channels * 2, base.sampleRate, false
            )
            val frames = ais.frameLength
            if (frames == AudioSystem.NOT_SPECIFIED.toLong()) throw EncodeException("소리 길이를 알 수 없어 WAV 로 저장할 수 없어요.")
            val dataLen = frames * 2L * base.channels
            writeRiffHeader(out, dataLen, base.sampleRate.toInt(), base.channels)
            AudioSystem.getAudioInputStream(target, ais).use { pcm ->
                val buf = ByteArray(64 * 1024)
                var written = 0L
                while (true) {
                    if (!active()) throw kotlinx.coroutines.CancellationException("취소됨")
                    val n = pcm.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    progress((written.toFloat() / dataLen).coerceIn(0f, 1f))
                }
            }
        }
    }

    private fun writeRiffHeader(out: OutputStream, dataLen: Long, sampleRate: Int, channels: Int) {
        val byteRate = sampleRate * channels * 2
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt((36 + dataLen).toInt()).put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(channels.toShort())
        header.putInt(sampleRate).putInt(byteRate).putShort((channels * 2).toShort()).putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataLen.toInt())
        out.write(header.array())
    }

    private fun copy(src: File, out: OutputStream, active: () -> Boolean, progress: (Float) -> Unit) {
        val total = src.length().coerceAtLeast(1L)
        var copied = 0L
        src.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                if (!active()) throw kotlinx.coroutines.CancellationException("취소됨")
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                copied += n
                progress((copied.toFloat() / total).coerceIn(0f, 1f))
            }
        }
    }
}
