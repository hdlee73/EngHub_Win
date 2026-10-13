package com.hdlee73.englishstudy.docvoice.core

import java.io.File
import java.io.OutputStream

/** 소리 파일(m4a, webm 등)을 읽어 mp3 출력 스트림으로 내보낸다. */
object Mp3Encoder {
    class EncodeException(message: String) : Exception(message)

    fun encode(input: File, out: OutputStream, active: () -> Boolean, progress: (Float) -> Unit) {
        if (!input.exists() || input.length() == 0L) throw EncodeException("소리가 비어 있어요.")
        val tmpOut = File(input.parentFile, input.nameWithoutExtension + "_conv.mp3")
        try {
            val converted = runCatching {
                val proc = ProcessBuilder(
                    "ffmpeg", "-y", "-i", input.absolutePath, "-vn", "-acodec", "libmp3lame", "-q:a", "2", tmpOut.absolutePath
                ).redirectErrorStream(true).start()
                while (proc.isAlive) {
                    if (!active()) {
                        proc.destroyForcibly()
                        throw kotlinx.coroutines.CancellationException()
                    }
                    Thread.sleep(100)
                }
                proc.exitValue() == 0 && tmpOut.exists() && tmpOut.length() > 0L
            }.getOrDefault(false)

            val src = if (converted) tmpOut else input
            val total = src.length().coerceAtLeast(1L)
            var copied = 0L
            src.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (!active()) throw kotlinx.coroutines.CancellationException()
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    copied += n
                    progress((copied.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        } finally {
            tmpOut.delete()
        }
    }
}
