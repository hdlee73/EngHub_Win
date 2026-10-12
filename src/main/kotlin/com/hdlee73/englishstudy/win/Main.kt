package com.hdlee73.englishstudy.win

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.hdlee73.englishstudy.audio.GlobalPlaybackManager
import com.hdlee73.englishstudy.ui.*

@Composable
fun App() {
    var currentTab by remember { mutableStateOf(AppTab.LISTENING) }
    var dictionaryQuery by remember { mutableStateOf("") }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF6750A4),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFEADDFF),
            onPrimaryContainer = Color(0xFF21005D),
            surface = Color(0xFFFDF8FD),
            surfaceVariant = Color(0xFFE7E0EC)
        )
    ) {
        Scaffold(
            bottomBar = {
                // Requirement (1): MiniPlayerBar stays visible and audio keeps playing across all tabs
                MiniPlayerBar(
                    onNavigateToListening = { currentTab = AppTab.LISTENING }
                )
            }
        ) { paddingValues ->
            Row(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                // Left Navigation Bar (Windows Desktop App style)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .width(220.dp)
                        .fillMaxHeight()
                ) {
                    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 14.dp)
                        ) {
                            Text(
                                "EngHub Win",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Divider(modifier = Modifier.padding(bottom = 12.dp))

                        AppTab.values().forEach { tab ->
                            val isSelected = currentTab == tab
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .clickable {
                                        currentTab = tab
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label,
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.DarkGray,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = tab.label,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.DarkGray
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            "v1.0.0 (Windows)",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                        )
                    }
                }

                // Main Content View
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    when (currentTab) {
                        AppTab.LISTENING -> ListeningScreen(
                            onLookupWord = { word, timestampMs ->
                                // Requirement (3): capture word and timestamp, then navigate to Dictionary
                                GlobalPlaybackManager.setWordLookupContext(word, timestampMs)
                                dictionaryQuery = word
                                currentTab = AppTab.DICTIONARY
                            }
                        )
                        AppTab.DICTIONARY -> DictionaryScreen(
                            initialQuery = dictionaryQuery,
                            onNavigateToListening = {
                                currentTab = AppTab.LISTENING
                            }
                        )
                        AppTab.READING -> ReadingScreen(
                            onLookupWord = { word ->
                                dictionaryQuery = word
                                currentTab = AppTab.DICTIONARY
                            }
                        )
                        AppTab.FLASHCARDS -> FlashcardsScreen()
                        AppTab.QUIZ -> QuizScreen()
                        AppTab.PHRASES -> PhrasesScreen()
                        AppTab.TRANSLATE -> TranslateScreen()
                        AppTab.DOCVOICE -> DocVoiceScreen()
                    }
                }
            }
        }
    }
}

fun main() = application {
    val windowState = rememberWindowState(width = 1180.dp, height = 780.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = "EngHub - English Study App (Windows)",
        state = windowState
    ) {
        App()
    }
}
