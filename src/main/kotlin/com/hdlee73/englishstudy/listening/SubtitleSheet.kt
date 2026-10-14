package com.hdlee73.englishstudy.listening

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.hdlee73.englishstudy.R
import com.hdlee73.englishstudy.ui.copyToClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val NORMAL_HINT = "문장을 누르면 그 위치로 이동 · 길게 누르면 그 문장을 구간 반복 · 길게 누른 채 끌면 여러 문장을 구간 반복"
private const val SELECT_HINT = "글자 선택 모드: 자막 글자를 누른 채 끌면 다른 문장(시간대)까지 이어서 고를 수 있어요. 손을 떼면 ‘이 부분 재생 · 단어장·사전 · 번역 · 복사’ 메뉴가 나와요. (위의 글자 선택 버튼으로 끌 수 있어요)"

/** The picked text of select mode, which may run over several sentences: (cue index, character offset) of both ends. */
private class TextSel(val loPos: Int, val loOff: Int, val hiPos: Int, val hiOff: Int)

private fun indexAt(cues: List<Cue>, positionMs: Long): Int {
    var low = 0
    var high = cues.lastIndex
    var result = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (cues[mid].startMs <= positionMs) {
            result = mid
            low = mid + 1
        } else high = mid - 1
    }
    return result
}

