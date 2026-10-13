package com.hdlee73.englishstudy.listening

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hdlee73.englishstudy.R

private const val ROW_OWN = -1
private const val ROW_NEW = -2
private const val KEY_LIST_VIEW = "folder_list_view"

/** What the playlist asks the user in a dialog. */
private sealed class PlaylistDialog {
    class Prompt(val title: String, val initial: String, val positive: String, val onOk: (String) -> Unit) : PlaylistDialog()
    class Confirm(val message: String, val positive: String, val onOk: () -> Unit) : PlaylistDialog()
    class Choose(val title: String, val labels: List<String>, val onPick: (Int) -> Unit) : PlaylistDialog()
}

/**
 * The playlist: folders as cards (or rows); tapping a folder lists its tracks, the back arrow returns.
 * Tracks are reordered by dragging the handle; a track or a whole folder can be set to repeat.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BoxScope.PlaylistSheet(env: LsEnv, live: Int, onDismiss: () -> Unit) {
    val svc = env.svc
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(TrackStore.PREFS, android.content.Context.MODE_PRIVATE) }
    var listView by remember { mutableStateOf(prefs.getBoolean(KEY_LIST_VIEW, false)) }
    var viewGroup by remember { mutableStateOf(svc.activeGroup) }
    var inFolder by remember { mutableStateOf(false) }
    var levelParent by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<PlaylistDialog?>(null) }
    var menuGroup by remember { mutableStateOf<Int?>(null) }
    var menuTrack by remember { mutableStateOf<Int?>(null) }
    var headerMenu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    val groups = svc.groups.toList()
    val _unused = live

    fun parentOf(g: TrackStore.Group): String? = g.parentId?.takeIf { id -> groups.any { it.id == id } }
    fun childrenOf(parent: String?): List<Int> = groups.indices.filter { parentOf(groups[it]) == parent }
    fun depthOf(g: TrackStore.Group): Int {
        var depth = 0
        var p = parentOf(g)
        while (p != null && depth < 20) {
            depth++
            p = groups.firstOrNull { it.id == p }?.let { parentOf(it) }
        }
        return depth
    }

    viewGroup = viewGroup.coerceIn(0, groups.lastIndex)
    if (levelParent != null && groups.none { it.id == levelParent }) levelParent = null
    val level = groups.firstOrNull { it.id == levelParent }

    fun goBack() {
        if (inFolder) inFolder = false
        else levelParent = level?.let { parentOf(it) }
    }

    fun openFolder(index: Int) {
        val group = groups.getOrNull(index) ?: return
        viewGroup = index
        if (childrenOf(group.id).isNotEmpty()) {
            levelParent = group.id
            inFolder = false
        } else {
            levelParent = parentOf(group)
            inFolder = true
        }
    }

    fun promptNewFolder(parentId: String?) {
        dialog = PlaylistDialog.Prompt(if (parentId != null) "하위 폴더" else "새 폴더", "폴더 ${groups.size + 1}", "만들기") { name ->
            val index = svc.createGroup(name, parentId)
            levelParent = parentId
            inFolder = true
            viewGroup = index
        }
    }

    BackHandler(enabled = inFolder || levelParent != null) { goBack() }

    BottomSheet(0.82f, onDismiss) {
        Column(Modifier.fillMaxSize().padding(top = 10.dp)) {
            SheetHandle()
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (inFolder || level != null) {
                    HeaderIcon(R.drawable.ls_ic_back, "폴더 목록으로", Modifier.padding(end = 8.dp)) { goBack() }
                }
                Text(
                    if (inFolder) groups[viewGroup].name else level?.name ?: "재생목록",
                    color = LsText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                if (!inFolder) {
                    HeaderIcon(
                        if (listView) R.drawable.ls_ic_grid else R.drawable.ls_ic_list,
                        if (listView) "카드로 보기" else "목록으로 보기",
                        Modifier.padding(end = 6.dp)
                    ) {
                        listView = !listView
                        prefs.edit().putBoolean(KEY_LIST_VIEW, listView).apply()
                    }
                }
                Box {
                    HeaderIcon(R.drawable.ls_ic_add, if (inFolder) "이 폴더에 파일 추가" else if (level != null) "하위 폴더 만들기" else "새 폴더") {
                        if (inFolder) env.addFilesTo(viewGroup) else addMenu = true
                    }
                    IconMenu(
                        addMenu, { addMenu = false },
                        listOf(
                            MenuItem(1, if (levelParent != null) "하위 폴더 만들기" else "새 폴더 만들기", R.drawable.ls_ic_folder_add),
                            MenuItem(2, "기기 폴더 가져오기 (하위 폴더 포함)", R.drawable.ls_ic_folder_open)
                        )
                    ) { id -> if (id == 1) promptNewFolder(levelParent) else env.importDeviceFolder() }
                }
                if (inFolder) {
                    Box(Modifier.padding(start = 6.dp)) {
                        HeaderIcon(R.drawable.ls_ic_more, "그룹 메뉴") { headerMenu = true }
                        GroupMenu(
                            env, expanded = headerMenu, groupIndex = viewGroup, onDismiss = { headerMenu = false },
                            onDialog = { dialog = it },
                            onNewSub = { promptNewFolder(it) },
                            depthOf = ::depthOf,
                            onMoved = { parent -> levelParent = parent; inFolder = false },
                            onDeleted = { viewGroup = 0; inFolder = false },
                            onMovedIndex = { viewGroup = it }
                        )
                    }
                }
            }
            val entries = svc.groupEntries(viewGroup)
            val isActiveGroup = viewGroup == svc.activeGroup
            val subs = childrenOf(levelParent)
            Text(
                if (inFolder) "${entries.size}곡" + if (isActiveGroup) "  ·  재생 중인 폴더" else "  ·  곡을 누르면 이 폴더로 전환돼요"
                else if (level != null) "하위 폴더 ${subs.size}개  ·  이 폴더의 곡은 맨 위 줄에서 볼 수 있어요"
                else "폴더 ${subs.size}개  ·  폴더를 눌러 곡을 보세요 (길게 누르면 메뉴)",
                color = LsText2, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 6.dp, end = 20.dp, bottom = 4.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 4.dp)
                    .heightIn(min = 40.dp).clip(RoundedCornerShape(14.dp)).background(LsTeal)
                    .combinedClickable(onClick = {
                        // The 6min folder is created by the download itself when it does not exist yet.
                        val have = svc.groups.filter { it.name.equals(SixMinuteEnglish.GROUP_NAME, ignoreCase = true) }
                            .flatMap { it.entries }.mapNotNull { SixMinuteEnglish.episodeKey(it.name) }
                        SixMinuteEnglish.fetchLatest(context, have)
                        env.toast("최신 에피소드를 확인하고 있어요…")
                    }),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LsIcon(R.drawable.ls_ic_add, Color.White, 18.dp)
                Spacer(Modifier.width(8.dp))
                Text("6 Minute English 새 에피소드 받기", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            if (inFolder && entries.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 4.dp)
                        .heightIn(min = 40.dp).clip(RoundedCornerShape(14.dp))
                        .border(1.dp, LsBorder, RoundedCornerShape(14.dp))
                        .combinedClickable(onClick = { dialog = null; env.openSrt(env.svc.groupEntries(viewGroup)) }),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LsIcon(R.drawable.ls_ic_subtitles, LsTealDark, 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("이 폴더 곡 자막(SRT) 만들기", color = LsTealDark, fontSize = 14.sp)
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!inFolder) {
                    val rows = (if (level != null) listOf(ROW_OWN) else emptyList()) + subs + ROW_NEW
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val columns = if (listView) 1 else if (maxWidth >= 600.dp) 4 else 2
                        LazyVerticalGrid(
                            GridCells.Fixed(columns),
                            contentPadding = PaddingValues(start = 11.dp, end = 11.dp, bottom = 16.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(rows) { row ->
                                FolderItem(
                                    env, row, levelParent, listView, ::childrenOf,
                                    menuOpen = menuGroup == row,
                                    onMenuDismiss = { menuGroup = null },
                                    onOpen = { r ->
                                        if (r == ROW_NEW) promptNewFolder(levelParent)
                                        else if (r == ROW_OWN) {
                                            viewGroup = groups.indexOfFirst { it.id == levelParent }
                                            inFolder = true
                                        } else openFolder(r)
                                    },
                                    onLong = { r -> if (r >= 0) { viewGroup = r; menuGroup = r } },
                                    menu = { r ->
                                        GroupMenu(
                                            env, expanded = menuGroup == r, groupIndex = r, onDismiss = { menuGroup = null },
                                            onDialog = { dialog = it },
                                            onNewSub = { promptNewFolder(it) },
                                            depthOf = ::depthOf,
                                            onMoved = { parent -> levelParent = parent; inFolder = false },
                                            onDeleted = { viewGroup = 0; inFolder = false },
                                            onMovedIndex = { viewGroup = it }
                                        )
                                    }
                                )
                            }
                        }
                    }
                } else if (entries.isEmpty()) {
                    Text(
                        "이 폴더는 비어 있어요.\n오른쪽 위 ＋ 버튼으로 파일을 추가하세요.",
                        color = LsText2, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 20.sp,
                        modifier = Modifier.align(Alignment.Center).padding(32.dp)
                    )
                } else {
                    TrackList(
                        env, entries, viewGroup, isActiveGroup,
                        menuTrack = menuTrack,
                        onMenuTrack = { menuTrack = it },
                        onDialog = { dialog = it },
                        onPlayed = onDismiss
                    )
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        is PlaylistDialog.Prompt -> PromptDialog(d.title, d.initial, d.positive, onDismiss = { dialog = null }) { value ->
            dialog = null
            d.onOk(value)
        }
        is PlaylistDialog.Confirm -> ConfirmDialog(d.message, d.positive, onDismiss = { dialog = null }, onOk = d.onOk)
        is PlaylistDialog.Choose -> ChoiceDialog(d.title, d.labels, onDismiss = { dialog = null }, onPick = d.onPick)
    }
}

/** A folder as a card (grid) or a row (list); also the "new folder" and "this folder's tracks" entries. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderItem(
    env: LsEnv,
    row: Int,
    levelParent: String?,
    listView: Boolean,
    childrenOf: (String?) -> List<Int>,
    menuOpen: Boolean,
    onMenuDismiss: () -> Unit,
    onOpen: (Int) -> Unit,
    onLong: (Int) -> Unit,
    menu: @Composable (Int) -> Unit
) {
    val svc = env.svc
    val icon: Int
    val badge: String
    val name: String
    val count: String
    val active: Boolean
    when (row) {
        ROW_NEW -> {
            icon = R.drawable.ls_ic_add; badge = ""
            name = if (levelParent != null) "하위 폴더" else "새 폴더"
            count = if (listView) "" else "폴더 만들기"
            active = false
        }
        ROW_OWN -> {
            val own = svc.groups.indexOfFirst { it.id == levelParent }
            val group = svc.groups.getOrNull(own) ?: return
            icon = R.drawable.ls_ic_folder_open
            active = own == svc.activeGroup
            badge = if (active) "▶ 재생 중" else ""
            name = "이 폴더의 곡 보기"
            count = "${group.entries.size}곡"
        }
        else -> {
            val group = svc.groups.getOrNull(row) ?: return
            val subs = childrenOf(group.id).size
            icon = R.drawable.ls_ic_folder
            active = row == svc.activeGroup
            badge = if (active) "▶ 재생 중" else ""
            name = group.name
            count = "${group.entries.size}곡" + if (subs > 0) " · 하위 ${subs}" else ""
        }
    }
    val shape = RoundedCornerShape(18.dp)
    val bg = if (active) LsMint else Color.White
    val border = if (active) 1.5.dp to LsTeal else 1.dp to LsBorder
    val click = Modifier.combinedClickable(onClick = { onOpen(row) }, onLongClick = { onLong(row) })
    if (listView) {
        Box(Modifier.padding(start = 5.dp, top = 3.dp, end = 5.dp, bottom = 3.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(bg).border(border.first, border.second, shape)
                    .then(click).padding(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LsIcon(icon, LsTeal, 28.dp)
                Text(name, color = LsText, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 14.dp))
                Text(badge, color = LsTealDark, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                Text(count, color = LsText2, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp))
            }
            if (menuOpen) menu(row)
        }
    } else {
        Box(Modifier.padding(5.dp)) {
            Column(
                Modifier.fillMaxWidth().clip(shape).background(bg).border(border.first, border.second, shape)
                    .then(click).padding(start = 14.dp, top = 12.dp, end = 10.dp, bottom = 12.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    LsIcon(icon, LsTeal, 34.dp)
                    Spacer(Modifier.weight(1f))
                    Text(badge, color = LsTealDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Text(name, color = LsText, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Text(count, color = LsText2, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
            }
            if (menuOpen) menu(row)
        }
    }
}

/** The folder menu (long press on a folder, or the ⋮ button inside a folder). Place inside its anchor. */
@Composable
private fun GroupMenu(
    env: LsEnv,
    expanded: Boolean,
    groupIndex: Int,
    onDismiss: () -> Unit,
    onDialog: (PlaylistDialog) -> Unit,
    onNewSub: (String) -> Unit,
    depthOf: (TrackStore.Group) -> Int,
    onMoved: (String?) -> Unit,
    onDeleted: () -> Unit,
    onMovedIndex: (Int) -> Unit
) {
    val svc = env.svc
    val group = svc.groups.getOrNull(groupIndex) ?: return
    val items = listOf(
        MenuItem(1, "이 폴더 재생", R.drawable.ls_ic_play),
        MenuItem(2, "이 폴더 반복 재생", R.drawable.ls_ic_repeat),
        MenuItem(6, "하위 폴더 만들기", R.drawable.ls_ic_folder_add, dividerBefore = true),
        MenuItem(7, "폴더 이동", R.drawable.ls_ic_folder_move),
        MenuItem(8, "앞으로 (위로) 옮기기", R.drawable.ls_ic_arrow_up),
        MenuItem(9, "뒤로 (아래로) 옮기기", R.drawable.ls_ic_arrow_down),
        MenuItem(10, "곡을 이름순으로 정렬", R.drawable.ls_ic_sort),
        MenuItem(3, "이름 변경", R.drawable.ls_ic_edit),
        MenuItem(11, "폴더 곡 모두 자막(SRT) 만들기", R.drawable.ls_ic_subtitles),
        MenuItem(4, "폴더 비우기", R.drawable.ls_ic_clear_list, dividerBefore = true),
        MenuItem(5, if (svc.groups.size > 1) "폴더 삭제" else "폴더 삭제 (마지막 폴더는 비우기만)", R.drawable.ls_ic_delete, destructive = true)
    )
    IconMenu(expanded, onDismiss, items) { id ->
        when (id) {
            1 -> if (group.entries.isEmpty()) env.toast("비어 있는 폴더입니다.") else svc.switchGroup(groupIndex, play = true)
            2 -> if (group.entries.isEmpty()) env.toast("비어 있는 폴더입니다.") else {
                svc.switchGroup(groupIndex, play = true)
                svc.updateRepeatMode(2)
                env.toast("‘${group.name}’ 폴더를 반복 재생합니다.")
            }
            3 -> onDialog(PlaylistDialog.Prompt("폴더 이름", group.name, "확인") { name -> svc.renameGroup(groupIndex, name) })
            4 -> onDialog(PlaylistDialog.Confirm("‘${group.name}’ 폴더의 곡을 모두 뺄까요?\n(곡별 기록은 유지됩니다)", "비우기") { svc.clearGroup(groupIndex) })
            5 -> {
                val subs = svc.descendants(groupIndex)
                val extra = if (subs.isEmpty()) "" else "\n하위 폴더 ${subs.size}개도 함께 삭제됩니다."
                onDialog(PlaylistDialog.Confirm("‘${group.name}’ 폴더를 삭제할까요?$extra\n(곡별 기록은 유지됩니다)", "삭제") {
                    (subs + groupIndex).sortedDescending().forEach { svc.deleteGroup(it) }
                    onDeleted()
                })
            }
            6 -> onNewSub(group.id)
            7 -> {
                val blocked = svc.descendants(groupIndex).toSet() + groupIndex
                val targets = svc.groups.indices.filter { it !in blocked }
                val labels = listOf("📁  최상위") + targets.map { "    ".repeat(depthOf(svc.groups[it])) + "📁  " + svc.groups[it].name }
                onDialog(PlaylistDialog.Choose("‘${group.name}’ 폴더를 어디로 옮길까요?", labels) { which ->
                    val parent = if (which == 0) null else svc.groups[targets[which - 1]].id
                    val current = group.parentId?.takeIf { id -> svc.groups.any { it.id == id } }
                    if (parent == current) env.toast("이미 그 위치에 있습니다.")
                    else if (svc.moveGroup(groupIndex, parent)) {
                        env.toast("폴더를 옮겼습니다.")
                        onMoved(parent)
                    }
                })
            }
            8, 9 -> {
                val moved = svc.reorderGroup(groupIndex, if (id == 8) -1 else 1)
                if (!moved) env.toast(if (id == 8) "이미 맨 앞입니다." else "이미 맨 뒤입니다.")
                else onMovedIndex(svc.groups.indexOf(group))
            }
            11 -> if (group.entries.isEmpty()) env.toast("비어 있는 폴더입니다.") else env.openSrt(group.entries.toList())
            10 -> {
                svc.sortGroupByName(groupIndex)
                env.toast("곡을 이름순으로 정렬했습니다.")
            }
        }
    }
}

