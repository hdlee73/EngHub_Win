package com.hdlee73.englishstudy.listening

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hdlee73.englishstudy.R
import com.hdlee73.englishstudy.ui.Hero
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which sheet or dialog sits over the player. */
internal enum class LsSheet { NONE, PLAYLIST, SUBTITLES, TIMER, SETTINGS }

/** What the sheets need from the screen: the service and the things only the screen can do (pickers, hand-over to other tabs). */
internal class LsEnv(
    val context: Context,
    val svc: PlaybackService,
    val toast: (String) -> Unit,
    val addFilesTo: (Int) -> Unit,
    val importDeviceFolder: () -> Unit,
    /** Opens the folder picker for the folder subtitles are saved in; calls back once one is chosen. */
    val pickSaveFolder: (onPicked: () -> Unit) -> Unit,
    val pickSubtitle: () -> Unit,
    val onLookup: (String) -> Unit,
    val onTranslate: (String) -> Unit,
    val openSrt: (List<TrackStore.Entry>) -> Unit
)

/**
 * The Listening tab: the MP3 player. A Compose version of the Android player screen (ls_activity_main.xml) with the same
 * header buttons, now-playing card, A-B range card and the same sheets (playlist, subtitles, timer, settings).
 * [pendingIntent] is an audio file opened from elsewhere; [onIntentConsumed] is called once it is handed over.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListeningScreen(
    pendingIntent: Intent?,
    onIntentConsumed: () -> Unit,
    modifier: Modifier = Modifier,
    onLookup: (String) -> Unit = {},
    onTranslate: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val svc = remember { PlaybackService.instance }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val prefs = remember { context.getSharedPreferences(TrackStore.PREFS, Context.MODE_PRIVATE) }

    // The screen redraws the service's state a few times a second (like Android's 200 ms ui ticker).
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            tick++
            delay(200L)
        }
    }
    val live = tick

    var sheet by remember { mutableStateOf(LsSheet.NONE) }
    var subtitleListOpen by remember { mutableStateOf(false) }
    var srtTarget by remember { mutableStateOf<List<TrackStore.Entry>?>(null) }
    var rangesOpen by remember { mutableStateOf(false) }
    var saveRangeOpen by remember { mutableStateOf(false) }
    var rangeMenu by remember { mutableStateOf<Int?>(null) }
    var renameRange by remember { mutableStateOf<Int?>(null) }
    var abCountMenu by remember { mutableStateOf(false) }
    var fineStepMs by remember { mutableStateOf(prefs.getLong("fine_step", 100L)) }
    var subtitleReload by remember { mutableIntStateOf(0) }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- pickers
    var pendingAddGroup by remember { mutableIntStateOf(-1) }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val picked = uris.map { it to displayName(context, it) }
        val entries = picked.map { (uri, name) -> TrackStore.Entry(uri.toString(), name) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val group = pendingAddGroup.takeIf { it in svc.groups.indices } ?: svc.activeGroup
        pendingAddGroup = -1
        val added = svc.addEntries(entries, group, startIfIdle = group == svc.activeGroup)
        toast(if (added == 0) "이미 그 그룹에 있는 파일입니다." else "${added}곡을 ‘${svc.groups[group].name}’ 그룹에 추가했습니다.")
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        toast("폴더를 읽고 있어요…")
        scope.launch {
            val root = withContext(Dispatchers.IO) { runCatching { FolderImport.scan(context, tree) }.getOrNull() }
            if (root == null || !root.hasAudio) {
                toast("이 폴더에는 오디오 파일이 없습니다.")
            } else {
                val added = svc.applyScan(tree.toString(), root, force = true)
                toast(
                    if (added == 0) "이미 모두 들어 있는 폴더입니다."
                    else "${added}곡을 재생목록에 추가했습니다. 이 폴더에 새 파일이 생기면 자동으로 들어옵니다."
                )
            }
        }
    }
    var onSaveFolderPicked by remember { mutableStateOf<(() -> Unit)?>(null) }
    val saveFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            com.hdlee73.englishstudy.docvoice.core.SaveFolder.set(context, tree)
            onSaveFolderPicked?.invoke()
        }
    }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            svc.setSubtitle(uri.toString())
            subtitleReload++
        }
    }

    val env = remember(svc) {
        LsEnv(
            context = context,
            svc = svc,
            toast = { toast(it) },
            addFilesTo = { group ->
                pendingAddGroup = group
                audioPicker.launch(arrayOf("audio/*"))
            },
            importDeviceFolder = { folderPicker.launch(null) },
            pickSaveFolder = { done ->
                onSaveFolderPicked = done
                saveFolderPicker.launch(null)
            },
            pickSubtitle = { subtitlePicker.launch(arrayOf("*/*")) },
            onLookup = { onLookup(it) },
            onTranslate = { onTranslate(it) },
            openSrt = { srtTarget = it }
        )
    }

    // ---------------------------------------------------------------- start-up work and hand-overs from other tabs
    LaunchedEffect(Unit) {
        svc.importPending()
        // Back from the dictionary / word list after picking words in the subtitles: play on from there, subtitles open again.
        val pos = svc.takeLookupReturn()
        if (pos >= 0L) {
            svc.player.seekTo(pos)
            svc.resume()
            sheet = LsSheet.SUBTITLES
        }
        // Adds audio files that appeared in the device folders behind imported playlist folders.
        val trees = svc.linkedTrees()
        if (trees.isNotEmpty()) {
            val scans = withContext(Dispatchers.IO) {
                trees.mapNotNull { t -> runCatching { t to FolderImport.scan(context, Uri.parse(t)) }.getOrNull() }
            }
            var added = 0
            scans.forEach { (t, root) -> if (root != null) added += svc.applyScan(t, root, force = false) }
            if (added > 0) toast("기기 폴더에서 새 파일 ${added}곡을 재생목록에 추가했습니다.")
        }
    }
    LaunchedEffect(pendingIntent) {
        val intent = pendingIntent ?: return@LaunchedEffect
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data
                if (uri != null) svc.openAndPlay(TrackStore.Entry(uri.toString(), displayName(context, uri)))
                else toast("오디오 파일을 열 수 없습니다.")
            }
            ListeningLink.ACTION_PICK_DRIVE -> env.addFilesTo(svc.activeGroup)
            ListeningLink.ACTION_RESUME_LAST -> if (svc.player.mediaItemCount == 0) env.addFilesTo(svc.activeGroup) else svc.resume()
        }
        onIntentConsumed()
    }
    // New BBC episodes fetched in the background are put into the 6min folder.
    LaunchedEffect(live) { if (SixMinuteEnglish.hasPending(context)) svc.importPending() }

    // Esc closes the sheet that is open (sheets register their own, newer handlers for inner pages).
    BackHandler(enabled = sheet != LsSheet.NONE) { sheet = LsSheet.NONE }

    // ---------------------------------------------------------------- state of the player
    val player = svc.player
    val count = player.mediaItemCount
    val title = svc.currentTitle
    val duration = player.duration.coerceAtLeast(0L)
    val position = player.currentPosition.coerceAtLeast(0L)
    val active = svc.isActive
    val a = svc.abStartMs
    val b = svc.abEndMs
    val ranges = svc.savedRanges
    val remaining = svc.sleepRemainingMs
    val timerBadge = when {
        remaining >= 0 -> "⏱ " + formatTime(remaining)
        svc.sleepAtTrackEnd -> "⏱ 곡 끝까지"
        else -> ""
    }

    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf(0L) }
    val shownPosition = if (dragging) dragPosition else position

    Box(modifier.fillMaxSize().background(LsBg)) {
        Column(Modifier.fillMaxSize()) {
            Hero("🎧", "Listening", "MP3 · A-B 반복 · 자막으로 귀를 열어요", Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val viewport = maxHeight
                var topH by remember { mutableStateOf(0.dp) }
                var bottomH by remember { mutableStateOf(0.dp) }
                // The two Space(weight 1) of the Android layout share what is left of the screen.
                val extra = ((viewport - topH - bottomH - 8.dp) / 2).coerceAtLeast(0.dp)
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 6.dp)
                ) {
                    Column(Modifier.onSizeChanged { topH = with(density) { it.height.toDp() } }) {
                        // ---- header: the buttons that open their own list
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            if (timerBadge.isNotEmpty()) {
                                QuickChip(timerBadge, Modifier.padding(end = 8.dp)) { sheet = LsSheet.TIMER }
                            }
                            HeaderIcon(R.drawable.ls_ic_add, "파일 추가") { env.addFilesTo(svc.activeGroup) }
                            Spacer(Modifier.width(6.dp))
                            HeaderIcon(R.drawable.ls_ic_list, "재생목록") { sheet = LsSheet.PLAYLIST; }
                            Spacer(Modifier.width(6.dp))
                            HeaderIcon(R.drawable.ls_ic_subtitles, "자막") {
                                if (count == 0) toast("먼저 오디오 파일을 추가해 주세요.") else sheet = LsSheet.SUBTITLES
                            }
                            Spacer(Modifier.width(6.dp))
                            HeaderIcon(R.drawable.ls_ic_timer, "취침 타이머") { sheet = LsSheet.TIMER }
                            Spacer(Modifier.width(6.dp))
                            HeaderIcon(R.drawable.ls_ic_settings, "설정") { sheet = LsSheet.SETTINGS }
                        }
                        Spacer(Modifier.height(6.dp))

                        // ---- now playing
                        LsCard(padding = PaddingValues(start = 18.dp, top = 14.dp, end = 18.dp, bottom = 8.dp)) {
                            Text(
                                title ?: "오디오 파일을 추가하세요",
                                color = LsText,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Clip,
                                modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE)
                            )
                            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                QuickChip("📝 자막 만들기") {
                                    val uri = svc.currentTrackUri
                                    if (uri == null) toast("먼저 오디오 파일을 추가해 주세요.")
                                    else srtTarget = listOf(TrackStore.Entry(uri, svc.currentTitle ?: "audio"))
                                }
                                QuickChip("📄 자막 목록", Modifier.padding(start = 8.dp)) { subtitleListOpen = true }
                            }
                            RepeatSeekBar(
                                position = shownPosition,
                                duration = duration,
                                abStart = a,
                                abEnd = b,
                                modifier = Modifier.padding(top = 6.dp),
                                onDragStart = { dragging = true },
                                onDrag = { dragPosition = it },
                                onSeek = {
                                    dragging = false
                                    player.seekTo(it)
                                }
                            )
                            Row(Modifier.fillMaxWidth()) {
                                Text(formatTime(shownPosition), color = LsText2, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text("-" + formatTime((duration - shownPosition).coerceAtLeast(0L)), color = LsText2, fontSize = 12.sp)
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                ToggleIcon(R.drawable.ls_ic_shuffle, svc.shuffle, "그룹 안에서 랜덤 재생") {
                                    svc.updateShuffle(!svc.shuffle)
                                    toast(if (svc.shuffle) "그룹 안에서 랜덤 재생" else "순서대로 재생")
                                }
                                Spacer(Modifier.weight(1f))
                                TransportIcon(R.drawable.ls_ic_fast_rewind, "이전 곡", 64.dp, 64.dp) {
                                    val index = player.currentMediaItemIndex
                                    when {
                                        count == 0 -> toast("먼저 오디오 파일을 추가해 주세요.")
                                        index > 0 -> player.seekToDefaultPosition(index - 1)
                                        else -> { toast("재생목록의 첫 곡입니다."); player.seekTo(0) }
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                TransportIcon(if (active) R.drawable.ls_ic_pause else R.drawable.ls_ic_play, if (active) "일시정지" else "재생", 84.dp, 68.dp) {
                                    if (count == 0) toast("먼저 오디오 파일을 추가해 주세요.") else svc.togglePlay()
                                }
                                Spacer(Modifier.weight(1f))
                                TransportIcon(R.drawable.ls_ic_fast_forward, "다음 곡", 64.dp, 64.dp) {
                                    val index = player.currentMediaItemIndex
                                    when {
                                        count == 0 -> toast("먼저 오디오 파일을 추가해 주세요.")
                                        svc.shuffle && count > 1 ->
                                            player.seekToDefaultPosition((0 until count).filter { it != index }.random())
                                        index + 1 < count -> player.seekToDefaultPosition(index + 1)
                                        else -> toast("재생목록의 마지막 곡입니다.")
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                ToggleIcon(
                                    when (svc.repeatMode) { 1 -> R.drawable.ls_ic_repeat_one; 2 -> R.drawable.ls_ic_repeat_all; else -> R.drawable.ls_ic_repeat },
                                    svc.repeatMode != 0,
                                    repeatLabel(svc)
                                ) {
                                    svc.updateRepeatMode((svc.repeatMode + 1) % 3)
                                    toast(repeatLabel(svc))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(extra))

                    // ---- A-B range
                    Column(
                        Modifier.onSizeChanged { bottomH = with(density) { it.height.toDp() } }
                    ) {
                        Spacer(Modifier.height(6.dp))
                        LsCard(padding = PaddingValues(12.dp)) {
                            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("구간 반복", color = LsText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    when {
                                        a >= 0 && b >= 0 -> "A " + formatTime(a) + "  ↔  B " + formatTime(b)
                                        a >= 0 -> "A " + formatTime(a) + "  ·  B 미지정"
                                        else -> "꺼짐"
                                    },
                                    color = LsText2,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 10.dp).weight(1f)
                                )
                                Box {
                                    QuickChip(
                                        "횟수 " + (if (svc.abRepeatLimit < 0) "무제한" else "${svc.abRepeatLimit}회") + " ▾",
                                        height = 30.dp
                                    ) { abCountMenu = true }
                                    ChoiceMenu(
                                        expanded = abCountMenu,
                                        onDismiss = { abCountMenu = false },
                                        values = AB_COUNT_VALUES.map { if (it < 0) "무제한" else "${it}회" },
                                        selected = AB_COUNT_VALUES.indexOf(svc.abRepeatLimit)
                                    ) { svc.updateAbRepeatLimit(AB_COUNT_VALUES[it]) }
                                }
                                QuickChip("해제", Modifier.padding(start = 6.dp), height = 30.dp, background = null, color = LsCoral, fontSize = 13) {
                                    svc.clearAb()
                                }
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                                AbBox(
                                    modifier = Modifier.weight(1f),
                                    setText = "A 지정",
                                    value = a,
                                    onSet = { svc.setA(player.currentPosition) },
                                    onMinus = { svc.nudgeA(-fineStepMs) },
                                    onPlus = { svc.nudgeA(fineStepMs) }
                                )
                                AbBox(
                                    modifier = Modifier.padding(start = 10.dp).weight(1f),
                                    setText = "B 지정",
                                    value = b,
                                    onSet = {
                                        if (!svc.setB(player.currentPosition)) {
                                            toast(if (svc.abStartMs < 0) "먼저 A 시작점을 지정해 주세요." else "B 종료점은 A보다 뒤에 있어야 합니다.")
                                        }
                                    },
                                    onMinus = { svc.nudgeB(-fineStepMs) },
                                    onPlus = { svc.nudgeB(fineStepMs) }
                                )
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                LsButton(
                                    "구간 저장", Modifier.weight(1f), background = LsMint, color = LsText, height = 38.dp, fontSize = 11,
                                    enabled = a >= 0 && b > a
                                ) {
                                    if (svc.abStartMs < 0 || svc.abEndMs <= svc.abStartMs) toast("저장할 구간이 없습니다. A·B 지점을 먼저 지정해 주세요.")
                                    else saveRangeOpen = true
                                }
                                LsButton("저장 목록 ${ranges.size}", Modifier.padding(start = 10.dp).weight(1f), background = LsMint, color = LsText, height = 38.dp, fontSize = 11) {
                                    if (ranges.isEmpty()) toast("저장된 구간이 없습니다. A·B를 지정한 뒤 '구간 저장'을 눌러 주세요.")
                                    else {
                                        rangesOpen = true
                                        toast("길게 누르면 이름 변경·삭제")
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(extra))
                }
            }
        }

        // ---------------------------------------------------------------- sheets
        when (sheet) {
            LsSheet.NONE -> Unit
            LsSheet.PLAYLIST -> PlaylistSheet(env, live) { sheet = LsSheet.NONE; }
            LsSheet.SUBTITLES -> SubtitleSheet(
                env = env,
                live = live,
                reload = subtitleReload,
                onLookup = { text, positionMs ->
                    svc.rememberLookup(positionMs)
                    sheet = LsSheet.NONE
                    onLookup(text)
                },
                onTranslate = { sheet = LsSheet.NONE; onTranslate(it) },
                onDismiss = { sheet = LsSheet.NONE }
            )
            LsSheet.TIMER -> TimerSheet(env, prefs) { sheet = LsSheet.NONE }
            LsSheet.SETTINGS -> SettingsSheet(env, prefs, fineStepMs, { fineStepMs = it }) { sheet = LsSheet.NONE }
        }
        if (subtitleListOpen) SubtitleListSheet(env, live, onDismiss = { subtitleListOpen = false }, onChanged = { subtitleReload++ })
    }

    // ---------------------------------------------------------------- dialogs
    srtTarget?.let { entries -> SrtDialog(env, entries) { srtTarget = null } }
    if (saveRangeOpen) {
        PromptDialog(
            "구간 저장  " + formatTimeTenths(svc.abStartMs) + " – " + formatTimeTenths(svc.abEndMs),
            "구간 ${ranges.size + 1}",
            "저장",
            onDismiss = { saveRangeOpen = false }
        ) { name ->
            saveRangeOpen = false
            svc.saveCurrentRange(name)
            toast("구간을 저장했습니다.")
        }
    }
    if (rangesOpen) {
        if (ranges.isEmpty()) rangesOpen = false
        ChoiceDialog(
            "저장한 구간 · ${ranges.size}개",
            ranges.map { it.name + "\n" + formatTimeTenths(it.startMs) + " – " + formatTimeTenths(it.endMs) },
            onDismiss = { rangesOpen = false },
            onPick = { svc.applyRange(it) },
            onLongPick = { rangeMenu = it }
        )
    }
    rangeMenu?.let { index ->
        val range = ranges.getOrNull(index)
        if (range == null) rangeMenu = null
        else ChoiceDialog(range.name, listOf("이름 변경", "삭제"), onDismiss = { rangeMenu = null }, onPick = { which ->
            rangesOpen = false
            if (which == 0) renameRange = index else {
                svc.deleteRange(index)
                rangesOpen = svc.savedRanges.isNotEmpty()
            }
        })
    }
    renameRange?.let { index ->
        val range = ranges.getOrNull(index)
        if (range == null) renameRange = null
        else PromptDialog("이름 변경", range.name, onDismiss = { renameRange = null }) { name ->
            renameRange = null
            svc.renameRange(index, name)
            rangesOpen = true
        }
    }
}

private val AB_COUNT_VALUES = listOf(-1, 2, 3, 5, 10)

private fun repeatLabel(svc: PlaybackService): String = when (svc.repeatMode) {
    1 -> "파일 반복"
    2 -> "그룹 반복 · ${svc.activeGroupName}"
    else -> "반복 안 함"
}

/** The A or B half of the range card: set button, then − / time / +. */
@Composable
private fun AbBox(modifier: Modifier, setText: String, value: Long, onSet: () -> Unit, onMinus: () -> Unit, onPlus: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(LsMint).padding(8.dp)) {
        LsButton(setText, Modifier.fillMaxWidth(), background = LsTeal, color = Color.White, height = 34.dp, bold = true, onClick = onSet)
        Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            LsButton("−", Modifier.width(40.dp), background = Color.White, height = 30.dp, fontSize = 16, enabled = value >= 0, onClick = onMinus)
            Text(
                if (value >= 0) formatTimeTenths(value) else "--:--.-",
                color = LsText,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            LsButton("+", Modifier.width(40.dp), background = Color.White, height = 30.dp, fontSize = 16, enabled = value >= 0, onClick = onPlus)
        }
    }
}

/** ls_ToggleIcon: 48 x 44 dp, mint when on. */
@Composable
private fun ToggleIcon(res: Int, on: Boolean, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp, 44.dp).clip(RoundedCornerShape(16.dp)).background(if (on) LsMint else Color.Transparent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(
            androidx.compose.ui.res.painterResource(res), description, tint = if (on) LsTealDark else LsText2, modifier = Modifier.size(24.dp)
        )
    }
}

/** ls_TransportIcon: big icon button without background. */
@Composable
private fun TransportIcon(res: Int, description: String, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(Modifier.size(width, height).clip(RoundedCornerShape(50)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(
            androidx.compose.ui.res.painterResource(res), description, tint = LsText, modifier = Modifier.size(minOf(width, height) - 24.dp)
        )
    }
}

/** A drop-down list of values; the chosen one has a check mark. */
@Composable
internal fun ChoiceMenu(expanded: Boolean, onDismiss: () -> Unit, values: List<String>, selected: Int, onPick: (Int) -> Unit) {
    IconMenu(
        expanded, onDismiss,
        values.mapIndexed { index, value -> MenuItem(index, value, if (index == selected) R.drawable.ls_ic_check else 0) },
        onPick
    )
}

/**
 * The seek bar with the A-B range painted over its track (RepeatSeekBar): a thin track, a 16 dp thumb,
 * the range as an orange band with white end marks. Dragging shows the new time; the player seeks when the finger lifts.
 */
@Composable
private fun RepeatSeekBar(
    position: Long,
    duration: Long,
    abStart: Long,
    abEnd: Long,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit,
    onDrag: (Long) -> Unit,
    onSeek: (Long) -> Unit
) {
    val thumbRadius = 8.dp
    Box(
        modifier.fillMaxWidth().height(36.dp)
            .pointerInput(duration) {
                val pad = thumbRadius.toPx()
                fun toMs(x: Float): Long = (((x - pad) / (size.width - 2 * pad).coerceAtLeast(1f)).coerceIn(0f, 1f) * duration).toLong()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (duration <= 0) return@awaitEachGesture
                    onDragStart()
                    var ms = toMs(down.position.x)
                    onDrag(ms)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.first()
                        ms = toMs(change.position.x)
                        onDrag(ms)
                        change.consume()
                    } while (change.pressed)
                    onSeek(ms)
                }
            }
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val pad = thumbRadius.toPx()
            val usable = size.width - 2 * pad
            val cy = size.height / 2f
            val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            val trackH = 2.dp.toPx()
            drawRect(LsText2.copy(alpha = 0.4f), Offset(pad, cy - trackH / 2), Size(usable, trackH))
            drawRect(LsTeal, Offset(pad, cy - trackH / 2), Size(usable * fraction, trackH))
            if (duration > 0 && abStart >= 0 && abEnd > abStart) {
                val half = 3.dp.toPx()
                val sx = pad + usable * (abStart.toFloat() / duration).coerceIn(0f, 1f)
                val ex = pad + usable * (abEnd.toFloat() / duration).coerceIn(0f, 1f)
                drawRoundRect(Color(0xBEFFB84D), Offset(sx, cy - half), Size(ex - sx, half * 2), CornerRadius(half, half))
                drawLine(Color.White, Offset(sx, cy - half * 2), Offset(sx, cy + half * 2), 2.dp.toPx())
                drawLine(Color.White, Offset(ex, cy - half * 2), Offset(ex, cy + half * 2), 2.dp.toPx())
            }
            drawCircle(LsTeal, thumbRadius.toPx(), Offset(pad + usable * fraction, cy))
        }
    }
}

/** The name the system shows for an audio file; falls back to the last part of the path. */
internal fun displayName(context: Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "선택한 오디오"
}
