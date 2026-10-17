package com.hdlee73.englishstudy.listening

import android.app.Application
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.roundToInt

/**
 * Owns the player so audio keeps going continuously across all tabs and background operations.
 * Modification (1): PlaybackService is a process-wide singleton that never stops when switching tabs.
 */
class PlaybackService : Service() {
    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    /** Lightweight desktop player interface matching ExoPlayer calls used by ListeningScreen and SubtitleSheet. */
    inner class DesktopPlayer {
        @Volatile var playWhenReady: Boolean = false
            private set
        @Volatile var isPlaying: Boolean = false
            private set
        @Volatile var currentMediaItemIndex: Int = 0
            private set
        @Volatile var currentPosition: Long = 0L
            private set
        @Volatile var duration: Long = 0L
            private set
        @Volatile var speed: Float = 1.0f
        @Volatile var shuffleModeEnabled: Boolean = false
        @Volatile var repeatMode: Int = 0
        @Volatile var pauseAtEndOfMediaItems: Boolean = false

        private val queue = mutableListOf<TrackStore.Entry>()
        @Volatile private var loadedUri: String? = null
        @Volatile private var pcmBytes: ByteArray = ByteArray(0)
        @Volatile private var sampleRate: Int = 44100
        @Volatile private var channels: Int = 2
        private var playThread: Thread? = null
        @Volatile private var threadGen = 0
        /** A decode/playback problem to show once; the Listening screen polls [consumeError]. */
        private var pendingError: String? = null

        // Decoding runs here, never on the UI thread, so a long track no longer freezes the screen.
        private val decoder: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "EngHub-AudioDecode").apply { isDaemon = true } }
        private var decodeFuture: java.util.concurrent.Future<*>? = null
        private var decodeGen = 0

        val mediaItemCount: Int
            @Synchronized get() = queue.size

        @Synchronized
        fun getEntryAt(index: Int): TrackStore.Entry? = queue.getOrNull(index)

        val currentEntry: TrackStore.Entry?
            @Synchronized get() = queue.getOrNull(currentMediaItemIndex)

        /** Returns the pending error (once) and clears it, or null. */
        @Synchronized
        fun consumeError(): String? = pendingError.also { pendingError = null }

        /** Next index in the current order: random when shuffle is on, otherwise the following item. */
        @Synchronized
        fun nextIndex(): Int {
            val count = queue.size
            if (count == 0) return -1
            if (shuffleModeEnabled && count > 1) {
                var n: Int
                do { n = kotlin.random.Random.nextInt(count) } while (n == currentMediaItemIndex)
                return n
            }
            return (currentMediaItemIndex + 1) % count
        }

        /** Like ExoPlayer's hasNextMediaItem: true while something can still play after this one. */
        @Synchronized
        fun hasNextMediaItem(): Boolean {
            if (queue.isEmpty()) return false
            if (repeatMode == 2) return true
            if (shuffleModeEnabled && queue.size > 1) return true
            return currentMediaItemIndex + 1 < queue.size
        }

        /** Previous index in the current order: random when shuffle is on, otherwise the item before this one. */
        @Synchronized
        fun previousIndex(): Int {
            val count = queue.size
            if (count == 0) return -1
            if (shuffleModeEnabled && count > 1) {
                var n: Int
                do { n = kotlin.random.Random.nextInt(count) } while (n == currentMediaItemIndex)
                return n
            }
            return (currentMediaItemIndex - 1 + count) % count
        }

        @Synchronized
        fun hasPreviousMediaItem(): Boolean {
            if (queue.isEmpty()) return false
            if (repeatMode == 2) return true
            if (shuffleModeEnabled && queue.size > 1) return true
            return currentMediaItemIndex > 0
        }

        @Synchronized
        fun clearMediaItems() {
            stopAudioThread()
            queue.clear()
            currentMediaItemIndex = 0
            currentPosition = 0L
            duration = 0L
            loadedUri = null
            pcmBytes = ByteArray(0)
            decodeGen++
            decodeFuture?.cancel(true)
            decodeFuture = null
        }

        @Synchronized
        fun setMediaItems(items: List<TrackStore.Entry>, startIndex: Int, startPositionMs: Long) {
            stopAudioThread()
            queue.clear()
            queue.addAll(items)
            currentMediaItemIndex = startIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
            currentPosition = startPositionMs.coerceAtLeast(0L)
            loadCurrentEntryPcm()
            onTrackLoaded(currentEntry)
        }

        @Synchronized
        fun addMediaItems(items: List<TrackStore.Entry>) {
            val wasEmpty = queue.isEmpty()
            queue.addAll(items)
            if (wasEmpty && queue.isNotEmpty()) {
                currentMediaItemIndex = 0
                loadCurrentEntryPcm()
                onTrackLoaded(currentEntry)
            }
        }

        @Synchronized
        fun removeMediaItem(index: Int) {
            if (index !in queue.indices) return
            val removingCurrent = index == currentMediaItemIndex
            queue.removeAt(index)
            if (queue.isEmpty()) {
                clearMediaItems()
                onTrackLoaded(null)
                return
            }
            if (index < currentMediaItemIndex) {
                currentMediaItemIndex--
            } else if (currentMediaItemIndex >= queue.size) {
                currentMediaItemIndex = queue.lastIndex
            }
            if (removingCurrent) {
                val wasPlaying = isPlaying
                stopAudioThread()
                currentPosition = 0L
                loadCurrentEntryPcm()
                onTrackLoaded(currentEntry)
                if (wasPlaying) play()
            }
        }

        @Synchronized
        fun moveMediaItem(from: Int, to: Int) {
            if (from !in queue.indices || to !in queue.indices || from == to) return
            val item = queue.removeAt(from)
            queue.add(to, item)
            when (currentMediaItemIndex) {
                from -> currentMediaItemIndex = to
                in minOf(from, to)..maxOf(from, to) -> currentMediaItemIndex += if (from < to) -1 else 1
            }
        }

        @Synchronized
        fun seekToDefaultPosition(index: Int) {
            if (index !in queue.indices) return
            val wasPlaying = isPlaying
            savePosition()
            stopAudioThread()
            currentMediaItemIndex = index
            val entry = queue[index]
            val st = store.getState(entry.uri)
            currentPosition = if (st.done) 0L else st.positionMs.coerceAtLeast(0L)
            loadCurrentEntryPcm()
            onTrackLoaded(entry)
            if (wasPlaying) play()
        }

        @Synchronized
        fun seekTo(positionMs: Long) {
            val maxDur = if (duration > 0L) duration else Long.MAX_VALUE
            currentPosition = positionMs.coerceIn(0L, maxDur)
            lastPositionMs = currentPosition
        }

        @Synchronized
        fun prepare() {
            val entry = currentEntry ?: return
            if (loadedUri != entry.uri || pcmBytes.isEmpty()) loadCurrentEntryPcm()
        }

        @Synchronized
        fun play() {
            if (queue.isEmpty()) return
            prepare()
            if (currentEntry == null) return
            playWhenReady = true
            if (isPlaying) return
            isPlaying = true
            startTicker()
            val myGen = ++threadGen
            playThread = Thread({
                runAudioLoop(myGen)
            }, "EngHub-PlaybackThread").apply {
                isDaemon = true
                start()
            }
        }

        @Synchronized
        fun pause() {
            playWhenReady = false
            isPlaying = false
            threadGen++
            savePosition()
        }

        private fun stopAudioThread() {
            playWhenReady = false
            isPlaying = false
            threadGen++
        }

        @Synchronized
        private fun loadCurrentEntryPcm() {
            val entry = currentEntry
            if (entry == null) {
                loadedUri = null
                pcmBytes = ByteArray(0)
                duration = 0L
                return
            }
            if (loadedUri == entry.uri) return
            loadedUri = entry.uri
            pcmBytes = ByteArray(0)
            duration = 0L
            scheduleDecode(entry)
        }

        /** Starts a background decode of [entry]; the newest request wins. Call while holding this monitor. */
        private fun scheduleDecode(entry: TrackStore.Entry) {
            val gen = ++decodeGen
            decodeFuture?.cancel(true)
            decodeFuture = decoder.submit(java.lang.Runnable {
                val decoded = decodeEntry(entry)
                synchronized(this) {
                    if (gen != decodeGen) return@synchronized
                    if (currentEntry?.uri != entry.uri) return@synchronized
                    pcmBytes = decoded.data
                    sampleRate = decoded.sampleRate
                    channels = decoded.channels
                    duration = (decoded.data.size * 1000L) / (decoded.sampleRate * decoded.channels * 2).coerceAtLeast(1)
                }
            })
        }

        /** Reads the file and returns 16-bit PCM in its own sample rate. Runs off the UI thread; may throw. */
        private fun decodeEntry(entry: TrackStore.Entry): Decoded {
            val raw = contentResolver.openInputStream(Uri.parse(entry.uri))?.use { it.readBytes() }
                ?: File(Uri.parse(entry.uri).path ?: entry.uri).takeIf { it.exists() }?.readBytes()
                ?: throw IllegalStateException("파일을 열 수 없어요.")
            if (raw.isEmpty()) throw IllegalStateException("빈 파일이에요.")
            val isWav = raw.size >= 12 &&
                raw[0] == 'R'.code.toByte() && raw[1] == 'I'.code.toByte() &&
                raw[2] == 'F'.code.toByte() && raw[3] == 'F'.code.toByte()
            if (isWav) {
                AudioSystem.getAudioInputStream(ByteArrayInputStream(raw)).use { ais ->
                    val base = ais.format
                    val sr = base.sampleRate.toInt().coerceAtLeast(8000)
                    val ch = base.channels.coerceIn(1, 2)
                    val targetFmt = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sr.toFloat(), 16, ch, ch * 2, sr.toFloat(), false)
                    AudioSystem.getAudioInputStream(targetFmt, ais).use { pcmIn ->
                        return Decoded(readCapped(pcmIn), sr, ch)
                    }
                }
            }
            val bs = Bitstream(BufferedInputStream(ByteArrayInputStream(raw)))
            try {
                val dec = Decoder()
                val out = ByteArrayOutputStream()
                var sr = 44100
                var ch = 2
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException("취소됨")
                    val h = bs.readFrame() ?: break
                    val sb = dec.decodeFrame(h, bs) as? SampleBuffer
                    if (sb != null) {
                        sr = sb.sampleFrequency.coerceAtLeast(8000)
                        ch = sb.channelCount.coerceIn(1, 2)
                        val shorts = sb.buffer
                        val len = sb.bufferLength
                        if (out.size().toLong() + len * 2L > MAX_PCM_BYTES) {
                            throw IllegalStateException("오디오가 너무 커서 재생할 수 없어요.")
                        }
                        val byteBuf = ByteArray(len * 2)
                        var bi = 0
                        for (i in 0 until len) {
                            val v = shorts[i].toInt()
                            byteBuf[bi++] = (v and 0xFF).toByte()
                            byteBuf[bi++] = ((v ushr 8) and 0xFF).toByte()
                        }
                        out.write(byteBuf)
                    }
                    bs.closeFrame()
                }
                return Decoded(out.toByteArray(), sr, ch)
            } finally {
                bs.close()
            }
        }

        /** Reads all of [ins] but stops with a clear message rather than exhausting the heap on a huge file. */
        private fun readCapped(ins: java.io.InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(1 shl 16)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException("취소됨")
                val n = ins.read(buf)
                if (n < 0) break
                if (out.size().toLong() + n > MAX_PCM_BYTES) throw IllegalStateException("오디오가 너무 커서 재생할 수 없어요.")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        /** Waits for the background decode, retrying once here if it failed. Must be called off the monitor. */
        private fun ensureDecoded(myGen: Int): Boolean {
            val entry = synchronized(this) { currentEntry } ?: return false
            if (synchronized(this) { pcmBytes.isNotEmpty() && currentEntry?.uri == entry.uri }) return true
            synchronized(this) { loadCurrentEntryPcm() }
            val fut = synchronized(this) { decodeFuture }
            runCatching { fut?.get() }
            if (myGen != threadGen) return false
            if (synchronized(this) { pcmBytes.isNotEmpty() && currentEntry?.uri == entry.uri }) return true
            // One retry on this thread: a transient read/decoder failure should not silently end playback.
            return try {
                val decoded = decodeEntry(entry)
                synchronized(this) {
                    if (currentEntry?.uri == entry.uri) {
                        pcmBytes = decoded.data
                        sampleRate = decoded.sampleRate
                        channels = decoded.channels
                        duration = (decoded.data.size * 1000L) / (decoded.sampleRate * decoded.channels * 2).coerceAtLeast(1)
                    }
                }
                myGen == threadGen && synchronized(this) { pcmBytes.isNotEmpty() }
            } catch (e: Throwable) {
                synchronized(this) {
                    pendingError = "오디오를 열지 못했어요. (${e.message ?: e.javaClass.simpleName})"
                    isPlaying = false
                    playWhenReady = false
                }
                false
            }
        }

        /** Clears the "playing" flag as the audio thread ends so the next track (repeat one) can start. */
        private fun endedPlayback(myGen: Int) {
            if (myGen != threadGen) return
            isPlaying = false
            playWhenReady = false
        }

        private fun runAudioLoop(myGen: Int) {
            if (!ensureDecoded(myGen)) return
            val data: ByteArray
            val sr: Int
            val ch: Int
            synchronized(this) {
                data = pcmBytes
                sr = sampleRate
                ch = channels
            }
            if (myGen != threadGen || data.isEmpty()) return
            val frameBytes = ch * 2
            val bytesPerMs = (sr * frameBytes) / 1000.0
            val fmt = AudioFormat(sr.toFloat(), 16, ch, true, false)
            val info = DataLine.Info(SourceDataLine::class.java, fmt)
            val line = runCatching {
                (AudioSystem.getLine(info) as SourceDataLine).also {
                    it.open(fmt, sr * frameBytes / 4)
                    it.start()
                }
            }.getOrNull()
            try {
                val outBuf = ByteArray(4096)
                // 재생 커서는 바이트 단위로만 앞으로 나간다. 예전에는 청크(약 23ms)마다 재생 위치를 ms 로 반올림해
                // 바이트 오프셋을 다시 계산했는데, 그 값이 직전에 쓴 지점보다 몇 프레임 앞으로 되돌아가는 일이
                // 반복되어 같은 소리가 겹쳐 나면서 지속적인 잡음이 났다(사용자 신고). 커서를 그대로 밀면 이어진다.
                var cursor = ((currentPosition * bytesPerMs).toLong() / frameBytes) * frameBytes
                var expectedMs = currentPosition
                while (myGen == threadGen && isPlaying) {
                    val posMs = currentPosition
                    // 탐색 등으로 밖에서 위치가 바뀐 경우에만 커서를 그 지점으로 다시 맞춘다.
                    if (posMs != expectedMs) {
                        cursor = ((posMs * bytesPerMs).toLong() / frameBytes) * frameBytes
                    }
                    val byteOffset = cursor.coerceIn(0L, data.size.toLong()).toInt()
                    val remaining = data.size - byteOffset
                    // The same yardstick for "done" as for the seek bar: the position reaches the duration, or no whole frame is left.
                    if (posMs >= duration || remaining < frameBytes) {
                        handler.post {
                            if (myGen == threadGen) {
                                endedPlayback(myGen)
                                onTrackEnded()
                            }
                        }
                        break
                    }
                    val spd = speed.coerceIn(0.5f, 2.0f)
                    val chunkFrames = 1024
                    val srcFrames = (chunkFrames * spd).roundToInt().coerceAtLeast(1)
                    val srcBytesNeeded = srcFrames * frameBytes
                    val availBytes = remaining.coerceAtMost(srcBytesNeeded)
                    val availFrames = availBytes / frameBytes
                    if (spd == 1.0f || line == null) {
                        if (line != null) {
                            line.write(data, byteOffset, availFrames * frameBytes)
                        } else {
                            Thread.sleep(20)
                        }
                    } else {
                        val outFrames = (availFrames / spd).roundToInt().coerceAtLeast(1)
                        val neededOutBytes = outFrames * frameBytes
                        val targetBuf = if (neededOutBytes <= outBuf.size) outBuf else ByteArray(neededOutBytes)
                        for (of in 0 until outFrames) {
                            val sf = ((of * spd).toInt()).coerceIn(0, availFrames - 1)
                            val srcIdx = byteOffset + sf * frameBytes
                            val dstIdx = of * frameBytes
                            for (b in 0 until frameBytes) {
                                targetBuf[dstIdx + b] = data[srcIdx + b]
                            }
                        }
                        line.write(targetBuf, 0, neededOutBytes)
                    }
                    cursor += availFrames.toLong() * frameBytes
                    val advancedMs = ((availFrames * 1000L) / sr.coerceAtLeast(1)).coerceAtLeast(1L)
                    currentPosition = (currentPosition + advancedMs).coerceAtMost(duration)
                    expectedMs = currentPosition
                    tick()
                }
            } catch (_: Throwable) {
            } finally {
                runCatching {
                    line?.drain()
                    line?.stop()
                    line?.close()
                }
            }
        }
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(TrackStore.PREFS, MODE_PRIVATE) }

    val player: DesktopPlayer = DesktopPlayer()
    lateinit var store: TrackStore
        private set

    var repeatMode = 0
        private set
    val fileRepeatLimit = -1
    var abRepeatLimit = -1
        private set
    var gapMs = 0L
        private set

    private var currentUri: String? = null
    private var current = TrackStore.TrackState()
    var abStartMs = -1L
        private set
    var abEndMs = -1L
        private set
    val bookmarks = LongArray(TrackStore.BOOKMARK_SLOTS) { -1L }
    val savedRanges: List<TrackStore.SavedRange> get() = current.ranges
    private var abLoopsDone = 0
    private var abExhausted = false
    private var fileRepeatsDone = 0
    private var lastPositionMs = 0L
    private var lastSaveAt = 0L

    private var gapPending = false
    private val gapResume = Runnable {
        gapPending = false
        player.play()
    }
    private var sleepEndRealtime = 0L
    var sleepAtTrackEnd = false
        private set
    private val sleepRunnable = Runnable {
        sleepEndRealtime = 0L
        cancelGap()
        player.pause()
    }

    // ---- "go to the dictionary and come back to the same words" ----
    private var lookupTrackUri: String? = null
    private var lookupPositionMs = -1L
    private var lookupAt = 0L

    /** Remembers where the picked words are in the track and pauses; [takeLookupReturn] restarts from there. */
    fun rememberLookup(positionMs: Long) {
        lookupTrackUri = currentUri
        lookupPositionMs = positionMs.coerceAtLeast(0L)
        lookupAt = System.currentTimeMillis()
        cancelGap()
        player.pause()
    }

    /** The point to resume from when the user comes back to Listening after a lookup (once), or -1. */
    fun takeLookupReturn(): Long {
        val pos = lookupPositionMs
        val fresh = pos >= 0L && lookupTrackUri == currentUri && System.currentTimeMillis() - lookupAt < 30 * 60_000L
        lookupPositionMs = -1L
        lookupTrackUri = null
        return if (fresh) pos else -1L
    }

    /** The desktop service lives as long as the process; these exist so the screen can use the same calls as on Android. */
    fun onUiAttached() {}
    fun onUiDetached() {}

    /** Folders imported before v1.22 are linked through Android's persisted tree permissions; not needed on Windows. */
    fun linkLegacyImports() {}

    private var tickerRunning = false
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (player.isPlaying) handler.postDelayed(this, if (abActive()) 30L else 200L)
            else tickerRunning = false
        }
    }

    init {
        onCreate()
    }

    override fun onCreate() {
        super.onCreate()
        if (::store.isInitialized) return
        store = TrackStore(this)
        repeatMode = prefs.getInt("repeat_mode", 0).coerceIn(0, 2)
        abRepeatLimit = prefs.getInt("ab_repeat_limit", -1)
        gapMs = prefs.getLong("gap_ms", 0L).coerceAtLeast(0L)
        player.speed = prefs.getFloat("speed", 1.0f)
        player.shuffleModeEnabled = prefs.getBoolean("shuffle", false)
        restoreGroups()
        importPending()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun onTrackEnded() {
        if (sleepAtTrackEnd) {
            sleepAtTrackEnd = false
            player.pauseAtEndOfMediaItems = false
            player.pause()
            return
        }
        when (repeatMode) {
            1 -> {
                onFileRepeated()
                player.seekTo(0L)
                if (!gapPending) player.play()
            }
            2 -> {
                if (player.mediaItemCount > 0) {
                    markCurrentDone()
                    playIndex(player.nextIndex())
                }
            }
            else -> {
                val count = player.mediaItemCount
                val next = if (player.shuffleModeEnabled && count > 1) player.nextIndex() else player.currentMediaItemIndex + 1
                if (next < count) {
                    markCurrentDone()
                    playIndex(next)
                } else {
                    player.pause()
                    markCurrentDone()
                }
            }
        }
    }

    /** The track that just finished is remembered as listened, so the playlist stops showing it as unfinished. */
    private fun markCurrentDone() {
        val uri = currentUri ?: return
        current.done = true
        current.positionMs = 0L
        store.saveState(uri, current)
    }

    private fun renameSixMinuteEntries() {
        var changed = false
        for (group in groups) {
            if (!group.name.equals(SixMinuteEnglish.GROUP_NAME, ignoreCase = true)) continue
            for ((i, entry) in group.entries.withIndex()) {
                val key = SixMinuteEnglish.episodeKey(entry.name) ?: continue
                val name = SixMinuteEnglish.fileName(key)
                if (entry.name == name) continue
                group.entries[i] = TrackStore.Entry(entry.uri, name)
                changed = true
            }
        }
        if (changed) store.saveGroups(groups, store.activeGroup.coerceIn(0, groups.lastIndex))
    }

    fun importPending() {
        val entries = SixMinuteEnglish.takePending(this)
        if (entries.isEmpty()) return
        val index = groups.indexOfFirst { it.name.equals(SixMinuteEnglish.GROUP_NAME, ignoreCase = true) }
            .takeIf { it >= 0 } ?: createGroup(SixMinuteEnglish.GROUP_NAME)
        addEntries(entries, index)
    }

    lateinit var groups: MutableList<TrackStore.Group>
        private set
    var activeGroup = 0
        private set

    private fun restoreGroups() {
        groups = store.loadGroups()
        renameSixMinuteEntries()
        activeGroup = store.activeGroup.coerceIn(0, groups.lastIndex)
        loadActiveIntoPlayer(play = false, startIndex = null)
    }

    private fun queueEntries(): List<TrackStore.Entry> = (0 until player.mediaItemCount).mapNotNull {
        player.getEntryAt(it)
    }

    fun groupEntries(group: Int): List<TrackStore.Entry> = groups.getOrNull(group)?.entries.orEmpty()

    fun isDone(uri: String): Boolean = if (uri == currentUri) current.done else store.getState(uri).done

    val currentTitle: String? get() = player.currentEntry?.name

    val activeGroupName: String get() = groups.getOrNull(activeGroup)?.name ?: ""

    private fun syncActiveFromPlayer() {
        val group = groups.getOrNull(activeGroup) ?: return
        group.entries.clear()
        group.entries.addAll(queueEntries())
        group.index = player.currentMediaItemIndex.coerceAtLeast(0)
    }

    private fun persistGroups() {
        syncActiveFromPlayer()
        store.saveGroups(groups, activeGroup)
    }

    private fun loadActiveIntoPlayer(play: Boolean, startIndex: Int?) {
        cancelGap()
        val group = groups[activeGroup]
        if (group.entries.isEmpty()) {
            player.pause()
            player.clearMediaItems()
            onTrackLoaded(null)
            return
        }
        val index = (startIndex ?: group.index).coerceIn(0, group.entries.lastIndex)
        val state = store.getState(group.entries[index].uri)
        player.setMediaItems(group.entries.toList(), index, if (state.done) 0L else state.positionMs)
        player.prepare()
        if (play) player.play() else player.pause()
    }

    fun createGroup(name: String, parentId: String? = null): Int {
        groups.add(TrackStore.Group(name.trim().ifBlank { "새 그룹" }, parentId = parentId))
        persistGroups()
        return groups.lastIndex
    }

    fun descendants(group: Int): List<Int> {
        val id = groups.getOrNull(group)?.id ?: return emptyList()
        val out = mutableListOf<Int>()
        val queue = ArrayDeque(listOf(id))
        while (queue.isNotEmpty()) {
            val parent = queue.removeFirst()
            groups.forEachIndexed { i, g -> if (g.parentId == parent) { out += i; queue += g.id } }
        }
        return out
    }

    fun moveGroup(group: Int, newParentId: String?): Boolean {
        val g = groups.getOrNull(group) ?: return false
        if (newParentId != null) {
            if (newParentId == g.id) return false
            if (descendants(group).any { groups[it].id == newParentId }) return false
            if (groups.none { it.id == newParentId }) return false
        }
        g.parentId = newParentId
        persistGroups()
        return true
    }

    fun reorderGroup(group: Int, delta: Int): Boolean {
        val g = groups.getOrNull(group) ?: return false
        val level = groups.indices.filter { i ->
            val p = groups[i].parentId?.takeIf { id -> groups.any { it.id == id } }
            p == g.parentId?.takeIf { id -> groups.any { it.id == id } }
        }
        val pos = level.indexOf(group)
        val other = level.getOrNull(pos + delta) ?: return false
        syncActiveFromPlayer()
        java.util.Collections.swap(groups, group, other)
        activeGroup = when (activeGroup) {
            group -> other
            other -> group
            else -> activeGroup
        }
        persistGroups()
        return true
    }

    fun sortGroupByName(group: Int) {
        val g = groups.getOrNull(group) ?: return
        if (group == activeGroup) {
            syncActiveFromPlayer()
            val sorted = g.entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            sorted.forEachIndexed { i, entry ->
                val j = (i until player.mediaItemCount).firstOrNull { player.getEntryAt(it)?.uri == entry.uri } ?: return@forEachIndexed
                if (j != i) player.moveMediaItem(j, i)
            }
        } else {
            val cur = g.entries.getOrNull(g.index)?.uri
            g.entries.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            g.index = g.entries.indexOfFirst { it.uri == cur }.coerceAtLeast(0)
        }
        persistGroups()
    }

    fun linkedTrees(): List<String> = groups.mapNotNull { it.sourceTree }.distinct()

    fun applyScan(tree: String, root: FolderImport.Node, force: Boolean): Int {
        var added = 0
        fun visit(node: FolderImport.Node, parent: TrackStore.Group?) {
            var group = groups.firstOrNull { it.sourceTree == tree && it.sourceDocId == node.docId }
            if (group == null && force) {
                group = groups.firstOrNull { it.sourceTree == null && (it.name == node.path || (parent == null && it.name == node.name)) }
            }
            if (group == null && node.hasAudio) {
                val allowed = if (parent == null) force else force || node.docId !in parent.seen
                if (allowed) {
                    group = TrackStore.Group(node.name, parentId = parent?.id)
                    groups.add(group)
                    parent?.seen?.add(node.docId)
                }
            }
            val linked = group
            if (linked != null) {
                linked.sourceTree = tree
                linked.sourceDocId = node.docId
                parent?.seen?.add(node.docId)
                val have = linked.entries.map { it.uri }.toSet()
                val toAdd = node.files.filter { it.uri !in have && (force || it.uri !in linked.seen) }
                added += addEntries(toAdd, groups.indexOf(linked), startIfIdle = false)
                node.files.forEach { linked.seen += it.uri }
            }
            node.children.forEach { visit(it, group) }
        }
        visit(root, null)
        persistGroups()
        return added
    }

    fun renameGroup(group: Int, name: String) {
        if (name.isBlank()) return
        groups.getOrNull(group)?.name = name.trim()
        persistGroups()
    }

    fun deleteGroup(group: Int) {
        if (group !in groups.indices) return
        if (groups.size == 1) {
            clearGroup(0)
            return
        }
        if (group == activeGroup) {
            val next = if (group > 0) group - 1 else 1
            groups.removeAt(group)
            activeGroup = if (next > group) next - 1 else next
            loadActiveIntoPlayer(play = false, startIndex = null)
        } else {
            groups.removeAt(group)
            if (group < activeGroup) activeGroup--
        }
        persistGroups()
    }

    fun clearGroup(group: Int) {
        if (group !in groups.indices) return
        if (group == activeGroup) {
            cancelGap()
            player.pause()
            player.clearMediaItems()
        } else {
            groups[group].entries.clear()
            groups[group].index = 0
        }
        persistGroups()
    }

    fun switchGroup(group: Int, play: Boolean) {
        if (group !in groups.indices) return
        if (group != activeGroup) {
            syncActiveFromPlayer()
            activeGroup = group
            loadActiveIntoPlayer(play, startIndex = null)
        } else if (play) resume()
        persistGroups()
    }

    fun addEntries(newEntries: List<TrackStore.Entry>, group: Int = activeGroup, startIfIdle: Boolean = false): Int {
        if (group !in groups.indices) return 0
        if (group != activeGroup) {
            val target = groups[group]
            val known = target.entries.map { it.uri }.toSet()
            val fresh = newEntries.filter { it.uri !in known }
            target.entries.addAll(fresh)
            persistGroups()
            return fresh.size
        }
        val existing = (0 until player.mediaItemCount).mapNotNull { player.getEntryAt(it)?.uri }.toSet()
        val fresh = newEntries.filter { it.uri !in existing }
        if (fresh.isEmpty()) return 0
        val wasEmpty = player.mediaItemCount == 0
        val firstNew = player.mediaItemCount
        val idle = !isActive
        if (wasEmpty) {
            val state = store.getState(fresh[0].uri)
            player.setMediaItems(fresh, 0, if (state.done) 0L else state.positionMs)
        } else {
            player.addMediaItems(fresh)
        }
        player.prepare()
        if (startIfIdle && idle) {
            if (!wasEmpty) player.seekToDefaultPosition(firstNew)
            cancelGap()
            player.play()
        }
        persistGroups()
        return fresh.size
    }

    fun openAndPlay(entry: TrackStore.Entry) {
        addEntries(listOf(entry), activeGroup, startIfIdle = false)
        val index = (0 until player.mediaItemCount).firstOrNull { player.getEntryAt(it)?.uri == entry.uri } ?: return
        playIndex(index)
    }

    fun removeFrom(group: Int, index: Int) {
        val list = groups.getOrNull(group)?.entries ?: return
        if (index !in list.indices) return
        if (group == activeGroup) {
            player.removeMediaItem(index)
        } else {
            list.removeAt(index)
            val g = groups[group]
            if (g.index > index || g.index >= list.size) g.index = (g.index - 1).coerceAtLeast(0)
        }
        persistGroups()
    }

    fun moveInGroup(group: Int, from: Int, to: Int) {
        val g = groups.getOrNull(group) ?: return
        if (from !in g.entries.indices || to !in g.entries.indices || from == to) return
        if (group == activeGroup) {
            player.moveMediaItem(from, to)
        } else {
            g.entries.add(to, g.entries.removeAt(from))
            when (g.index) {
                from -> g.index = to
                in minOf(from, to)..maxOf(from, to) -> g.index += if (from < to) -1 else 1
            }
        }
        persistGroups()
    }

    fun moveToGroup(fromGroup: Int, index: Int, toGroup: Int): Boolean {
        val entry = groups.getOrNull(fromGroup)?.entries?.getOrNull(index) ?: return false
        if (fromGroup == toGroup) return false
        if (groups.getOrNull(toGroup)?.entries?.any { it.uri == entry.uri } != false) return false
        addEntries(listOf(entry), toGroup, startIfIdle = false)
        removeFrom(fromGroup, index)
        return true
    }

    fun resetProgress(group: Int, index: Int) {
        val uri = groups.getOrNull(group)?.entries?.getOrNull(index)?.uri ?: return
        val state = if (uri == currentUri) current else store.getState(uri)
        state.done = false
        state.positionMs = 0L
        store.saveState(uri, state)
    }

    fun playIn(group: Int, index: Int) {
        val g = groups.getOrNull(group) ?: return
        if (index !in g.entries.indices) return
        if (group == activeGroup) {
            playIndex(index)
            return
        }
        syncActiveFromPlayer()
        activeGroup = group
        loadActiveIntoPlayer(play = true, startIndex = index)
        persistGroups()
    }

    fun playIndex(index: Int) {
        if (index !in 0 until player.mediaItemCount) return
        cancelGap()
        player.seekToDefaultPosition(index)
        player.play()
    }

    val isActive: Boolean
        get() = player.isPlaying

    /** A decode/playback problem the screen should show once, or null. */
    fun consumeError(): String? = player.consumeError()

    fun togglePlay() {
        if (player.mediaItemCount == 0) return
        cancelGap()
        if (isActive) {
            player.pause()
        } else {
            player.prepare()
            if (player.duration > 0L && player.currentPosition >= player.duration) {
                player.seekTo(0L)
            }
            player.play()
        }
    }

    fun resume() {
        if (player.mediaItemCount > 0 && !isActive) togglePlay()
    }

    fun stopPlayback() {
        cancelGap()
        player.pause()
        player.seekTo(0)
        abLoopsDone = 0
        abExhausted = false
        lastPositionMs = 0L
        current.positionMs = 0L
        currentUri?.let { store.saveState(it, current) }
    }

    fun setSpeed(speed: Float) {
        player.speed = speed.coerceIn(0.5f, 2.0f)
        prefs.edit().putFloat("speed", player.speed).apply()
    }

    val speed: Float get() = player.speed

    fun updateRepeatMode(mode: Int) {
        repeatMode = mode.coerceIn(0, 2)
        prefs.edit().putInt("repeat_mode", repeatMode).apply()
        fileRepeatsDone = 0
        applyRepeatMode()
    }

    val shuffle: Boolean get() = player.shuffleModeEnabled

    fun updateShuffle(on: Boolean) {
        player.shuffleModeEnabled = on
        prefs.edit().putBoolean("shuffle", on).apply()
    }

    fun updateAbRepeatLimit(limit: Int) {
        abRepeatLimit = limit
        prefs.edit().putInt("ab_repeat_limit", limit).apply()
        resetAbLoop()
    }

    fun updateGap(ms: Long) {
        gapMs = ms.coerceAtLeast(0L)
        prefs.edit().putLong("gap_ms", gapMs).apply()
    }

    private fun applyRepeatMode() {
        player.repeatMode = repeatMode
    }

    private fun onFileRepeated() {
        fileRepeatsDone++
        applyRepeatMode()
        if (gapMs > 0L) startGap()
    }

    private fun abActive() = abStartMs >= 0 && abEndMs > abStartMs && !abExhausted

    private fun resetAbLoop() {
        abLoopsDone = 0
        abExhausted = false
    }

    private fun persistAb() {
        current.abStartMs = abStartMs
        current.abEndMs = abEndMs
        currentUri?.let { store.saveState(it, current) }
    }

    private fun maxPosition(): Long = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE

    fun setA(positionMs: Long) {
        abStartMs = positionMs.coerceAtLeast(0L)
        if (abEndMs <= abStartMs) abEndMs = -1L
        resetAbLoop()
        persistAb()
    }

    fun setB(positionMs: Long): Boolean {
        if (abStartMs < 0 || positionMs <= abStartMs) return false
        abEndMs = positionMs
        resetAbLoop()
        persistAb()
        player.seekTo(abStartMs)
        return true
    }

    fun clearAb() {
        abStartMs = -1L
        abEndMs = -1L
        resetAbLoop()
        persistAb()
    }

    fun nudgeA(deltaMs: Long) {
        if (abStartMs < 0) return
        val upper = if (abEndMs > 0) abEndMs - MIN_RANGE_MS else maxPosition()
        abStartMs = (abStartMs + deltaMs).coerceIn(0L, upper.coerceAtLeast(0L))
        resetAbLoop()
        persistAb()
        cancelGap()
        player.seekTo(abStartMs)
        player.play()
    }

    fun nudgeB(deltaMs: Long) {
        if (abEndMs < 0) return
        abEndMs = (abEndMs + deltaMs).coerceAtMost(maxPosition()).coerceAtLeast(abStartMs + MIN_RANGE_MS)
        resetAbLoop()
        persistAb()
        cancelGap()
        player.seekTo((abEndMs - PREVIEW_BEFORE_B_MS).coerceAtLeast(abStartMs))
        player.play()
    }

    fun saveCurrentRange(name: String?): Boolean {
        if (abStartMs < 0 || abEndMs <= abStartMs) return false
        val label = name?.takeIf { it.isNotBlank() } ?: "구간 ${current.ranges.size + 1}"
        current.ranges.add(TrackStore.SavedRange(label, abStartMs, abEndMs))
        persistAb()
        return true
    }

    fun applyRange(index: Int) {
        val range = current.ranges.getOrNull(index) ?: return
        abStartMs = range.startMs
        abEndMs = range.endMs
        resetAbLoop()
        persistAb()
        cancelGap()
        player.seekTo(abStartMs)
        player.play()
    }

    fun renameRange(index: Int, name: String) {
        val range = current.ranges.getOrNull(index) ?: return
        if (name.isBlank()) return
        current.ranges[index] = range.copy(name = name.trim())
        persistAb()
    }

    fun deleteRange(index: Int) {
        if (index !in current.ranges.indices) return
        current.ranges.removeAt(index)
        persistAb()
    }

    private fun onAbEnd() {
        abLoopsDone++
        if (abRepeatLimit >= 0 && abLoopsDone >= abRepeatLimit) {
            abExhausted = true
            return
        }
        player.seekTo(abStartMs)
        if (gapMs > 0L) startGap()
    }

    val currentTrackUri: String? get() = currentUri
    val subtitleUri: String? get() = current.subtitleUri ?: adoptLinkedSubtitle()

    private fun adoptLinkedSubtitle(): String? {
        val uri = currentUri ?: return null
        if (uri == checkedLinkFor && SubtitleLinks.version == checkedLinkVersion) return null
        checkedLinkFor = uri
        checkedLinkVersion = SubtitleLinks.version
        val linked = SubtitleLinks.find(this, uri, currentTitle) ?: return null
        setSubtitle(linked)
        return linked
    }
    private var checkedLinkFor: String? = null
    private var checkedLinkVersion = -1

    fun setSubtitle(uri: String?) {
        current.subtitleUri = uri
        persistAb()
    }

    fun toggleBookmark(slot: Int): Boolean {
        if (slot !in bookmarks.indices) return false
        val removing = bookmarks[slot] >= 0
        bookmarks[slot] = if (removing) -1L else player.currentPosition.coerceAtLeast(0L)
        current.bookmarks[slot] = bookmarks[slot]
        persistAb()
        return !removing
    }

    private fun startGap() {
        handler.removeCallbacks(gapResume)
        gapPending = true
        player.pause()
        handler.postDelayed(gapResume, gapMs)
    }

    private fun cancelGap() {
        if (!gapPending) return
        gapPending = false
        handler.removeCallbacks(gapResume)
    }

    fun setSleepTimer(minutes: Int) {
        cancelSleepTimer()
        if (minutes > 0) {
            val delay = minutes * 60_000L
            sleepEndRealtime = SystemClock.elapsedRealtime() + delay
            handler.postDelayed(sleepRunnable, delay)
        }
    }

    fun sleepAtEndOfTrack() {
        cancelSleepTimer()
        sleepAtTrackEnd = true
        player.pauseAtEndOfMediaItems = true
    }

    fun cancelSleepTimer() {
        handler.removeCallbacks(sleepRunnable)
        sleepEndRealtime = 0L
        if (sleepAtTrackEnd) {
            sleepAtTrackEnd = false
            player.pauseAtEndOfMediaItems = false
        }
    }

    val sleepRemainingMs: Long
        get() = if (sleepEndRealtime > 0L) (sleepEndRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L) else -1L

    private fun onTrackLoaded(item: TrackStore.Entry?) {
        val uri = item?.uri
        currentUri = uri
        current = if (uri != null) store.getState(uri) else TrackStore.TrackState()
        abStartMs = current.abStartMs
        abEndMs = current.abEndMs
        current.bookmarks.copyInto(bookmarks)
        resetAbLoop()
        fileRepeatsDone = 0
        lastPositionMs = 0L
        applyRepeatMode()
        persistGroups()
        prefs.edit().putString("last_name", item?.name).apply()
    }

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        handler.post(ticker)
    }

    private fun tick() {
        val position = player.currentPosition
        lastPositionMs = position
        if (abStartMs >= 0 && abEndMs > abStartMs) {
            if (abExhausted) {
                if (position < abStartMs) resetAbLoop()
            } else if (position >= abEndMs) {
                onAbEnd()
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastSaveAt >= SAVE_INTERVAL_MS) {
            lastSaveAt = now
            savePosition()
        }
    }

    fun savePosition() {
        val uri = currentUri ?: return
        lastPositionMs = player.currentPosition.coerceAtLeast(0L)
        current.positionMs = lastPositionMs
        store.saveState(uri, current)
    }

    companion object {
        const val ACTION_LOCAL_BIND = "com.hdlee73.englishstudy.listening.action.LOCAL_BIND"
        private const val MIN_RANGE_MS = 200L
        private const val PREVIEW_BEFORE_B_MS = 1_500L
        private const val SAVE_INTERVAL_MS = 5_000L
        /** Decoded PCM cap (16-bit). A track longer than this is refused instead of exhausting the heap. */
        private const val MAX_PCM_BYTES = 512L * 1024 * 1024

        /** Process-wide singleton so playback never stops when navigating between tabs (Modification 1). */
        val instance: PlaybackService by lazy {
            PlaybackService().also { it.onCreate() }
        }
    }
}

/** 16-bit PCM decoded from one track, in the track's own sample rate and channel count. */
private class Decoded(val data: ByteArray, val sampleRate: Int, val channels: Int)
