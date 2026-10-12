package com.hdlee73.englishstudy.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hdlee73.englishstudy.docvoice.Exporter
import com.hdlee73.englishstudy.docvoice.Sentence
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Requirement (2): Subtitle (.srt) generation screen maintaining continuous unified timestamps per sentence.
 */
@Composable
fun DocVoiceScreen(modifier: Modifier = Modifier) {
    var inputText by remember {
        mutableStateOf(
            "Hello everyone, welcome back to EngHub for English learners.\n" +
            "In today's lesson, we are going to explore how continuous audio playback keeps playing without interruption even when you switch tabs.\n" +
            "Furthermore, notice how this entire long sentence preserves its exact same unified time duration without splitting into disjoint chunks!"
        )
    }

    var startSec by remember { mutableStateOf("0.0") }
    var durationPerSentenceSec by remember { mutableStateOf("5.0") }
    var generatedSrt by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }

    fun generateSubtitles() {
        val lines = inputText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val start = startSec.toDoubleOrNull() ?: 0.0
        val step = durationPerSentenceSec.toDoubleOrNull() ?: 5.0

        val sentences = lines.mapIndexed { idx, line ->
            Sentence(
                start = start + idx * step,
                end = start + (idx + 1) * step,
                text = line
            )
        }

        // Generate using updated Exporter logic (Requirement 2)
        generatedSrt = Exporter.srt(sentences)
        statusMessage = "SRT 자막 ${sentences.size}개 문장 생성 완료 (동일 문장 타임랩 일원화 적용)"
    }

    LaunchedEffect(Unit) {
        generateSubtitles()
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("DocVoice / SRT 자막 생성기", fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text(
            "요구사항 (2) 반영: 하나의 문장에 대해 타임이 분할되어 끊기지 않고, 동일 문장은 동일한 타임랩으로 정밀 생성됩니다.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        Row(modifier = Modifier.fillMaxSize()) {
            // Left: Input
            Card(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                    Text("대본 / 스크립트 텍스트 입력", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        placeholder = { Text("줄바꿈 기준으로 문장을 입력하세요.") }
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = startSec,
                            onValueChange = { startSec = it },
                            label = { Text("시작 초") },
                            modifier = Modifier.width(100.dp),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        OutlinedTextField(
                            value = durationPerSentenceSec,
                            onValueChange = { durationPerSentenceSec = it },
                            label = { Text("문장당 초") },
                            modifier = Modifier.width(100.dp),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = { generateSubtitles() },
                            modifier = Modifier.height(56.dp)
                        ) {
                            Icon(Icons.Default.Subtitles, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("SRT 자막 생성")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Right: Generated SRT
            Card(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("생성된 .srt 자막 미리보기", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Button(
                            onClick = {
                                val dialog = FileDialog(null as Frame?, "SRT 파일 저장", FileDialog.SAVE)
                                dialog.file = "subtitle.srt"
                                dialog.isVisible = true
                                val file = dialog.file
                                val dir = dialog.directory
                                if (file != null && dir != null) {
                                    File(dir, file).writeText(generatedSrt, Charsets.UTF_8)
                                    statusMessage = "파일 저장 완료: $file"
                                }
                            },
                            enabled = generatedSrt.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("SRT 파일 저장")
                        }
                    }

                    if (statusMessage.isNotEmpty()) {
                        Text(statusMessage, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 4.dp))
                    }

                    Divider(modifier = Modifier.padding(vertical = 6.dp))

                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val scroll = rememberScrollState()
                        Text(
                            text = if (generatedSrt.isNotEmpty()) generatedSrt else "생성된 자막이 여기에 표시됩니다.",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(12.dp).verticalScroll(scroll)
                        )
                    }
                }
            }
        }
    }
}
