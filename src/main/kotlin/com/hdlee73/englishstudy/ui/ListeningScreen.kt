package com.hdlee73.englishstudy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hdlee73.englishstudy.audio.GlobalPlaybackManager
import com.hdlee73.englishstudy.audio.TrackItem
import com.hdlee73.englishstudy.listening.Cue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
fun ListeningScreen(
    onLookupWord: (word: String, timestampMs: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by GlobalPlaybackManager.state.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll subtitle to active cue
    LaunchedEffect(state.currentCueIndex) {
        if (state.currentCueIndex >= 0 && state.currentCueIndex < state.subtitles.size) {
            listState.animateScrollToItem(state.currentCueIndex)
        }
    }

    var selectedCueForAction by remember { mutableStateOf<Cue?>(null) }
    var pickedWord by remember { mutableStateOf("") }

    Row(modifier = modifier.fillMaxSize()) {
        // Left Column: Playlist & Controls
        Card(
            modifier = Modifier
                .width(320.dp)
                .fillMaxHeight()
                .padding(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                // Header with file import buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("재생 목록 (${state.playlist.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Row {
                        IconButton(onClick = {
                            val dialog = FileDialog(null as Frame?, "오디오 파일 선택", FileDialog.LOAD)
                            dialog.isVisible = true
                            val file = dialog.file
                            val dir = dialog.directory
                            if (file != null && dir != null) {
                                GlobalPlaybackManager.addTrack(File(dir, file))
                            }
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "파일 추가")
                        }
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 8.dp))

                // Track list
                LazyColumn(modifier = Modifier.weight(1f)) {
                    if (state.playlist.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                                Text("오디오 파일(+ 버튼)을 추가해 주세요.", color = Color.Gray, fontSize = 13.sp)
                            }
                        }
                    } else {
                        itemsIndexed(state.playlist) { idx, track ->
                            val isSelected = idx == state.currentIndex
                            ListItem(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { GlobalPlaybackManager.selectTrack(idx) }
                                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent),
                                headlineContent = {
                                    Text(
                                        track.title,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        if (isSelected && state.isPlaying) Icons.Default.VolumeUp else Icons.Outlined.MusicNote,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray
                                    )
                                },
                                trailingContent = {
                                    if (track.subtitleFile != null) {
                                        Text("SRT", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    }
                                }
                            )
                        }
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 8.dp))

                // Subtitle attachment button
                OutlinedButton(
                    onClick = {
                        val dialog = FileDialog(null as Frame?, "자막 파일 선택 (.srt, .vtt)", FileDialog.LOAD)
                        dialog.isVisible = true
                        val file = dialog.file
                        val dir = dialog.directory
                        if (file != null && dir != null) {
                            GlobalPlaybackManager.setSubtitleFile(File(dir, file))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Subtitles, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("자막 (.srt) 불러오기")
                }
            }
        }

        // Right Column: Active Track View, Controls, Subtitles
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(12.dp)
        ) {
            val track = state.currentTrack
            if (track != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(track.title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Spacer(modifier = Modifier.height(10.dp))

                        // Progress slider
                        Slider(
                            value = if (state.durationMs > 0) state.currentPositionMs.toFloat() / state.durationMs else 0f,
                            onValueChange = { frac ->
                                GlobalPlaybackManager.seekTo((frac * state.durationMs).toLong())
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(GlobalPlaybackManager.formatTime(state.currentPositionMs), fontSize = 12.sp)
                            Text(GlobalPlaybackManager.formatTime(state.durationMs), fontSize = 12.sp)
                        }

                        // Playback Control Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { GlobalPlaybackManager.previous() }) {
                                Icon(Icons.Default.SkipPrevious, contentDescription = null)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            FilledIconButton(
                                onClick = { GlobalPlaybackManager.togglePlay() },
                                modifier = Modifier.size(54.dp)
                            ) {
                                Icon(
                                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            IconButton(onClick = { GlobalPlaybackManager.next() }) {
                                Icon(Icons.Default.SkipNext, contentDescription = null)
                            }
                            Spacer(modifier = Modifier.width(20.dp))

                            // A-B Repeat Controls
                            OutlinedButton(
                                onClick = {
                                    if (state.aMs < 0L) GlobalPlaybackManager.setA()
                                    else if (state.bMs < 0L) GlobalPlaybackManager.setB()
                                    else GlobalPlaybackManager.clearAB()
                                }
                            ) {
                                val label = when {
                                    state.aMs >= 0L && state.bMs >= 0L -> "A-B 해제"
                                    state.aMs >= 0L -> "B 지점 설정"
                                    else -> "A 지점 설정"
                                }
                                Text(label, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Subtitle List
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Text("자막 스크립트 (${state.subtitles.size}문장)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Divider(modifier = Modifier.padding(vertical = 8.dp))

                    if (state.subtitles.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("등록된 자막이 없습니다. [자막 (.srt) 불러오기]를 눌러 추가하세요.", color = Color.Gray)
                        }
                    } else {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(state.subtitles) { idx, cue ->
                                val isCurrent = idx == state.currentCueIndex
                                Surface(
                                    color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable {
                                            selectedCueForAction = cue
                                        }
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                "${GlobalPlaybackManager.formatTime(cue.startMs)} --> ${GlobalPlaybackManager.formatTime(cue.endMs)}",
                                                fontSize = 11.sp,
                                                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Gray,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))

                                        // Render words as clickable tokens
                                        Row(modifier = Modifier.fillMaxWidth()) {
                                            val words = cue.text.split(" ")
                                            Column {
                                                Text(
                                                    text = cue.text,
                                                    fontSize = 15.sp,
                                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                    lineHeight = 22.sp
                                                )
                                                // Quick word chips row
                                                Row(modifier = Modifier.padding(top = 4.dp)) {
                                                    words.take(6).forEach { w ->
                                                        val clean = w.trim(',', '.', '!', '?', '"', '\'')
                                                        if (clean.length >= 2) {
                                                            SuggestionChip(
                                                                onClick = {
                                                                    // Requirement (3): capture word & cue.startMs and navigate to dictionary
                                                                    onLookupWord(clean, cue.startMs)
                                                                },
                                                                label = { Text(clean, fontSize = 11.sp) },
                                                                modifier = Modifier.padding(end = 4.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal action sheet for selected cue
    selectedCueForAction?.let { cue ->
        AlertDialog(
            onDismissRequest = { selectedCueForAction = null },
            title = { Text("자막 구간 동작") },
            text = {
                Column {
                    Text(cue.text, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${GlobalPlaybackManager.formatTime(cue.startMs)} ~ ${GlobalPlaybackManager.formatTime(cue.endMs)}",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    GlobalPlaybackManager.seekTo(cue.startMs)
                    GlobalPlaybackManager.play()
                    selectedCueForAction = null
                }) {
                    Text("이 구간 재생")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedCueForAction = null }) {
                    Text("닫기")
                }
            }
        )
    }
}
