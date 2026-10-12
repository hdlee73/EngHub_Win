package com.hdlee73.englishstudy

import com.hdlee73.englishstudy.audio.GlobalPlaybackManager
import com.hdlee73.englishstudy.audio.TrackItem
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class WordLookupNavigationTest {

    @Test
    fun testWordLookupTimestampSavedAndRestored() {
        // Requirement (3): 자막 단어 선택 시 해당 타임스탬프 저장 및 복귀 시 seekTo 검증
        val dummyTrack = TrackItem(File("test_audio.mp3"), "Test Track")
        GlobalPlaybackManager.setPlaylist(listOf(dummyTrack))

        val word = "explore"
        val timestampMs = 45200L // 00:45

        GlobalPlaybackManager.setWordLookupContext(word, timestampMs)

        val state = GlobalPlaybackManager.state.value
        assertNotNull(state.returnContext, "Return context must be saved")
        assertEquals("explore", state.returnContext?.word)
        assertEquals(45200L, state.returnContext?.cueStartMs)
        assertEquals("00:45", state.returnContext?.timestampFormatted)

        // Resume from lookup context
        GlobalPlaybackManager.resumeFromLookupContext()
        val newState = GlobalPlaybackManager.state.value
        assertEquals(45200L, newState.currentPositionMs, "Must seek to the word's cue start timestamp")
        assertEquals(null, newState.returnContext, "Context should be cleared after resuming")
    }
}