/**
 * On-demand subtitle window. It shows the subtitle file linked to the playing track, follows the playback position and
 * highlights the current sentence. Tap = jump there, long press = loop it, long press then drag = loop every sentence the
 * pointer passes over. Select mode picks words and phrases like any text.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BoxScope.SubtitleSheet(
    env: LsEnv,
    live: Int,
    reload: Int,
    onLookup: (text: String, positionMs: Long) -> Unit,
    onTranslate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val svc = env.svc
    val density = LocalDensity.current
    val subtitle = svc.subtitleUri
    val key = (svc.currentTrackUri ?: "") + "|" + (subtitle ?: "") + "|" + reload

    var cues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var message by remember { mutableStateOf<String?>("자막을 불러오는 중…") }
    var canPick by remember { mutableStateOf(false) }
    LaunchedEffect(key) {
        cues = emptyList()
        if (subtitle == null) {
            message = "이 곡에 연결된 자막이 없어요.\n.srt 자막 파일을 고르면 재생 위치에 맞춰 문장이 표시돼요."
            canPick = true
            return@LaunchedEffect
        }
        message = "자막을 불러오는 중…"
        canPick = false
        val parsed = withContext(Dispatchers.IO) { runCatching { SubtitleParser.load(env.context, subtitle) }.getOrNull() }
        when {
            parsed == null -> { message = "자막 파일을 읽을 수 없어요.\n다른 파일을 골라 주세요."; canPick = true }
            parsed.isEmpty() -> { message = "자막 형식을 인식하지 못했어요. (.srt, .vtt, .lrc 지원)"; canPick = true }
            else -> { cues = parsed; message = null }
        }
    }

    val listState = rememberLazyListState()
    val current = if (cues.isEmpty()) -1 else indexAt(cues, svc.player.currentPosition)
    var lastUserScroll by remember { mutableLongStateOf(0L) }
    var programmatic by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress }.collect { moving ->
            if (moving && !programmatic) lastUserScroll = System.currentTimeMillis()
        }
    }
    var forceFollow by remember { mutableStateOf(true) }
    LaunchedEffect(current, cues) {
        if (current < 0) return@LaunchedEffect
        val idle = System.currentTimeMillis() - lastUserScroll > 4_000L
        if (idle || forceFollow) {
            programmatic = true
            runCatching { listState.scrollToItem(current, -(listState.layoutInfo.viewportSize.height / 3)) }
            programmatic = false
            forceFollow = false
        }
    }
    LaunchedEffect(cues) { forceFollow = true }

    var selectMode by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf<TextSel?>(null) }
    var menuAt by remember { mutableStateOf<IntOffset?>(null) }
    var moreMenu by remember { mutableStateOf(false) }

    // Range drag (normal mode).
    var dragAnchor by remember { mutableIntStateOf(-1) }
    var dragEnd by remember { mutableIntStateOf(-1) }
    var dragY by remember { mutableStateOf(0f) }
    // Text drag (select mode).
    var textDragging by remember { mutableStateOf(false) }
    var dragX by remember { mutableStateOf(0f) }
    var anchorOffset by remember { mutableIntStateOf(0) }
    var endOffset by remember { mutableIntStateOf(0) }
    val layouts = remember { HashMap<Int, TextLayoutResult>() }
    val textLeftPx = with(density) { (10 + 46).dp.toPx() }
    val textTopPx = with(density) { (2 + 10).dp.toPx() }

    fun itemAt(y: Float): Int {
        val infos = listState.layoutInfo.visibleItemsInfo
        if (infos.isEmpty()) return -1
        val hit = infos.firstOrNull { y >= it.offset && y < it.offset + it.size }
        return (hit ?: if (y < infos.first().offset) infos.first() else infos.last()).index
    }

    fun offsetIn(pos: Int, x: Float, y: Float): Int {
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == pos }
        val layout = layouts[pos]
        val text = cues.getOrNull(pos)?.text ?: return -1
        if (info == null || layout == null) return if (y < 0) 0 else text.length
        return layout.getOffsetForPosition(Offset(x - textLeftPx, y - info.offset - textTopPx)).coerceIn(0, text.length)
    }

    fun publishSel(aPos: Int, aOff: Int, bPos: Int, bOff: Int) {
        val forward = aPos < bPos || (aPos == bPos && aOff <= bOff)
        val loPos = if (forward) aPos else bPos
        val loRaw = if (forward) aOff else bOff
        val hiPos = if (forward) bPos else aPos
        val hiRaw = if (forward) bOff else aOff
        val loText = cues.getOrNull(loPos)?.text ?: return
        val hiText = cues.getOrNull(hiPos)?.text ?: return
        var lo = loRaw.coerceIn(0, loText.length)
        while (lo > 0 && !loText[lo - 1].isWhitespace()) lo--
        var hi = hiRaw.coerceIn(0, hiText.length)
        while (hi < hiText.length && !hiText[hi].isWhitespace()) hi++
        sel = TextSel(loPos, lo, hiPos, hi)
    }

    fun selectedText(s: TextSel): String = (s.loPos..s.hiPos).mapNotNull { pos ->
        val text = cues.getOrNull(pos)?.text ?: return@mapNotNull null
        text.substring(if (pos == s.loPos) s.loOff else 0, if (pos == s.hiPos) s.hiOff.coerceAtMost(text.length) else text.length)
    }.joinToString(" ").replace(Regex("\\s+"), " ").trim()

    fun timeAt(cue: Cue, offset: Int): Long =
        cue.startMs + (cue.endMs - cue.startMs).coerceAtLeast(1L) * offset / cue.text.length.coerceAtLeast(1)

    fun playSelection(s: TextSel) {
        val first = cues.getOrNull(s.loPos) ?: return
        val last = cues.getOrNull(s.hiPos) ?: return
        val from = timeAt(first, s.loOff)
        val to = if (s.hiOff >= last.text.length) last.endMs else timeAt(last, s.hiOff)
        val a = (from - 350L).coerceAtLeast(maxOf(0L, first.startMs - 300L))
        val b = maxOf(to + 450L, a + 800L)
        svc.setA(a)
        if (svc.setB(b)) {
            svc.resume()
            env.toast("고른 부분을 반복 재생합니다. (시간은 문장 안의 위치로 추정)")
        }
    }

    fun finishRange(apply: Boolean) {
        val a = dragAnchor
        val b = dragEnd
        dragAnchor = -1
        dragEnd = -1
        if (a < 0 || !apply) return
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        val first = cues.getOrNull(lo) ?: return
        val last = cues.getOrNull(hi) ?: return
        svc.setA(first.startMs)
        if (svc.setB(last.endMs.coerceAtLeast(first.startMs + 500L))) {
            svc.resume()
            env.toast(if (lo == hi) "이 문장을 구간 반복으로 지정했습니다." else "문장 ${hi - lo + 1}개를 구간 반복으로 지정했습니다.")
        }
    }

    fun finishText(apply: Boolean, at: IntOffset) {
        textDragging = false
        dragAnchor = -1
        dragEnd = -1
        val picked = sel
        if (!apply || picked == null || selectedText(picked).isEmpty()) {
            sel = null
            return
        }
        menuAt = at
    }

    // Edge auto-scroll while dragging.
    LaunchedEffect(dragAnchor >= 0) {
        while (dragAnchor >= 0) {
            val h = listState.layoutInfo.viewportSize.height.toFloat()
            val edge = h * 0.15f
            val dy = when {
                dragY < edge -> -((edge - dragY) / edge * 28f + 4f)
                dragY > h - edge -> ((dragY - (h - edge)) / edge * 28f + 4f)
                else -> 0f
            }
            if (dy != 0f) {
                programmatic = true
                listState.scrollBy(dy)
                programmatic = false
                val pos = itemAt(dragY.coerceIn(0f, h - 1f))
                if (pos >= 0) {
                    if (textDragging) {
                        val off = offsetIn(pos, dragX, dragY)
                        if (off >= 0) { dragEnd = pos; endOffset = off; publishSel(dragAnchor, anchorOffset, dragEnd, endOffset) }
                    } else dragEnd = pos
                }
            }
            delay(16L)
        }
    }

    val curCues = rememberUpdatedState(cues)
    val curSelectMode = rememberUpdatedState(selectMode)

    BottomSheet(0.88f, onDismiss) {
        Column(Modifier.fillMaxSize().padding(top = 10.dp)) {
            SheetHandle()
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, top = 10.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("자막", color = LsText, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                // The select-mode toggle sits in the title bar: full opacity while on, dimmed while off.
                Box(Modifier.padding(end = 6.dp).alpha(if (selectMode) 1f else 0.45f)) {
                    HeaderIcon(R.drawable.ls_ic_edit, "글자 선택 모드") {
                        selectMode = !selectMode
                        sel = null
                        env.toast(if (selectMode) "자막 글자를 길게 눌러 단어나 구절을 고르세요." else "글자 선택 모드를 껐습니다.")
                    }
                }
                HeaderIcon(if (svc.isActive) R.drawable.ls_ic_pause else R.drawable.ls_ic_play, "재생/일시정지") { svc.togglePlay() }
                Box(Modifier.padding(start = 6.dp)) {
                    HeaderIcon(R.drawable.ls_ic_more, "자막 메뉴") { moreMenu = true }
                    val items = buildList {
                        add(MenuItem(1, if (svc.subtitleUri == null) "자막 파일 선택" else "다른 자막 파일 선택", R.drawable.ls_ic_subtitles))
                        if (svc.subtitleUri != null) add(MenuItem(2, "이 곡의 자막 제거", R.drawable.ls_ic_delete, destructive = true))
                    }
                    IconMenu(moreMenu, { moreMenu = false }, items) { id ->
                        when (id) {
                            1 -> env.pickSubtitle()
                            2 -> svc.setSubtitle(null)
                        }
                    }
                }
            }
            if (message == null) {
                Text(
                    if (selectMode) SELECT_HINT else NORMAL_HINT, color = LsText2, fontSize = 11.sp,
                    modifier = Modifier.padding(start = 20.dp, top = 2.dp, end = 20.dp, bottom = 6.dp)
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (message != null) {
                    Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(message ?: "", color = LsText2, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 19.sp)
                        if (canPick) {
                            LsButton(
                                "자막 파일 선택 (.srt)", Modifier.padding(top = 14.dp), background = LsTeal, color = Color.White,
                                height = 44.dp, fontSize = 14
                            ) { env.pickSubtitle() }
                        }
                    }
                } else {
                    val rangeLo = if (dragAnchor >= 0) minOf(dragAnchor, dragEnd) else -1
                    val rangeHi = if (dragAnchor >= 0) maxOf(dragAnchor, dragEnd) else -1
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)
                            .pointerInput(selectMode) {
                                if (selectMode) {
                                    detectDragGestures(
                                        onDragStart = { p ->
                                            val pos = itemAt(p.y)
                                            if (pos >= 0) {
                                                val off = offsetIn(pos, p.x, p.y).coerceAtLeast(0)
                                                textDragging = true
                                                dragAnchor = pos; dragEnd = pos
                                                anchorOffset = off; endOffset = off
                                                dragX = p.x; dragY = p.y
                                                publishSel(pos, off, pos, off)
                                            }
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            dragX = change.position.x
                                            dragY = change.position.y
                                            val pos = itemAt(dragY.coerceIn(0f, listState.layoutInfo.viewportSize.height - 1f))
                                            if (pos >= 0 && dragAnchor >= 0) {
                                                val off = offsetIn(pos, dragX, dragY)
                                                if (off >= 0 && (pos != dragEnd || off != endOffset)) {
                                                    dragEnd = pos; endOffset = off
                                                    publishSel(dragAnchor, anchorOffset, dragEnd, endOffset)
                                                }
                                            }
                                        },
                                        onDragEnd = { finishText(true, IntOffset(dragX.toInt(), dragY.toInt())) },
                                        onDragCancel = { finishText(false, IntOffset.Zero) }
                                    )
                                } else {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { p ->
                                            val pos = itemAt(p.y)
                                            if (pos >= 0) { dragAnchor = pos; dragEnd = pos; dragY = p.y }
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            dragY = change.position.y
                                            val pos = itemAt(dragY.coerceIn(0f, listState.layoutInfo.viewportSize.height - 1f))
                                            if (pos >= 0 && dragAnchor >= 0) dragEnd = pos
                                        },
                                        onDragEnd = { finishRange(true) },
                                        onDragCancel = { finishRange(false) }
                                    )
                                }
                            }
                            .pointerInput(selectMode) {
                                detectTapGestures { p ->
                                    if (curSelectMode.value) sel = null
                                    else {
                                        val pos = itemAt(p.y)
                                        curCues.value.getOrNull(pos)?.let { svc.player.seekTo(it.startMs) }
                                    }
                                }
                            }
                    ) {
                        items(cues.size, key = { it }) { position ->
                            val cue = cues[position]
                            val highlighted = position == current || position in rangeLo..rangeHi
                            val picked = sel
                            val text: AnnotatedString = if (picked != null && selectMode && position in picked.loPos..picked.hiPos) {
                                val from = if (position == picked.loPos) picked.loOff.coerceIn(0, cue.text.length) else 0
                                val to = if (position == picked.hiPos) picked.hiOff.coerceIn(0, cue.text.length) else cue.text.length
                                buildAnnotatedString {
                                    append(cue.text)
                                    if (to > from) addStyle(SpanStyle(background = Color(0x66FFD54F)), from, to)
                                }
                            } else AnnotatedString(cue.text)
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(14.dp))
                                    .then(if (highlighted) Modifier.background(LsMint) else Modifier)
                                    .padding(10.dp)
                            ) {
                                Text(
                                    formatTime(cue.startMs), color = LsText2, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(46.dp).padding(top = 3.dp)
                                )
                                Text(
                                    text, color = if (highlighted) LsTealDark else LsText2, fontSize = 16.sp, lineHeight = 22.sp,
                                    fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
                                    onTextLayout = { layouts[position] = it },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        item { Box(Modifier.padding(bottom = 24.dp)) }
                    }
                    // Menu of the picked words, opened where the pointer was let go.
                    val at = menuAt
                    val picked = sel
                    if (at != null && picked != null) {
                        Box(Modifier.offset { at }) {
                            val text = selectedText(picked)
                            IconMenu(
                                true, { menuAt = null; sel = null },
                                listOf(
                                    MenuItem(1, "이 부분 재생", R.drawable.ls_ic_play),
                                    MenuItem(2, "단어장·사전", R.drawable.ls_ic_edit),
                                    MenuItem(3, "번역", R.drawable.ls_ic_subtitles),
                                    MenuItem(4, "복사", R.drawable.ls_ic_list)
                                )
                            ) { id ->
                                when (id) {
                                    1 -> playSelection(picked)
                                    2 -> {
                                        // Where the picked words are said (estimated from their place in the sentence), so playback can come back to them.
                                        val first = cues.getOrNull(picked.loPos)
                                        val atMs = first?.let { timeAt(it, picked.loOff) } ?: svc.player.currentPosition
                                        val from = if (first != null) (atMs - 600L).coerceAtLeast(maxOf(0L, first.startMs - 300L)) else atMs
                                        onLookup(text, from)
                                    }
                                    3 -> onTranslate(text)
                                    4 -> copyToClipboard(env.context, text)
                                }
                                menuAt = null
                                sel = null
                            }
                        }
                    }
                }
            }
        }
    }
}
