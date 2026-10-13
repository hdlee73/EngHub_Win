package com.hdlee73.englishstudy.listening

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hdlee73.englishstudy.R
import com.hdlee73.englishstudy.ui.Hero
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private val LsBg = Color(0xFFEAF3F1)
private val LsCard = Color(0xFFFFFFFF)
private val LsCardBorder = Color(0xFFDCE9E6)
private val LsMint = Color(0xFFDDF1EC)
private val LsTeal = Color(0xFF168C82)
private val LsTealDark = Color(0xFF0C6F67)
private val LsTextPrimary = Color(0xFF172B2A)
private val LsTextSecondary = Color(0xFF6B7D7A)
private val LsCoral = Color(0xFFF28C72)
private val LsTrack = Color(0xFFCFE4DF)

/**
 * The Listening tab implemented in Compose with 100% visual & functional parity with Android's ls_activity_main.xml.
 *
 * - Modification (1): Uses [PlaybackService.instance] singleton and never pauses/stops when navigating away to other tabs.
 * - Modification (3): [onLookup] and [onLookupAt] pass the subtitle cue's start timestamp so returning from Dictionary
 *   resumes playback at that exact word/sentence position.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListeningScreen(
    pendingIntent: Intent?,
    onIntentConsumed: () -> Unit,
    modifier: Modifier = Modifier,
    onLookup: (String) -> Unit = {},
    onLookupAt: (String, Long?) -> Unit = { word, _ -> onLookup(word) },
    onTranslate: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val svc = remember { PlaybackService.instance }

    // UI refresh tick while ListeningScreen is visible (does NOT stop playback on dispose!)
    var uiTick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        svc.importPending()
        while (true) {
            uiTick++
            delay(100L)
        }
    }

    // Dialog states
    var showAddMenu by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var showPlaylistSheet by remember { mutableStateOf(false) }
    var showSubtitleSheet by remember { mutableStateOf(false) }
    var showSubtitleListSheet by remember { mutableStateOf(false) }
    var showSrtDialog by remember { mutableStateOf(false) }
    var showAbLimitMenu by remember { mutableStateOf(false) }
    var showSaveRangeDialog by remember { mutableStateOf(false) }
    var showSavedRangesDialog by remember { mutableStateOf(false) }

    // Audio file picker
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val entries = uris.map { uri ->
                val name = resolveDisplayName(context, uri) ?: "오디오"
                TrackStore.Entry(uri.toString(), name)
            }
            val added = svc.addEntries(entries, svc.activeGroup, startIfIdle = true)
            if (added > 0) {
                Toast.makeText(context, "${added}개 파일을 추가했습니다.", Toast.LENGTH_SHORT).show()
            }
            uiTick++
        }
    }

    // Folder picker
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            scope.launch {
                val root = withContext(Dispatchers.IO) { FolderImport.scan(context, treeUri) }
                if (root == null || !root.hasAudio) {
                    Toast.makeText(context, "폴더에서 오디오 파일을 찾지 못했어요.", Toast.LENGTH_SHORT).show()
                } else {
                    val added = svc.applyScan(treeUri.toString(), root, force = true)
                    Toast.makeText(context, "폴더에서 ${added}개 트랙을 가져왔어요.", Toast.LENGTH_SHORT).show()
                    uiTick++
                }
            }
        }
    }

    // Subtitle file picker
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            svc.setSubtitle(uri.toString())
            Toast.makeText(context, "자막 파일을 연결했습니다.", Toast.LENGTH_SHORT).show()
            uiTick++
        }
    }

    LaunchedEffect(pendingIntent) {
        val intent = pendingIntent ?: return@LaunchedEffect
        when (intent.action) {
            ListeningLink.ACTION_PICK_DRIVE -> audioPicker.launch(arrayOf("audio/*"))
            ListeningLink.ACTION_RESUME_LAST -> svc.resume()
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    val name = resolveDisplayName(context, uri) ?: "오디오"
                    svc.openAndPlay(TrackStore.Entry(uri.toString(), name))
                }
            }
        }
        onIntentConsumed()
        uiTick++
    }

    // Read live state from PlaybackService (re-evaluated on uiTick)
    val tickVal = uiTick
    val isPlaying = remember(tickVal) { svc.isActive }
    val posMs = remember(tickVal) { svc.player.currentPosition.coerceAtLeast(0L) }
    val durMs = remember(tickVal) { svc.player.duration.coerceAtLeast(0L) }
    val currentTitle = remember(tickVal) { svc.currentTitle }
    val groupName = remember(tickVal) { svc.activeGroupName }
    val mediaCount = remember(tickVal) { svc.player.mediaItemCount }
    val currentIndex = remember(tickVal) { svc.player.currentMediaItemIndex }
    val abStart = remember(tickVal) { svc.abStartMs }
    val abEnd = remember(tickVal) { svc.abEndMs }
    val abLimit = remember(tickVal) { svc.abRepeatLimit }
    val repeatMode = remember(tickVal) { svc.repeatMode }
    val shuffle = remember(tickVal) { svc.shuffle }
    val speed = remember(tickVal) { svc.speed }
    val gapMs = remember(tickVal) { svc.gapMs }
    val subtitleUri = remember(tickVal) { svc.subtitleUri }
    val sleepRem = remember(tickVal) { svc.sleepRemainingMs }
    val sleepTrackEnd = remember(tickVal) { svc.sleepAtTrackEnd }
    val bookmarks = remember(tickVal) { svc.bookmarks.clone() }
    val savedRanges = remember(tickVal) { svc.savedRanges.toList() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LsBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Hero(
            "🎧",
            "Listening",
            "MP3 · A-B 반복 · 자막으로 귀를 열어요",
            Modifier.padding(bottom = 12.dp)
        )

        // Header bar matching ls_activity_main.xml top action row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (sleepRem > 0L || sleepTrackEnd) {
                Surface(
                    color = LsMint,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.clickable { showSleepDialog = true }
                ) {
                    Text(
                        text = if (sleepTrackEnd) "⏱ 이 곡 끝까지" else "⏱ ${formatTime(sleepRem)}",
                        color = LsTealDark,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            } else if (groupName.isNotBlank()) {
                Surface(
                    color = LsMint,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.clickable { showPlaylistSheet = true }
                ) {
                    Text(
                        text = "📁 $groupName (${if (mediaCount > 0) "${currentIndex + 1}/$mediaCount" else "0"})",
                        color = LsTealDark,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // Add button + dropdown
            Box {
                HeaderIconButton(R.drawable.ls_ic_add, "파일 추가") { showAddMenu = true }
                DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("오디오 파일 선택") },
                        onClick = {
                            showAddMenu = false
                            audioPicker.launch(arrayOf("audio/*"))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("기기 폴더에서 가져오기") },
                        onClick = {
                            showAddMenu = false
                            folderPicker.launch(null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("연결된 폴더 새로고침") },
                        onClick = {
                            showAddMenu = false
                            scope.launch {
                                val trees = svc.linkedTrees()
                                if (trees.isEmpty()) {
                                    Toast.makeText(context, "연결된 폴더가 없어요. 먼저 폴더를 가져와 주세요.", Toast.LENGTH_SHORT).show()
                                } else {
                                    var total = 0
                                    withContext(Dispatchers.IO) {
                                        trees.forEach { t ->
                                            FolderImport.scan(context, Uri.parse(t))?.let { root ->
                                                total += svc.applyScan(t, root, force = false)
                                            }
                                        }
                                    }
                                    Toast.makeText(context, if (total > 0) "새 파일 ${total}개를 추가했어요." else "새로 추가된 파일이 없어요.", Toast.LENGTH_SHORT).show()
                                    uiTick++
                                }
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("BBC 6 Minute English 새로고침") },
                        onClick = {
                            showAddMenu = false
                            Toast.makeText(context, "BBC 6 Minute English 확인 중…", Toast.LENGTH_SHORT).show()
                            scope.launch(Dispatchers.IO) {
                                SixMinuteEnglish.fetchLatest(context)
                                withContext(Dispatchers.Main) {
                                    svc.importPending()
                                    uiTick++
                                }
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.width(6.dp))
            HeaderIconButton(R.drawable.ls_ic_list, "재생목록") { showPlaylistSheet = true }
            Spacer(Modifier.width(6.dp))
            HeaderIconButton(
                iconRes = R.drawable.ls_ic_subtitles,
                contentDesc = "자막",
                badge = subtitleUri != null
            ) { showSubtitleSheet = true }
            Spacer(Modifier.width(6.dp))
            HeaderIconButton(
                iconRes = R.drawable.ls_ic_timer,
                contentDesc = "취침 타이머",
                badge = sleepRem > 0L || sleepTrackEnd
            ) { showSleepDialog = true }
            Spacer(Modifier.width(6.dp))
            HeaderIconButton(
                iconRes = R.drawable.ls_ic_settings,
                contentDesc = "재생 설정",
                badge = speed != 1.0f || gapMs > 0L
            ) { showSettingsDialog = true }
        }

        Spacer(Modifier.height(10.dp))

        // NOW PLAYING CARD
        Surface(
            color = LsCard,
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, LsCardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = currentTitle ?: "재생할 MP3 파일을 선택하세요",
                    color = LsTextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                // Quick subtitle chips
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = LsMint,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.clickable { showSrtDialog = true }
                    ) {
                        Text(
                            text = "📝 자막 만들기",
                            color = LsTealDark,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = LsMint,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.clickable { showSubtitleListSheet = true }
                    ) {
                        Text(
                            text = "📄 자막 목록",
                            color = LsTealDark,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    if (speed != 1.0f || gapMs > 0L) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = LsMint,
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier.clickable { showSettingsDialog = true }
                        ) {
                            val speedTxt = String.format("%.2fx", speed).replace("0x", "x").replace(".x", ".0x")
                            val gapTxt = if (gapMs > 0L) " · 쉼 ${gapMs / 1000f}s" else ""
                            Text(
                                text = "⚡ $speedTxt$gapTxt",
                                color = LsTealDark,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // RepeatSeekBar with A-B markers and bookmarks
                RepeatSeekBarComposable(
                    positionMs = posMs,
                    durationMs = durMs,
                    abStartMs = abStart,
                    abEndMs = abEnd,
                    bookmarks = bookmarks,
                    onSeek = { target ->
                        svc.player.seekTo(target)
                        uiTick++
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(posMs),
                        color = LsTextSecondary,
                        fontSize = 12.sp
                    )
                    val remaining = (durMs - posMs).coerceAtLeast(0L)
                    Text(
                        text = "-${formatTime(remaining)}",
                        color = LsTextSecondary,
                        fontSize = 12.sp
                    )
                }

                Spacer(Modifier.height(8.dp))

                // 3 Bookmark slots
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (slot in 0 until TrackStore.BOOKMARK_SLOTS) {
                        val bmPos = bookmarks.getOrElse(slot) { -1L }
                        val saved = bmPos >= 0L
                        Surface(
                            color = if (saved) LsMint else Color.Transparent,
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (saved) LsTeal else LsCardBorder),
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .combinedClickable(
                                    onClick = {
                                        if (saved) {
                                            svc.player.seekTo(bmPos)
                                            svc.resume()
                                        } else if (svc.currentTrackUri != null) {
                                            svc.toggleBookmark(slot)
                                            Toast.makeText(context, "북마크 ${slot + 1} 저장 (${formatTime(svc.player.currentPosition)})", Toast.LENGTH_SHORT).show()
                                        }
                                        uiTick++
                                    },
                                    onLongClick = {
                                        if (svc.currentTrackUri != null) {
                                            val added = svc.toggleBookmark(slot)
                                            Toast.makeText(
                                                context,
                                                if (added) "북마크 ${slot + 1} 저장" else "북마크 ${slot + 1} 해제",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            uiTick++
                                        }
                                    }
                                )
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (saved) "★ ${slot + 1}  ${formatTime(bmPos)}" else "☆ ${slot + 1}",
                                    color = if (saved) LsTealDark else LsTextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Transport controls: Shuffle, Prev, Play/Pause, Next, Repeat
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            svc.updateShuffle(!shuffle)
                            uiTick++
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ls_ic_shuffle),
                            contentDescription = "셔플",
                            tint = if (shuffle) LsTeal else LsTextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            if (svc.player.currentPosition > 3000L) {
                                svc.player.seekTo(0L)
                            } else if (currentIndex > 0) {
                                svc.playIndex(currentIndex - 1)
                            } else {
                                svc.player.seekTo(0L)
                            }
                            uiTick++
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ls_ic_prev),
                            contentDescription = "이전",
                            tint = LsTextPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    // Big Play/Pause Circle Button
                    Surface(
                        color = LsTeal,
                        shape = CircleShape,
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .clickable {
                                if (svc.player.mediaItemCount == 0) {
                                    audioPicker.launch(arrayOf("audio/*"))
                                } else {
                                    svc.togglePlay()
                                }
                                uiTick++
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(if (isPlaying) R.drawable.ls_ic_pause else R.drawable.ls_ic_play),
                                contentDescription = if (isPlaying) "일시정지" else "재생",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    IconButton(
                        onClick = {
                            if (currentIndex + 1 < mediaCount) {
                                svc.playIndex(currentIndex + 1)
                            } else if (mediaCount > 0) {
                                svc.playIndex(0)
                            }
                            uiTick++
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ls_ic_next),
                            contentDescription = "다음",
                            tint = LsTextPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            val nextMode = (repeatMode + 1) % 3
                            svc.updateRepeatMode(nextMode)
                            val msg = when (nextMode) {
                                1 -> "한 곡 반복"
                                2 -> "전체 반복"
                                else -> "반복 끔"
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            uiTick++
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            painter = painterResource(
                                if (repeatMode == 1) R.drawable.ls_ic_repeat_one else R.drawable.ls_ic_repeat
                            ),
                            contentDescription = "반복 모드",
                            tint = if (repeatMode > 0) LsTeal else LsTextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // A-B REPEAT CARD
        Surface(
            color = LsCard,
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, LsCardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "구간 반복",
                            color = LsTextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        val abStatusText = when {
                            abStart >= 0L && abEnd > abStart -> "${formatTime(abStart)} ~ ${formatTime(abEnd)} 반복 중"
                            abStart >= 0L -> "A ${formatTime(abStart)} → B 지정 대기"
                            else -> "지정 안 됨"
                        }
                        Text(
                            text = abStatusText,
                            color = LsTeal,
                            fontSize = 12.sp
                        )
                    }

                    // Repeat count pill
                    Box {
                        Surface(
                            color = LsMint,
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier.clickable { showAbLimitMenu = true }
                        ) {
                            val limitLabel = if (abLimit <= 0) "횟수 무제한 ▾" else "${abLimit}회 반복 ▾"
                            Text(
                                text = limitLabel,
                                color = LsTealDark,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                        DropdownMenu(expanded = showAbLimitMenu, onDismissRequest = { showAbLimitMenu = false }) {
                            listOf(-1 to "횟수 무제한", 3 to "3회 반복", 5 to "5회 반복", 10 to "10회 반복", 20 to "20회 반복").forEach { (lim, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        showAbLimitMenu = false
                                        svc.updateAbRepeatLimit(lim)
                                        uiTick++
                                    }
                                )
                            }
                        }
                    }

                    if (abStart >= 0L || abEnd >= 0L) {
                        Spacer(Modifier.width(6.dp))
                        TextButton(
                            onClick = {
                                svc.clearAb()
                                uiTick++
                            }
                        ) {
                            Text("해제", color = LsCoral, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Side-by-side A and B boxes
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AbPointBox(
                        modifier = Modifier.weight(1f),
                        label = "A 지정",
                        timeText = if (abStart >= 0L) formatPreciseTime(abStart) else "--:--",
                        enabledNudge = abStart >= 0L,
                        onSet = {
                            svc.setA(svc.player.currentPosition)
                            uiTick++
                        },
                        onMinus = {
                            svc.nudgeA(-500L)
                            uiTick++
                        },
                        onPlus = {
                            svc.nudgeA(500L)
                            uiTick++
                        }
                    )
                    AbPointBox(
                        modifier = Modifier.weight(1f),
                        label = "B 지정",
                        timeText = if (abEnd > abStart && abStart >= 0L) formatPreciseTime(abEnd) else "--:--",
                        enabledNudge = abEnd > abStart && abStart >= 0L,
                        onSet = {
                            if (svc.abStartMs < 0L) {
                                Toast.makeText(context, "먼저 A 시작점을 지정해 주세요.", Toast.LENGTH_SHORT).show()
                            } else if (!svc.setB(svc.player.currentPosition)) {
                                Toast.makeText(context, "B 끝점은 A 시작점보다 뒤여야 합니다.", Toast.LENGTH_SHORT).show()
                            } else {
                                svc.resume()
                            }
                            uiTick++
                        },
                        onMinus = {
                            svc.nudgeB(-500L)
                            uiTick++
                        },
                        onPlus = {
                            svc.nudgeB(500L)
                            uiTick++
                        }
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Save range & Saved ranges buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (abStart >= 0L && abEnd > abStart) {
                                showSaveRangeDialog = true
                            } else {
                                Toast.makeText(context, "먼저 A-B 구간을 지정해 주세요.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = abStart >= 0L && abEnd > abStart,
                        modifier = Modifier.weight(1f).height(38.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("구간 저장", color = LsTeal, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { showSavedRangesDialog = true },
                        modifier = Modifier.weight(1f).height(38.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (savedRanges.isEmpty()) "저장 목록" else "저장 목록 (${savedRanges.size})",
                            color = LsTeal,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    // --- DIALOGS & SHEETS ---

    if (showSettingsDialog) {
        PlaybackSettingsDialog(
            speed = speed,
            gapMs = gapMs,
            onSpeed = {
                svc.setSpeed(it)
                uiTick++
            },
            onGap = {
                svc.updateGap(it)
                uiTick++
            },
            onDismiss = { showSettingsDialog = false }
        )
    }

    if (showSleepDialog) {
        SleepTimerDialog(
            sleepRemainingMs = sleepRem,
            sleepAtTrackEnd = sleepTrackEnd,
            onSelectMinutes = { mins ->
                svc.setSleepTimer(mins)
                showSleepDialog = false
                uiTick++
            },
            onTrackEnd = {
                svc.sleepAtEndOfTrack()
                showSleepDialog = false
                uiTick++
            },
            onCancel = {
                svc.cancelSleepTimer()
                showSleepDialog = false
                uiTick++
            },
            onDismiss = { showSleepDialog = false }
        )
    }

    if (showSaveRangeDialog) {
        var rangeName by remember { mutableStateOf("구간 ${savedRanges.size + 1}") }
        AlertDialog(
            onDismissRequest = { showSaveRangeDialog = false },
            title = { Text("구간 저장", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = rangeName,
                    onValueChange = { rangeName = it },
                    label = { Text("구간 이름") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    svc.saveCurrentRange(rangeName)
                    showSaveRangeDialog = false
                    Toast.makeText(context, "구간을 저장했습니다.", Toast.LENGTH_SHORT).show()
                    uiTick++
                }) { Text("저장") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveRangeDialog = false }) { Text("취소") }
            }
        )
    }

    if (showSavedRangesDialog) {
        AlertDialog(
            onDismissRequest = { showSavedRangesDialog = false },
            title = { Text("저장된 구간 목록", fontWeight = FontWeight.Bold) },
            text = {
                if (savedRanges.isEmpty()) {
                    Text("저장된 반복 구간이 없습니다.", color = LsTextSecondary)
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        savedRanges.forEachIndexed { idx, r ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(LsMint)
                                    .clickable {
                                        svc.applyRange(idx)
                                        showSavedRangesDialog = false
                                        uiTick++
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(r.name, fontWeight = FontWeight.Bold, color = LsTextPrimary, fontSize = 14.sp)
                                    Text("${formatPreciseTime(r.startMs)} ~ ${formatPreciseTime(r.endMs)}", color = LsTealDark, fontSize = 12.sp)
                                }
                                TextButton(onClick = {
                                    svc.deleteRange(idx)
                                    uiTick++
                                }) {
                                    Text("삭제", color = LsCoral, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSavedRangesDialog = false }) { Text("닫기") }
            }
        )
    }

    if (showPlaylistSheet) {
        PlaylistDialog(
            svc = svc,
            uiTick = uiTick,
            onTick = { uiTick++ },
            onAddFiles = { audioPicker.launch(arrayOf("audio/*")) },
            onAddFolder = { folderPicker.launch(null) },
            onDismiss = { showPlaylistSheet = false }
        )
    }

    if (showSubtitleSheet) {
        SubtitleDialog(
            context = context,
            svc = svc,
            uiTick = uiTick,
            onTick = { uiTick++ },
            onPickSubtitle = { subtitlePicker.launch(arrayOf("*/*")) },
            onLookupAt = { word, cueStartMs ->
                showSubtitleSheet = false
                onLookupAt(word, cueStartMs)
            },
            onTranslate = { text ->
                showSubtitleSheet = false
                onTranslate(text)
            },
            onDismiss = { showSubtitleSheet = false }
        )
    }

    if (showSubtitleListSheet) {
        SubtitleListDialog(
            context = context,
            svc = svc,
            onChanged = { uiTick++ },
            onDismiss = { showSubtitleListSheet = false }
        )
    }

    if (showSrtDialog) {
        SrtMakerDialog(
            context = context,
            svc = svc,
            onDismiss = { showSrtDialog = false }
        )
    }
}

