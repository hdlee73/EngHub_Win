package com.hdlee73.englishstudy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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

/**
 * Requirement (1): Persistent global mini player bar visible across all app tabs.
 * Playback continues seamlessly during screen switches.
 */
@Composable
fun MiniPlayerBar(
    onNavigateToListening: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by GlobalPlaybackManager.state.collectAsState()
    val track = state.currentTrack ?: return

    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .clickable { onNavigateToListening() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = track.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${GlobalPlaybackManager.formatTime(state.currentPositionMs)} / ${GlobalPlaybackManager.formatTime(state.durationMs)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.aMs >= 0L || state.bMs >= 0L) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text("A-B 반복 중", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // Slider
            Slider(
                value = if (state.durationMs > 0) state.currentPositionMs.toFloat() / state.durationMs else 0f,
                onValueChange = { frac ->
                    GlobalPlaybackManager.seekTo((frac * state.durationMs).toLong())
                },
                modifier = Modifier
                    .width(180.dp)
                    .padding(horizontal = 8.dp)
            )

            // Playback controls
            IconButton(onClick = { GlobalPlaybackManager.previous() }) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "이전 곡")
            }

            FilledIconButton(
                onClick = { GlobalPlaybackManager.togglePlay() },
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) "일시정지" else "재생"
                )
            }

            IconButton(onClick = { GlobalPlaybackManager.next() }) {
                Icon(Icons.Default.SkipNext, contentDescription = "다음 곡")
            }
        }
    }
}
