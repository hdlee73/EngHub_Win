package com.hdlee73.englishstudy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hdlee73.englishstudy.audio.GlobalPlaybackManager
import com.hdlee73.englishstudy.dictionary.DictionaryEngine
import com.hdlee73.englishstudy.dictionary.SavedWordsStore
import com.hdlee73.englishstudy.dictionary.WordEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Requirement (3): Dictionary screen with quick-resume banner back to listening audio timestamp.
 */
@Composable
fun DictionaryScreen(
    initialQuery: String = "",
    onNavigateToListening: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playbackState by GlobalPlaybackManager.state.collectAsState()
    val savedWords by SavedWordsStore.savedWords.collectAsState()
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf(initialQuery) }
    var currentEntry by remember { mutableStateOf<WordEntry?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    fun doLookup(word: String) {
        val q = word.trim()
        if (q.isEmpty()) return
        query = q
        isLoading = true
        scope.launch(Dispatchers.IO) {
            val res = DictionaryEngine.lookup(q)
            currentEntry = res
            isLoading = false
        }
    }

    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) {
            doLookup(initialQuery)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        // Requirement (3): Prominent return banner when jumped from a subtitle word
        playbackState.returnContext?.let { ctx ->
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(10.dp),
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Headphones,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "자막 단어: '${ctx.word}'",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                "${ctx.trackTitle} (${ctx.timestampFormatted}) 위치에서 재생",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }

                    Row {
                        Button(
                            onClick = {
                                GlobalPlaybackManager.resumeFromLookupContext()
                                onNavigateToListening()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("해당 위치부터 재생 복귀")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = { GlobalPlaybackManager.clearLookupContext() }) {
                            Icon(Icons.Default.Close, contentDescription = "닫기")
                        }
                    }
                }
            }
        }

        // Search Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("영어 단어 또는 숙어 검색") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = null)
                        }
                    }
                }
            )
            Spacer(modifier = Modifier.width(10.dp))
            Button(
                onClick = { doLookup(query) },
                modifier = Modifier.height(56.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("검색")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxSize()) {
            // Left: Definition result
            Card(
                modifier = Modifier
                    .weight(1.3f)
                    .fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (currentEntry != null) {
                        val entry = currentEntry!!
                        val saved = SavedWordsStore.isSaved(entry.word)
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(entry.word, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                                IconButton(onClick = { SavedWordsStore.toggle(entry) }) {
                                    Icon(
                                        if (saved) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                        contentDescription = "단어장 저장",
                                        tint = if (saved) Color(0xFFFFB300) else Color.Gray,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }

                            if (entry.ipa.isNotBlank()) {
                                Text("[${entry.ipa}]", color = Color.Gray, fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            Divider(modifier = Modifier.padding(vertical = 10.dp))

                            Text("뜻 / 의미", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(entry.korean, fontSize = 15.sp, lineHeight = 22.sp)

                            if (entry.examples.isNotBlank()) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("예문", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(entry.examples, fontSize = 14.sp, lineHeight = 20.sp)
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("단어를 검색하거나 리스닝 자막에서 단어를 선택해 보세요.", color = Color.Gray)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Right: Saved Words / Vocabulary
            Card(
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                    Text("내 단어장 (${savedWords.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Divider(modifier = Modifier.padding(vertical = 8.dp))

                    if (savedWords.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("저장된 단어가 없습니다.", color = Color.Gray, fontSize = 13.sp)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(savedWords) { w ->
                                ListItem(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { doLookup(w.word) },
                                    headlineContent = { Text(w.word, fontWeight = FontWeight.SemiBold) },
                                    supportingContent = { Text(w.korean.lines().firstOrNull() ?: "", maxLines = 1) },
                                    trailingContent = {
                                        IconButton(onClick = { SavedWordsStore.remove(w.word) }) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = "삭제", tint = Color.Gray)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
