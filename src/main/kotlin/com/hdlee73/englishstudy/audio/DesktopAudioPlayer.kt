package com.hdlee73.englishstudy.audio

import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.Header
import javazoom.jl.decoder.SampleBuffer
import javazoom.jl.player.AudioDevice
import javazoom.jl.player.FactoryRegistry
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import kotlin.concurrent.thread

/**
 * Universal desktop audio player supporting MP3, WAV and other common audio files.
 * Provides accurate position tracking, seeking, pause/resume, volume, and A-B looping.
 * Runs on a dedicated daemon thread so navigation across app tabs never halts playback.
 */
class DesktopAudioPlayer {
    interface Listener {
        fun onPlaybackStateChanged(isPlaying: Boolean)
        fun onProgress(positionMs: Long, durationMs: Long)
        fun onTrackCompleted()
        fun onError(message: String)
    }

    var listener: Listener? = null

    @Volatile
    var isPlaying: Boolean = false
        private set

    @Volatile
    var currentFile: File? = null
        private set

    @Volatile
    var durationMs: Long = 0L
        private set

    @Volatile
    var currentPositionMs: Long = 0L
        private set

    // A-B Repeat state
    @Volatile var aMs: Long = -1L
    @Volatile var bMs: Long = -1L
    @Volatile var abRepeatLimit: Int = -1 // -1 = infinite
    @Volatile var abRepeatCount: Int = 0
    @Volatile var gapMs: Long = 0L

    private var playThread: Thread? = null
    private var stopRequested = false
    private var pauseRequested = false
    private var seekRequestedMs: Long? = null

    // Volume (0.0f to 1.0f)
    var volume: Float = 1.0f

    fun load(file: File) {
        stop()
        currentFile = file
        currentPositionMs = 0L
        aMs = -1L
        bMs = -1L
        abRepeatCount = 0
        durationMs = estimateDuration(file)
        listener?.onProgress(0L, durationMs)
    }

    fun play() {
        val file = currentFile ?: return
        if (isPlaying) return
        stopRequested = false
        pauseRequested = false
        isPlaying = true
        listener?.onPlaybackStateChanged(true)

        playThread = thread(isDaemon = true, name = "EngHub-AudioPlayer") {
            try {
                if (file.extension.equals("wav", ignoreCase = true)) {
                    playWav(file)
                } else {
                    playMp3(file)
                }
            } catch (e: Exception) {
                if (!stopRequested) {
                    listener?.onError(e.message ?: "재생 오류가 발생했습니다.")
                }
            } finally {
                isPlaying = false
                listener?.onPlaybackStateChanged(false)
                if (!stopRequested && !pauseRequested && (currentPositionMs >= durationMs - 500L || durationMs == 0L)) {
                    listener?.onTrackCompleted()
                }
            }
        }
    }

    fun pause() {
        pauseRequested = true
        isPlaying = false
        listener?.onPlaybackStateChanged(false)
    }

    fun toggle() {
        if (isPlaying) pause() else play()
    }

    fun stop() {
        stopRequested = true
        pauseRequested = false
        isPlaying = false
        playThread?.interrupt()
        playThread = null
        currentPositionMs = 0L
        listener?.onPlaybackStateChanged(false)
        listener?.onProgress(0L, durationMs)
    }

    fun seekTo(positionMs: Long) {
        val target = if (durationMs > 0L) positionMs.coerceIn(0L, durationMs) else positionMs.coerceAtLeast(0L)
        seekRequestedMs = target
        currentPositionMs = target
        listener?.onProgress(target, durationMs)
    }

    fun setRange(startMs: Long, endMs: Long) {
        aMs = startMs
        bMs = endMs
        abRepeatCount = 0
        if (startMs >= 0) {
            seekTo(startMs)
        }
    }

    fun clearRange() {
        aMs = -1L
        bMs = -1L
        abRepeatCount = 0
    }

