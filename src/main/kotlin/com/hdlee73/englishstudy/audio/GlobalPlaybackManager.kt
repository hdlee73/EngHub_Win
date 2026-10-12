package com.hdlee73.englishstudy.audio

import com.hdlee73.englishstudy.listening.Cue
import com.hdlee73.englishstudy.listening.SubtitleParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class TrackItem(
    val file: File,
    val title: String = file.nameWithoutExtension,
    var subtitleFile: File? = null
)

data class WordLookupContext(
    val word: String,
    val cueStartMs: Long,
    val trackFile: File?,
    val trackTitle: String,
    val timestampFormatted: String
)

data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentTrack: TrackItem? = null,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playlist: List<TrackItem> = emptyList(),
    val currentIndex: Int = -1,
    val subtitles: List<Cue> = emptyList(),
    val currentCueIndex: Int = -1,
    val repeatMode: Int = 0, // 0 = none/all, 1 = repeat track
    val aMs: Long = -1L,
    val bMs: Long = -1L,
    val returnContext: WordLookupContext? = null
)

object GlobalPlaybackManager : DesktopAudioPlayer.Listener {
    private val player = DesktopAudioPlayer()

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    init {
        player.listener = this
    }

    fun setPlaylist(tracks: List<TrackItem>, startIndex: Int = 0) {
        _state.value = _state.value.copy(
            playlist = tracks,
            currentIndex = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        )
        if (tracks.isNotEmpty()) {
            loadTrack(tracks[_state.value.currentIndex])
        }
    }

    fun addTrack(file: File) {
        val newItem = TrackItem(file)
        // Automatically check for matching .srt or .vtt in the same directory
        val srt = File(file.parentFile, "${file.nameWithoutExtension}.srt")
        val vtt = File(file.parentFile, "${file.nameWithoutExtension}.vtt")
        if (srt.exists()) newItem.subtitleFile = srt
        else if (vtt.exists()) newItem.subtitleFile = vtt

        val list = _state.value.playlist + newItem
        _state.value = _state.value.copy(playlist = list)
        if (_state.value.currentTrack == null) {
            _state.value = _state.value.copy(currentIndex = 0)
            loadTrack(newItem)
        }
    }

    fun selectTrack(index: Int) {
        val list = _state.value.playlist
        if (index in list.indices) {
            _state.value = _state.value.copy(currentIndex = index)
            loadTrack(list[index])
            play()
        }
    }

    fun loadTrack(track: TrackItem) {
        player.stop()
        player.load(track.file)

        // Load subtitles if available
        var cues = emptyList<Cue>()
        val subFile = track.subtitleFile ?: File(track.file.parentFile, "${track.file.nameWithoutExtension}.srt")
        if (subFile.exists()) {
            cues = SubtitleParser.parse(subFile.readText(Charsets.UTF_8))
        }

        _state.value = _state.value.copy(
            currentTrack = track,
            currentPositionMs = 0L,
            durationMs = player.durationMs,
            subtitles = cues,
            currentCueIndex = -1,
            aMs = -1L,
            bMs = -1L
        )
    }

    fun setSubtitleFile(file: File) {
        val track = _state.value.currentTrack ?: return
        track.subtitleFile = file
        val cues = SubtitleParser.parse(file.readText(Charsets.UTF_8))
        _state.value = _state.value.copy(subtitles = cues)
    }

    fun play() {
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun togglePlay() {
        player.toggle()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        updateCurrentCue(positionMs)
    }

    fun next() {
        val list = _state.value.playlist
        if (list.isEmpty()) return
        val nextIdx = (_state.value.currentIndex + 1) % list.size
        selectTrack(nextIdx)
    }

    fun previous() {
        val list = _state.value.playlist
        if (list.isEmpty()) return
        val prevIdx = if (_state.value.currentIndex - 1 < 0) list.size - 1 else _state.value.currentIndex - 1
        selectTrack(prevIdx)
    }

    fun setRepeatMode(mode: Int) {
        _state.value = _state.value.copy(repeatMode = mode)
    }

    fun setA(posMs: Long = player.currentPositionMs) {
        player.aMs = posMs
        _state.value = _state.value.copy(aMs = posMs)
    }

    fun setB(posMs: Long = player.currentPositionMs) {
        player.bMs = posMs
        _state.value = _state.value.copy(bMs = posMs)
    }

    fun clearAB() {
        player.clearRange()
        _state.value = _state.value.copy(aMs = -1L, bMs = -1L)
    }

    // --- Requirement (3) Implementation ---
    /**
     * Stash context when jumping from subtitle to Dictionary / Vocabulary.
     */
    fun setWordLookupContext(word: String, cueStartMs: Long) {
        val track = _state.value.currentTrack
        val formatted = formatTime(cueStartMs)
        _state.value = _state.value.copy(
            returnContext = WordLookupContext(
                word = word,
                cueStartMs = cueStartMs,
                trackFile = track?.file,
                trackTitle = track?.title ?: "리스닝",
                timestampFormatted = formatted
            )
        )
    }

    /**
     * Resume playback from the word's subtitle timestamp after returning from dictionary.
     */
    fun resumeFromLookupContext() {
        val ctx = _state.value.returnContext ?: return
        // If track changed, reload track
        if (ctx.trackFile != null && _state.value.currentTrack?.file != ctx.trackFile) {
            val matched = _state.value.playlist.find { it.file == ctx.trackFile }
            if (matched != null) {
                loadTrack(matched)
            }
        }
        seekTo(ctx.cueStartMs)
        play()
        _state.value = _state.value.copy(returnContext = null)
    }

    fun clearLookupContext() {
        _state.value = _state.value.copy(returnContext = null)
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        _state.value = _state.value.copy(isPlaying = isPlaying)
    }

    override fun onProgress(positionMs: Long, durationMs: Long) {
        _state.value = _state.value.copy(
            currentPositionMs = positionMs,
            durationMs = if (durationMs > 0) durationMs else _state.value.durationMs
        )
        updateCurrentCue(positionMs)
    }

    override fun onTrackCompleted() {
        if (_state.value.repeatMode == 1) {
            // Repeat single track
            seekTo(0L)
            play()
        } else {
            next()
        }
    }

    override fun onError(message: String) {
        _state.value = _state.value.copy(isPlaying = false)
    }

    private fun updateCurrentCue(posMs: Long) {
        val cues = _state.value.subtitles
        if (cues.isEmpty()) return
        val idx = cues.indexOfFirst { posMs in it.startMs..it.endMs }
        if (idx != _state.value.currentCueIndex) {
            _state.value = _state.value.copy(currentCueIndex = idx)
        }
    }

    fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0L)
        val m = totalSec / 60
        val s = totalSec % 60
        return "%02d:%02d".format(m, s)
    }
}