/** The tracks of one folder: number / ▶ / ✓, name, subtitle button, ⋮ menu and the drag handle. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackList(
    env: LsEnv,
    entries: List<TrackStore.Entry>,
    group: Int,
    isActiveGroup: Boolean,
    menuTrack: Int?,
    onMenuTrack: (Int?) -> Unit,
    onDialog: (PlaylistDialog) -> Unit,
    onPlayed: () -> Unit
) {
    val svc = env.svc
    val state = rememberLazyListState()
    val currentPosition = if (isActiveGroup) svc.player.currentMediaItemIndex else -1
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    LazyColumn(state = state, contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
        itemsIndexed(entries, key = { _, e -> e.uri }) { position, entry ->
            val isCurrent = position == currentPosition
            val hasSubtitle = remember(entry.uri, SubtitleLinks.version) { SubtitleLinks.find(env.context.applicationContext, entry.uri, entry.name) != null }
            val done = svc.isDone(entry.uri)
            val stateText = when { isCurrent -> "▶"; done -> "✓"; else -> (position + 1).toString() }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (draggingKey == entry.uri) LsBorder else if (isCurrent) LsMint else Color.Transparent)
                    .combinedClickable(onClick = { svc.playIn(group, position); onPlayed() })
                    .padding(start = 6.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stateText,
                    color = if (isCurrent || done) LsTeal else LsText2,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.width(34.dp)
                )
                Text(
                    entry.name,
                    color = if (isCurrent) LsTealDark else LsText,
                    fontSize = 14.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp)
                )
                PlainIconButton(R.drawable.ls_ic_subtitles, if (hasSubtitle) "자막 있음" else "자막(SRT) 만들기", if (hasSubtitle) LsTeal else LsText2) {
                    env.openSrt(listOf(entry))
                }
                Box {
                    PlainIconButton(R.drawable.ls_ic_more, "더 보기") { onMenuTrack(position) }
                    TrackMenu(env, group, position, entry, menuTrack == position, { onMenuTrack(null) }, onDialog)
                }
                Box(
                    Modifier.size(40.dp).pointerInput(entry.uri) {
                        detectDragGestures(
                            onDragStart = { draggingKey = entry.uri; dragOffset = 0f },
                            onDragEnd = { draggingKey = null },
                            onDragCancel = { draggingKey = null },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                // Swap with the neighbour once the finger passed half of its height.
                                val key = draggingKey ?: return@detectDragGestures
                                val index = svc.groupEntries(group).indexOfFirst { it.uri == key }
                                val info = state.layoutInfo.visibleItemsInfo
                                val own = info.firstOrNull { it.key == key } ?: return@detectDragGestures
                                if (dragOffset > own.size / 2f && index < svc.groupEntries(group).lastIndex) {
                                    svc.moveInGroup(group, index, index + 1)
                                    dragOffset -= own.size
                                } else if (dragOffset < -own.size / 2f && index > 0) {
                                    svc.moveInGroup(group, index, index - 1)
                                    dragOffset += own.size
                                }
                            }
                        )
                    },
                    contentAlignment = Alignment.Center
                ) { LsIcon(R.drawable.ls_ic_drag, LsText2, 24.dp) }
            }
        }
    }
}

@Composable
private fun TrackMenu(
    env: LsEnv,
    group: Int,
    position: Int,
    entry: TrackStore.Entry,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onDialog: (PlaylistDialog) -> Unit
) {
    val svc = env.svc
    val items = mutableListOf(MenuItem(1, "이 파일 반복 재생", R.drawable.ls_ic_repeat_one))
    if (svc.groups.size > 1) items += MenuItem(2, "다른 폴더로 이동", R.drawable.ls_ic_folder_move)
    items += MenuItem(5, "자막(SRT) 만들기", R.drawable.ls_ic_subtitles)
    items += MenuItem(3, "이어듣기 기록 초기화", R.drawable.ls_ic_refresh)
    items += MenuItem(4, "목록에서 삭제", R.drawable.ls_ic_delete, destructive = true, dividerBefore = true)
    IconMenu(expanded, onDismiss, items) { id ->
        when (id) {
            1 -> {
                svc.playIn(group, position)
                svc.updateRepeatMode(1)
                env.toast("‘${entry.name}’ 파일을 반복 재생합니다.")
            }
            2 -> {
                val targets = svc.groups.indices.filter { it != group }
                fun depth(g: TrackStore.Group): Int {
                    var d = 0
                    var p = g.parentId?.takeIf { id -> svc.groups.any { it.id == id } }
                    while (p != null && d < 20) { d++; p = svc.groups.firstOrNull { it.id == p }?.parentId?.takeIf { id -> svc.groups.any { it.id == id } } }
                    return d
                }
                onDialog(PlaylistDialog.Choose("어느 폴더로 옮길까요?", targets.map { "    ".repeat(depth(svc.groups[it])) + svc.groups[it].name }) { which ->
                    if (!svc.moveToGroup(group, position, targets[which])) env.toast("‘${entry.name}’ 파일이 이미 그 폴더에 있습니다.")
                })
            }
            5 -> env.openSrt(listOf(entry))
            3 -> svc.resetProgress(group, position)
            4 -> svc.removeFrom(group, position)
        }
    }
}