    private fun estimateDuration(file: File): Long {
        return try {
            if (file.extension.equals("wav", ignoreCase = true)) {
                AudioSystem.getAudioFileFormat(file)?.let { format ->
                    val frames = format.frameLength
                    val rate = format.format.frameRate
                    if (rate > 0) ((frames / rate) * 1000).toLong() else 0L
                } ?: 0L
            } else {
                // MP3 Bitstream estimate
                FileInputStream(file).use { fis ->
                    val bs = Bitstream(BufferedInputStream(fis))
                    val header = bs.readFrame()
                    if (header != null) {
                        val frameMs = header.ms_per_frame()
                        val bitRate = header.bitrate()
                        val fileSize = file.length()
                        if (bitRate > 0) {
                            (fileSize * 8L / bitRate) // duration in ms approx
                        } else 0L
                    } else 0L
                }
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun playMp3(file: File) {
        while (!stopRequested) {
            val startSeek = seekRequestedMs
            seekRequestedMs = null

            FileInputStream(file).use { fis ->
                val bis = BufferedInputStream(fis)
                val bitstream = Bitstream(bis)
                val decoder = Decoder()
                val audioDevice: AudioDevice = FactoryRegistry.systemRegistry().createAudioDevice()
                audioDevice.open(decoder)

                var frameIndex = 0
                var currentMs = 0.0

                // Seek handling
                if (startSeek != null && startSeek > 0) {
                    var h = bitstream.readFrame()
                    while (h != null && !stopRequested) {
                        val frameMs = h.ms_per_frame().toDouble()
                        if (currentMs + frameMs >= startSeek) {
                            bitstream.unreadFrame()
                            break
                        }
                        currentMs += frameMs
                        bitstream.closeFrame()
                        h = bitstream.readFrame()
                    }
                }

                while (!stopRequested && !pauseRequested) {
                    // Check if new seek is requested
                    if (seekRequestedMs != null) {
                        audioDevice.close()
                        break // restart inner loop with new seek
                    }

                    // A-B Repeat check
                    if (aMs >= 0 && bMs > aMs && currentMs >= bMs) {
                        abRepeatCount++
                        if (abRepeatLimit < 0 || abRepeatCount < abRepeatLimit) {
                            if (gapMs > 0) {
                                Thread.sleep(gapMs)
                            }
                            seekTo(aMs)
                            audioDevice.close()
                            break
                        }
                    }

                    val header = bitstream.readFrame() ?: break
                    val frameMs = header.ms_per_frame().toDouble()
                    val output = decoder.decodeFrame(header, bitstream) as? SampleBuffer
                    if (output != null) {
                        audioDevice.write(output.buffer, 0, output.bufferLength)
                    }
                    bitstream.closeFrame()

                    currentMs += frameMs
                    frameIndex++
                    currentPositionMs = currentMs.toLong()
                    if (frameIndex % 8 == 0) {
                        listener?.onProgress(currentPositionMs, durationMs)
                    }
                }

                audioDevice.flush()
                audioDevice.close()
            }

            if (pauseRequested || stopRequested || seekRequestedMs == null) {
                break
            }
        }
    }

    private fun playWav(file: File) {
        val audioInputStream: AudioInputStream = AudioSystem.getAudioInputStream(file)
        val clip: Clip = AudioSystem.getClip()
        clip.open(audioInputStream)

        val targetSeek = seekRequestedMs ?: currentPositionMs
        if (targetSeek > 0) {
            clip.microsecondPosition = targetSeek * 1000L
        }
        seekRequestedMs = null

        clip.start()
        while (clip.isRunning && !stopRequested && !pauseRequested) {
            if (seekRequestedMs != null) {
                val s = seekRequestedMs!!
                seekRequestedMs = null
                clip.microsecondPosition = s * 1000L
            }
            currentPositionMs = clip.microsecondPosition / 1000L
            listener?.onProgress(currentPositionMs, durationMs)
            Thread.sleep(50)
        }
        if (pauseRequested || stopRequested) {
            clip.stop()
        }
        clip.close()
        audioInputStream.close()
    }
}
