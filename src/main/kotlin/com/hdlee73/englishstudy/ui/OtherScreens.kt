package com.hdlee73.englishstudy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.hdlee73.englishstudy.dictionary.SavedWordsStore
import com.hdlee73.englishstudy.phrases.Phrase
import com.hdlee73.englishstudy.phrases.PhraseBank
import com.hdlee73.englishstudy.reading.ReadingArticle
import com.hdlee73.englishstudy.reading.ReadingLibrary
import com.hdlee73.englishstudy.study.FlashcardSession
import com.hdlee73.englishstudy.study.QuizQuestion
import com.hdlee73.englishstudy.study.QuizSession
import com.hdlee73.englishstudy.translate.TextTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// ---- READING SCREEN ----
@Composable
fun ReadingScreen(onLookupWord: (String) -> Unit, modifier: Modifier = Modifier) {
    var articles by remember { mutableStateOf<List<ReadingArticle>>(emptyList()) }
    var selectedArticle by remember { mutableStateOf<ReadingArticle?>(null) }

    LaunchedEffect(Unit) {
        val stream = ReadingLibrary::class.java.getResourceAsStream("/assets/reading_articles.txt")
        if (stream != null) {
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val list = ReadingLibrary.parse(text)
            articles = list
            if (list.isNotEmpty()) selectedArticle = list.first()
        }
    }

    Row(modifier = modifier.fillMaxSize().padding(16.dp)) {
        // Left: Article list
        Card(
            modifier = Modifier.width(300.dp).fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Text("리딩 아티클 (${articles.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(articles) { art ->
                        val isSel = art.id == selectedArticle?.id
                        ListItem(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedArticle = art },
                            headlineContent = {
                                Text(art.title, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = { Text("${art.topic} · 약 ${art.minutes}분", fontSize = 11.sp) }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Right: Article Content
        Card(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            val art = selectedArticle
            if (art != null) {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                    item {
                        Text(art.title, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        Text("${art.topic} | 단어 수: ${art.wordCount}단어", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                        Divider()
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    items(art.paragraphs) { para ->
                        Text(
                            text = para,
                            fontSize = 16.sp,
                            lineHeight = 26.sp,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("아티클을 선택해 주세요.", color = Color.Gray)
                }
            }
        }
    }
}

// ---- FLASHCARDS SCREEN ----
@Composable
fun FlashcardsScreen(modifier: Modifier = Modifier) {
    val savedWords by SavedWordsStore.savedWords.collectAsState()
    var session by remember { mutableStateOf<FlashcardSession?>(null) }
    var showMeaning by remember { mutableStateOf(false) }

    fun startSession() {
        if (savedWords.isNotEmpty()) {
            session = FlashcardSession(savedWords.shuffled())
            showMeaning = false
        }
    }

    LaunchedEffect(savedWords) {
        if (session == null && savedWords.isNotEmpty()) {
            startSession()
        }
    }

    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        val s = session
        if (s != null && !s.finished && s.current != null) {
            val card = s.current!!
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(0.6f)
            ) {
                Text("남은 단어: ${s.remaining}개 (암기: ${s.known} / 복습 필요: ${s.missed})", color = Color.Gray, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clickable { showMeaning = !showMeaning },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(card.word, fontWeight = FontWeight.Bold, fontSize = 32.sp)
                            Spacer(modifier = Modifier.height(12.dp))
                            if (showMeaning) {
                                Text(card.korean, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                            } else {
                                Text("(카드를 클릭하면 뜻이 표시됩니다)", fontSize = 13.sp, color = Color.Gray)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            s.answer(false)
                            showMeaning = false
                        },
                        modifier = Modifier.width(140.dp).height(50.dp)
                    ) {
                        Text("모르겠어요", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(modifier = Modifier.width(20.dp))
                    Button(
                        onClick = {
                            s.answer(true)
                            showMeaning = false
                        },
                        modifier = Modifier.width(140.dp).height(50.dp)
                    ) {
                        Text("알아요")
                    }
                }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (savedWords.isEmpty()) "단어장에 저장된 단어가 없습니다." else "플래시카드 학습을 완료했습니다!", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { startSession() }, enabled = savedWords.isNotEmpty()) {
                    Text("다시 학습하기")
                }
            }
        }
    }
}

// ---- QUIZ SCREEN ----
@Composable
fun QuizScreen(modifier: Modifier = Modifier) {
    val savedWords by SavedWordsStore.savedWords.collectAsState()
    var quizSession by remember { mutableStateOf<QuizSession?>(null) }
    var selectedOption by remember { mutableStateOf<Int?>(null) }

    fun startQuiz() {
        if (savedWords.size >= 4) {
            val questions = savedWords.shuffled().take(10).map { w ->
                val wrong = savedWords.filter { it.word != w.word }.shuffled().take(3).map { it.korean.lines().first() }
                val right = w.korean.lines().first()
                val options = (wrong + right).shuffled()
                QuizQuestion(w.word, right, options)
            }
            quizSession = QuizSession(questions)
            selectedOption = null
        }
    }

    LaunchedEffect(savedWords) {
        if (quizSession == null && savedWords.size >= 4) {
            startQuiz()
        }
    }

    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        val q = quizSession
        if (savedWords.size < 4) {
            Text("퀴즈를 풀려면 최소 4개 이상의 단어가 단어장에 저장되어 있어야 합니다.", color = Color.Gray)
        } else if (q != null && !q.finished && q.current != null) {
            val cur = q.current!!
            Column(modifier = Modifier.fillMaxWidth(0.6f)) {
                Text("문제 ${q.currentIndex + 1} / ${q.questions.size} (현재 점수: ${q.score})", color = Color.Gray, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(cur.word, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    }
                }
                cur.options.forEachIndexed { idx, opt ->
                    OutlinedButton(
                        onClick = {
                            q.submitAnswer(idx)
                            selectedOption = null
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(50.dp)
                    ) {
                        Text("${idx + 1}. $opt", fontSize = 15.sp)
                    }
                }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("퀴즈 완료! 최종 점수: ${quizSession?.score ?: 0} / ${quizSession?.questions?.size ?: 0}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { startQuiz() }) {
                    Text("새 퀴즈 시작")
                }
            }
        }
    }
}

// ---- PHRASES SCREEN ----
@Composable
fun PhrasesScreen(modifier: Modifier = Modifier) {
    var allPhrases by remember { mutableStateOf<List<Phrase>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val stream = PhraseBank::class.java.getResourceAsStream("/assets/phrases_us_uk.txt")
        if (stream != null) {
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            allPhrases = PhraseBank.parse(text)
        }
    }

    val filtered = remember(allPhrases, query, selectedCategory) {
        PhraseBank.search(allPhrases, query, category = selectedCategory)
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("표현, 패턴, 뜻 검색") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            FilterChip(
                selected = selectedCategory == null,
                onClick = { selectedCategory = null },
                label = { Text("전체") },
                modifier = Modifier.padding(end = 6.dp)
            )
            PhraseBank.CATEGORIES.forEach { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                    label = { Text(cat) },
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filtered) { p ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(p.phrase, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text("${p.region} · ${p.category}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(p.korean, fontSize = 14.sp)
                        if (p.example.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("예: ${p.example}", color = Color.Gray, fontSize = 13.sp)
                            Text("    ${p.exampleKo}", color = Color.Gray, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

// ---- TRANSLATE SCREEN ----
@Composable
fun TranslateScreen(modifier: Modifier = Modifier) {
    var sourceText by remember { mutableStateOf("") }
    var translatedText by remember { mutableStateOf("") }
    var isTranslating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("구글 텍스트 번역", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxSize()) {
            Card(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Text("원문 입력 (영어 / 한국어)", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sourceText,
                        onValueChange = { sourceText = it },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        placeholder = { Text("번역할 영어 또는 한글 텍스트를 입력하세요.") }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            if (sourceText.isNotBlank()) {
                                isTranslating = true
                                scope.launch(Dispatchers.IO) {
                                    val res = TextTranslator.translate(sourceText)
                                    translatedText = res
                                    isTranslating = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text(if (isTranslating) "번역 중..." else "번역 실행")
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Card(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Text("번역 결과", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            if (isTranslating) {
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                            } else {
                                Text(
                                    text = if (translatedText.isNotEmpty()) translatedText else "번역 결과가 여기에 표시됩니다.",
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