@Composable
private fun HeaderIconButton(
    iconRes: Int,
    contentDesc: String,
    badge: Boolean = false,
    onClick: () -> Unit
) {
    Box {
        Surface(
            color = LsCard,
            shape = CircleShape,
            border = androidx.compose.foundation.BorderStroke(1.dp, LsCardBorder),
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = contentDesc,
                    tint = LsTealDark,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (badge) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(LsCoral)
            )
        }
    }
}

@Composable
private fun AbPointBox(
    modifier: Modifier = Modifier,
    label: String,
    timeText: String,
    enabledNudge: Boolean,
    onSet: () -> Unit,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Surface(
        color = LsMint,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.height(50.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onMinus,
                enabled = enabledNudge,
                modifier = Modifier.width(36.dp).fillMaxHeight(),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("−", color = if (enabledNudge) LsTealDark else LsTextSecondary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(onClick = onSet),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(label, color = LsTealDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(timeText, color = LsTextSecondary, fontSize = 11.sp)
            }

            TextButton(
                onClick = onPlus,
                enabled = enabledNudge,
                modifier = Modifier.width(36.dp).fillMaxHeight(),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("+", color = if (enabledNudge) LsTealDark else LsTextSecondary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RepeatSeekBarComposable(
    positionMs: Long,
    durationMs: Long,
    abStartMs: Long,
    abEndMs: Long,
    bookmarks: LongArray,
    onSeek: (Long) -> Unit
) {
    val safeDur = durationMs.coerceAtLeast(1L)
    val progress = (positionMs.toFloat() / safeDur.toFloat()).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(safeDur) {
                detectTapGestures { offset ->
                    val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onSeek((frac * safeDur).toLong())
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(24.dp)) {
            val w = size.width
            val h = size.height
            val cy = h / 2f
            val trackH = 6.dp.toPx()

            // Track background
            drawRoundRect(
                color = LsTrack,
                topLeft = Offset(0f, cy - trackH / 2f),
                size = Size(w, trackH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH / 2f, trackH / 2f)
            )

            // A-B Repeat highlighted segment
            if (abStartMs >= 0L && durationMs > 0L) {
                val ax = (abStartMs.toFloat() / safeDur.toFloat()).coerceIn(0f, 1f) * w
                if (abEndMs > abStartMs) {
                    val bx = (abEndMs.toFloat() / safeDur.toFloat()).coerceIn(0f, 1f) * w
                    drawRect(
                        color = LsCoral.copy(alpha = 0.35f),
                        topLeft = Offset(ax, cy - trackH),
                        size = Size((bx - ax).coerceAtLeast(2f), trackH * 2f)
                    )
                    drawLine(
                        color = LsCoral,
                        start = Offset(bx, cy - 9.dp.toPx()),
                        end = Offset(bx, cy + 9.dp.toPx()),
                        strokeWidth = 2.5.dp.toPx()
                    )
                }
                drawLine(
                    color = LsCoral,
                    start = Offset(ax, cy - 9.dp.toPx()),
                    end = Offset(ax, cy + 9.dp.toPx()),
                    strokeWidth = 2.5.dp.toPx()
                )
            }

            // Played progress
            val px = progress * w
            drawRoundRect(
                color = LsTeal,
                topLeft = Offset(0f, cy - trackH / 2f),
                size = Size(px, trackH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH / 2f, trackH / 2f)
            )

            // Bookmarks
            if (durationMs > 0L) {
                bookmarks.forEach { bm ->
                    if (bm >= 0L) {
                        val bmx = (bm.toFloat() / safeDur.toFloat()).coerceIn(0f, 1f) * w
                        drawCircle(
                            color = LsCoral,
                            radius = 4.dp.toPx(),
                            center = Offset(bmx, cy - trackH)
                        )
                    }
                }
            }

            // Thumb
            drawCircle(
                color = LsTealDark,
                radius = 7.dp.toPx(),
                center = Offset(px, cy)
            )
        }
    }
}

@Composable
private fun PlaybackSettingsDialog(
    speed: Float,
    gapMs: Long,
    onSpeed: (Float) -> Unit,
    onGap: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("재생 속도 · 쉐도잉 쉼 간격", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("재생 속도: ${String.format("%.2fx", speed)}", fontWeight = FontWeight.Bold, color = LsTextPrimary)
                Slider(
                    value = speed,
                    onValueChange = { onSpeed((Math.round(it * 20f) / 20f).coerceIn(0.5f, 2.0f)) },
                    valueRange = 0.5f..2.0f
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(0.75f, 0.9f, 1.0f, 1.15f, 1.25f, 1.5f).forEach { preset ->
                        FilterChip(
                            selected = kotlin.math.abs(speed - preset) < 0.03f,
                            onClick = { onSpeed(preset) },
                            label = { Text("${preset}x", fontSize = 12.sp) }
                        )
                    }
                }

                Divider()

                val gapSec = gapMs / 1000f
                Text("반복 사이 쉼 간격 (쉐도잉): ${String.format("%.1f초", gapSec)}", fontWeight = FontWeight.Bold, color = LsTextPrimary)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(0L to "없음", 1000L to "1초", 2000L to "2초", 3000L to "3초", 5000L to "5초").forEach { (ms, label) ->
                        FilterChip(
                            selected = gapMs == ms,
                            onClick = { onGap(ms) },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("확인") }
        }
    )
}

@Composable
private fun SleepTimerDialog(
    sleepRemainingMs: Long,
    sleepAtTrackEnd: Boolean,
    onSelectMinutes: (Int) -> Unit,
    onTrackEnd: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("취침 타이머", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (sleepRemainingMs > 0L || sleepAtTrackEnd) {
                    Text(
                        text = if (sleepAtTrackEnd) "현재 설정: 이 곡이 끝나면 정지" else "남은 시간: ${formatTime(sleepRemainingMs)}",
                        color = LsTeal,
                        fontWeight = FontWeight.Bold
                    )
                }
                listOf(10, 20, 30, 45, 60, 90).forEach { mins ->
                    TextButton(
                        onClick = { onSelectMinutes(mins) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("${mins}분 후 정지", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                    }
                }
                TextButton(
                    onClick = onTrackEnd,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("지금 곡이 끝나면 정지", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                }
                if (sleepRemainingMs > 0L || sleepAtTrackEnd) {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("타이머 해제", color = LsCoral, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    )
}

@Composable
private fun PlaylistDialog(
    svc: PlaybackService,
    uiTick: Long,
    onTick: () -> Unit,
    onAddFiles: () -> Unit,
    onAddFolder: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var viewingGroup by remember { mutableStateOf(svc.activeGroup) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showRenameFolderDialog by remember { mutableStateOf(false) }

    val groups = remember(uiTick, viewingGroup) { svc.groups.toList() }
    val safeGroup = viewingGroup.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
    val entries = remember(uiTick, safeGroup) { svc.groupEntries(safeGroup) }
    val currentUri = remember(uiTick) { svc.currentTrackUri }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = LsCard,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.86f)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("재생목록 · 폴더", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = LsTextPrimary)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onAddFiles) { Text("+ 파일", color = LsTeal, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = onAddFolder) { Text("+ 기기 폴더", color = LsTeal, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = { showNewFolderDialog = true }) { Text("+ 새 폴더", color = LsTeal, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = onDismiss) { Text("닫기") }
                }

                // Folder tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    groups.forEachIndexed { idx, g ->
                        val selected = idx == safeGroup
                        FilterChip(
                            selected = selected,
                            onClick = {
                                viewingGroup = idx
                                svc.switchGroup(idx, play = false)
                                onTick()
                            },
                            label = {
                                Text(
                                    text = "📁 ${g.name} (${svc.groupEntries(idx).size})",
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }

                // Folder actions bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${groups.getOrNull(safeGroup)?.name ?: ""} · ${entries.size}곡",
                        color = LsTextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        svc.sortGroupByName(safeGroup)
                        onTick()
                    }) { Text("이름순 정렬", fontSize = 12.sp) }
                    TextButton(onClick = { showRenameFolderDialog = true }) { Text("이름 변경", fontSize = 12.sp) }
                    if (groups.size > 1) {
                        TextButton(onClick = {
                            svc.deleteGroup(safeGroup)
                            viewingGroup = svc.activeGroup
                            onTick()
                        }) { Text("폴더 삭제", color = LsCoral, fontSize = 12.sp) }
                    } else if (entries.isNotEmpty()) {
                        TextButton(onClick = {
                            svc.clearGroup(safeGroup)
                            onTick()
                        }) { Text("비우기", color = LsCoral, fontSize = 12.sp) }
                    }
                }

                Divider()

                if (entries.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("이 폴더에 오디오 파일이 없어요.", color = LsTextSecondary)
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = onAddFiles,
                                colors = ButtonDefaults.buttonColors(containerColor = LsTeal)
                            ) {
                                Text("오디오 파일 추가")
                            }
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        itemsIndexed(entries) { idx, entry ->
                            val isCurrent = entry.uri == currentUri
                            val isDone = svc.isDone(entry.uri)
                            val hasSub = SubtitleLinks.find(context, entry.uri, entry.name) != null ||
                                svc.store.getState(entry.uri).subtitleUri != null

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isCurrent) LsMint else Color.Transparent)
                                    .clickable {
                                        svc.playIn(safeGroup, idx)
                                        onTick()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = when {
                                        isCurrent -> "▶"
                                        isDone -> "✓"
                                        else -> "${idx + 1}."
                                    },
                                    color = if (isCurrent) LsTealDark else LsTextSecondary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(28.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.name,
                                        color = if (isCurrent) LsTealDark else LsTextPrimary,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (hasSub) {
                                        Text("자막 연결됨", color = LsTeal, fontSize = 11.sp)
                                    }
                                }
                                TextButton(onClick = {
                                    SrtMaker.start(context, listOf(entry))
                                    onTick()
                                }) {
                                    Text("자막생성", fontSize = 11.sp, color = LsTealDark)
                                }
                                TextButton(onClick = {
                                    svc.removeFrom(safeGroup, idx)
                                    onTick()
                                }) {
                                    Text("삭제", fontSize = 11.sp, color = LsCoral)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewFolderDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("새 폴더 만들기", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("폴더 이름") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val idx = svc.createGroup(name.ifBlank { "새 폴더" })
                    viewingGroup = idx
                    svc.switchGroup(idx, play = false)
                    showNewFolderDialog = false
                    onTick()
                }) { Text("만들기") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text("취소") }
            }
        )
    }

    if (showRenameFolderDialog) {
        var name by remember { mutableStateOf(groups.getOrNull(safeGroup)?.name ?: "") }
        AlertDialog(
            onDismissRequest = { showRenameFolderDialog = false },
            title = { Text("폴더 이름 변경", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("폴더 이름") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    svc.renameGroup(safeGroup, name)
                    showRenameFolderDialog = false
                    onTick()
                }) { Text("변경") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameFolderDialog = false }) { Text("취소") }
            }
        )
    }
}

/**
 * Subtitle viewer & word/sentence interaction dialog matching Android's SubtitleSheet.
 *
 * Modification (3): When a user selects a word or phrase in a subtitle cue and clicks "단어장·사전",
 * [onLookupAt] is invoked with `(selectedWord, cue.startMs)` so that returning to the Listening tab
 * resumes playback from the exact subtitle timestamp where the word appeared.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SubtitleDialog(
    context: Context,
    svc: PlaybackService,
    uiTick: Long,
    onTick: () -> Unit,
    onPickSubtitle: () -> Unit,
    onLookupAt: (String, Long?) -> Unit,
    onTranslate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val subUri = remember(uiTick) { svc.subtitleUri }
    val posMs = remember(uiTick) { svc.player.currentPosition }
    val isPlaying = remember(uiTick) { svc.isActive }

    var cues by remember(subUri) { mutableStateOf<List<Cue>?>(null) }
    var selectMode by remember { mutableStateOf(false) }
    var selectedText by remember { mutableStateOf<String?>(null) }
    var selectedCue by remember { mutableStateOf<Cue?>(null) }

    LaunchedEffect(subUri) {
        if (subUri == null) {
            cues = emptyList()
        } else {
            cues = withContext(Dispatchers.IO) {
                SubtitleParser.load(context, subUri) ?: emptyList()
            }
        }
    }

    val loadedCues = cues.orEmpty()
    val currentCueIndex = remember(loadedCues, posMs) {
        var res = -1
        for (i in loadedCues.indices) {
            if (loadedCues[i].startMs <= posMs) res = i else break
        }
        res
    }

    val listState = rememberLazyListState()
    LaunchedEffect(currentCueIndex) {
        if (currentCueIndex >= 0 && !selectMode && selectedText == null) {
            runCatching { listState.animateScrollToItem((currentCueIndex - 1).coerceAtLeast(0)) }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = LsCard,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(18.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("자막", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = LsTextPrimary)
                    Spacer(Modifier.width(10.dp))
                    FilterChip(
                        selected = selectMode,
                        onClick = {
                            selectMode = !selectMode
                            selectedText = null
                            selectedCue = null
                        },
                        label = {
                            Text(
                                text = if (selectMode) "글자·단어 선택 모드 켜짐" else "글자·단어 선택 모드",
                                fontSize = 12.sp
                            )
                        }
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        svc.togglePlay()
                        onTick()
                    }) {
                        Icon(
                            painter = painterResource(if (isPlaying) R.drawable.ls_ic_pause else R.drawable.ls_ic_play),
                            contentDescription = "재생/일시정지",
                            tint = LsTealDark
                        )
                    }
                    TextButton(onClick = onPickSubtitle) { Text("파일 선택", color = LsTeal) }
                    if (subUri != null) {
                        TextButton(onClick = {
                            svc.setSubtitle(null)
                            onTick()
                        }) { Text("제거", color = LsCoral) }
                    }
                    TextButton(onClick = onDismiss) { Text("닫기") }
                }

                Text(
                    text = if (selectMode) {
                        "단어를 누르면 ‘이 부분 재생 · 단어장·사전 · 번역 · 복사’ 메뉴가 나타납니다."
                    } else {
                        "문장을 누르면 그 위치로 이동 · 길게 누르거나 ‘반복’을 누르면 구간 반복 · 단어를 고르려면 위 ‘글자·단어 선택 모드’를 켜세요."
                    },
                    color = LsTextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                // Word/phrase action bar when a word/cue is selected
                val pickedWord = selectedText
                val pickedCue = selectedCue
                if (!pickedWord.isNullOrBlank() && pickedCue != null) {
                    Surface(
                        color = LsMint,
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, LsTeal),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("선택한 단어·문장: \"$pickedWord\"", fontWeight = FontWeight.Bold, color = LsTealDark, fontSize = 14.sp)
                                Text("위치: ${formatTime(pickedCue.startMs)}", color = LsTextSecondary, fontSize = 11.sp)
                            }
                            TextButton(onClick = {
                                svc.setA(pickedCue.startMs)
                                svc.setB(pickedCue.endMs.coerceAtLeast(pickedCue.startMs + 800L))
                                svc.resume()
                                onTick()
                            }) { Text("▶ 이 부분 재생", color = LsTealDark, fontWeight = FontWeight.Bold) }
                            TextButton(onClick = {
                                // Modification (3): Pass both the word and the cue's startMs so returning resumes here
                                onLookupAt(pickedWord, pickedCue.startMs)
                            }) { Text("📖 단어장·사전", color = LsTealDark, fontWeight = FontWeight.Bold) }
                            TextButton(onClick = {
                                onTranslate(pickedWord)
                            }) { Text("🌐 번역", color = LsTealDark, fontWeight = FontWeight.Bold) }
                            TextButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("subtitle", pickedWord))
                                Toast.makeText(context, "복사했어요.", Toast.LENGTH_SHORT).show()
                            }) { Text("복사", color = LsTextSecondary) }
                        }
                    }
                }

                Divider()

                if (subUri == null || loadedCues.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (subUri == null) "이 곡에 연결된 자막이 없어요.\n.srt 자막 파일을 고르거나 ‘자막 만들기’를 눌러 주세요."
                                else "자막을 불러오지 못했어요.",
                                color = LsTextSecondary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = onPickSubtitle,
                                colors = ButtonDefaults.buttonColors(containerColor = LsTeal)
                            ) {
                                Text("자막 파일 선택 (.srt / .vtt / .lrc)")
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(loadedCues) { index, cue ->
                            val isCurrent = index == currentCueIndex
                            Surface(
                                color = if (isCurrent) LsMint else Color.Transparent,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            svc.player.seekTo(cue.startMs)
                                            svc.resume()
                                            onTick()
                                        },
                                        onLongClick = {
                                            svc.setA(cue.startMs)
                                            svc.setB(cue.endMs.coerceAtLeast(cue.startMs + 500L))
                                            svc.resume()
                                            Toast.makeText(context, "이 문장을 구간 반복으로 지정했습니다.", Toast.LENGTH_SHORT).show()
                                            onTick()
                                        }
                                    )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = formatTime(cue.startMs),
                                        color = if (isCurrent) LsTealDark else LsTextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.width(52.dp).padding(top = 2.dp)
                                    )
                                    if (selectMode) {
                                        FlowRow(
                                            modifier = Modifier.weight(1f),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            val tokens = cue.text.split(Regex("\\s+")).filter { it.isNotBlank() }
                                            tokens.forEach { rawToken ->
                                                val cleanWord = rawToken.trim { !it.isLetterOrDigit() && it != '\'' && it != '-' }
                                                    .ifBlank { rawToken }
                                                val isWordSelected = selectedText == cleanWord && selectedCue == cue
                                                Surface(
                                                    color = if (isWordSelected) Color(0xFFFFE082) else LsBg,
                                                    shape = RoundedCornerShape(6.dp),
                                                    modifier = Modifier.clickable {
                                                        selectedText = cleanWord
                                                        selectedCue = cue
                                                    }
                                                ) {
                                                    Text(
                                                        text = rawToken,
                                                        color = LsTextPrimary,
                                                        fontSize = 15.sp,
                                                        fontWeight = if (isCurrent || isWordSelected) FontWeight.Bold else FontWeight.Normal,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        Text(
                                            text = cue.text,
                                            color = if (isCurrent) LsTealDark else LsTextPrimary,
                                            fontSize = 15.sp,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            svc.setA(cue.startMs)
                                            svc.setB(cue.endMs.coerceAtLeast(cue.startMs + 500L))
                                            svc.resume()
                                            onTick()
                                        },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Text("A-B", fontSize = 11.sp, color = LsTeal)
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

@Composable
private fun SubtitleListDialog(
    context: Context,
    svc: PlaybackService,
    onChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    var items by remember { mutableStateOf(SubtitleLinks.list(context)) }
    val currentSub = svc.subtitleUri
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("자막 목록", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "‘자막 만들기’로 만든 자막 파일이에요. 줄을 누르면 지금 재생 중인 곡의 자막으로 연결돼요.",
                    color = LsTextSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                if (items.isEmpty()) {
                    Text(
                        text = "아직 만든 자막이 없어요.\n재생목록의 곡 메뉴나 플레이어의 ‘자막 만들기’로 만들 수 있어요.",
                        color = LsTextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 20.dp)
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items.forEach { item ->
                            val isLinked = item.link == currentSub
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isLinked) LsMint else LsBg)
                                    .clickable {
                                        if (svc.currentTrackUri == null) {
                                            Toast.makeText(context, "먼저 오디오 파일을 추가해 주세요.", Toast.LENGTH_SHORT).show()
                                        } else {
                                            svc.setSubtitle(item.link)
                                            onChanged()
                                            Toast.makeText(context, "지금 곡의 자막으로 연결했어요.", Toast.LENGTH_SHORT).show()
                                            onDismiss()
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = (if (isLinked) "✔ " else "") + item.audioName,
                                        color = if (isLinked) LsTealDark else LsTextPrimary,
                                        fontWeight = if (isLinked) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "${dateFormat.format(Date(item.savedAt))} · ${item.sizeBytes / 1024 + 1}KB" +
                                            if (isLinked) " · 지금 곡에 연결됨" else "",
                                        color = LsTextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                                TextButton(onClick = {
                                    if (svc.subtitleUri == item.link) svc.setSubtitle(null)
                                    SubtitleLinks.remove(context, item.link)
                                    items = SubtitleLinks.list(context)
                                    onChanged()
                                }) {
                                    Text("삭제", color = LsCoral, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    )
}

@Composable
private fun SrtMakerDialog(
    context: Context,
    svc: PlaybackService,
    onDismiss: () -> Unit
) {
    var language by remember { mutableStateOf("en") }
    val currentEntry = svc.player.currentEntry
    val activeEntries = svc.groupEntries(svc.activeGroup)
    val allEntries = svc.groups.flatMap { it.entries }.distinctBy { it.uri }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("자막 만들기 (.srt)", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "오디오 음성을 인식해 문장 단위로 타임스탬프가 하나로 묶인 .srt 자막 파일을 만듭니다.",
                    color = LsTextSecondary,
                    fontSize = 13.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("en" to "영어", "ko" to "한국어", "auto" to "자동").forEach { (code, label) ->
                        FilterChip(
                            selected = language == code,
                            onClick = { language = code },
                            label = { Text(label) }
                        )
                    }
                }
                if (currentEntry != null) {
                    Button(
                        onClick = {
                            SrtMaker.start(context, listOf(currentEntry), language)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = LsTeal),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("지금 재생 중인 곡 자막 만들기")
                    }
                }
                if (activeEntries.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            SrtMaker.start(context, activeEntries, language)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("현재 폴더 전체 (${activeEntries.size}곡) 자막 만들기", color = LsTealDark)
                    }
                }
                if (allEntries.size > activeEntries.size) {
                    OutlinedButton(
                        onClick = {
                            SrtMaker.start(context, allEntries, language)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("모든 폴더 전체 (${allEntries.size}곡) 자막 만들기", color = LsTealDark)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

private fun resolveDisplayName(context: Context, uri: Uri): String? = runCatching {
    if (uri.scheme == "file") {
        File(uri.path ?: return@runCatching null).name
    } else {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast('\\')
    }
}.getOrNull()

