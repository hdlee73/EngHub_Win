package com.hdlee73.englishstudy.listening

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.hdlee73.englishstudy.R
import com.hdlee73.englishstudy.docvoice.core.SaveFolder
import com.hdlee73.englishstudy.docvoice.core.Storage
import com.hdlee73.englishstudy.update.UpdateChecker
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Material3 filter chip as used by the settings sheet (selected = mint with a check). */
@Composable
private fun FilterChipLs(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.height(32.dp).clip(shape)
            .then(if (selected) Modifier.background(LsMint) else Modifier.border(1.dp, LsBorder, shape))
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            LsIcon(R.drawable.ls_ic_check, LsTealDark, 18.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = if (selected) LsTealDark else LsText2, fontSize = 14.sp, maxLines = 1)
    }
}

// ------------------------------------------------------------------ timer

@Composable
internal fun BoxScope.TimerSheet(env: LsEnv, prefs: SharedPreferences, onDismiss: () -> Unit) {
    val svc = env.svc
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            tick++
            kotlinx.coroutines.delay(500L)
        }
    }
    tick.let { }
    val left = svc.sleepRemainingMs
    val (remaining, hint) = when {
        left >= 0 -> formatTime(left) to "뒤에 재생이 멈춥니다"
        svc.sleepAtTrackEnd -> "곡 끝까지" to "현재 곡이 끝나면 재생이 멈춥니다"
        else -> "꺼짐" to "시간을 고르면 그 시간 뒤에 재생이 멈춥니다"
    }
    var custom by remember { mutableIntStateOf(prefs.getInt("sleep_custom_minutes", 30).coerceIn(1, 60)) }

    fun start(minutes: Int) {
        svc.setSleepTimer(minutes)
        env.toast("${minutes}분 뒤에 재생이 멈춥니다.")
        onDismiss()
    }

    @Composable
    fun Tile(text: String, modifier: Modifier, onClick: () -> Unit) {
        LsButton(text, modifier.padding(4.dp), background = LsMint, color = LsTealDark, height = 56.dp, fontSize = 14, bold = true,
            shape = RoundedCornerShape(18.dp), onClick = onClick)
    }

    BottomSheet(null, onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 20.dp)) {
            SheetHandle()
            Text("취침 타이머", color = LsText, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
            Column(
                Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(LsMint),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    remaining, color = LsTealDark, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
                Text(hint, color = LsText2, fontSize = 12.sp, modifier = Modifier.padding(bottom = 14.dp))
            }
            Text(
                "${custom}분", color = LsText, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ custom = (custom - 1).coerceAtLeast(1) }) { Text("−1", color = LsTeal) }
                Slider(
                    value = custom.toFloat(), onValueChange = { custom = it.toInt().coerceIn(1, 60) },
                    valueRange = 1f..60f, steps = 58, modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = LsTeal, activeTrackColor = LsTeal)
                )
                TextButton({ custom = (custom + 1).coerceAtMost(60) }) { Text("+1", color = LsTeal) }
            }
            Tile("이 시간으로 시작 (1~60분)", Modifier.fillMaxWidth()) {
                prefs.edit().putInt("sleep_custom_minutes", custom).apply()
                start(custom)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                listOf(15, 30, 45).forEach { m -> Tile("${m}분", Modifier.weight(1f)) { start(m) } }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf(60, 90).forEach { m -> Tile("${m}분", Modifier.weight(1f)) { start(m) } }
                Tile("곡 끝까지", Modifier.weight(1f)) {
                    svc.sleepAtEndOfTrack()
                    env.toast("현재 곡이 끝나면 재생이 멈춥니다.")
                    onDismiss()
                }
            }
            TextButton(
                {
                    svc.cancelSleepTimer()
                    env.toast("취침 타이머를 껐습니다.")
                    onDismiss()
                },
                Modifier.fillMaxWidth().padding(top = 6.dp)
            ) { Text("타이머 끄기", color = LsCoral, fontSize = 14.sp) }
        }
    }
}

// ------------------------------------------------------------------ settings

private val speedValues = listOf(0.5f, 0.75f, 0.85f, 1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f)

internal fun formatSpeed(speed: Float): String =
    String.format(Locale.US, "%.2f", speed).trimEnd('0').let { if (it.endsWith(".")) it + "0" else it } + "x"

@Composable
internal fun BoxScope.SettingsSheet(
    env: LsEnv,
    prefs: SharedPreferences,
    fineStepMs: Long,
    onFineStep: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val svc = env.svc
    var speed by remember { mutableStateOf(svc.speed) }
    var gap by remember { mutableStateOf(svc.gapMs) }

    @Composable
    fun Heading(text: String, first: Boolean = false) {
        Text(
            text, color = if (first) LsText else LsTeal, fontSize = if (first) 18.sp else 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = if (first) 14.dp else 18.dp, bottom = 2.dp)
        )
    }

    @Composable
    fun ChipRow(title: String, labels: List<String>, selected: String, changed: (String) -> Unit) {
        Text(title, color = LsText2, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEach { label -> FilterChipLs(label, label == selected) { changed(label) } }
        }
    }

    BottomSheet(null, onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 24.dp)) {
            SheetHandle()
            Heading("설정", first = true)
            Heading("재생")
            ChipRow("재생 속도", speedValues.map(::formatSpeed), formatSpeed(speed)) { value ->
                val s = value.removeSuffix("x").toFloat()
                svc.setSpeed(s)
                speed = s
            }
            Heading("학습")
            val gapLabels = listOf("없음", "1초", "2초", "3초", "5초")
            val gapSelected = if (gap <= 0L) "없음" else (gap / 1000).toString() + "초"
            ChipRow("반복 사이 간격  (따라 말하기)", gapLabels, gapSelected) { value ->
                val ms = if (value == "없음") 0L else value.removeSuffix("초").toLong() * 1000
                svc.updateGap(ms)
                gap = ms
            }
            val fineLabels = listOf("0.1초", "0.2초", "0.5초", "1초")
            val fineMs = { label: String -> (label.removeSuffix("초").toDouble() * 1000).toLong() }
            ChipRow("구간 미세 조정 단위", fineLabels, fineLabels.firstOrNull { fineMs(it) == fineStepMs } ?: "0.1초") { value ->
                val ms = fineMs(value)
                prefs.edit().putLong("fine_step", ms).apply()
                onFineStep(ms)
            }
            Text("앱 버전 ${UpdateChecker.installedVersion(env.context)}", color = LsText2, fontSize = 11.sp, modifier = Modifier.padding(top = 18.dp))
        }
    }
}

// ------------------------------------------------------------------ subtitle list

@Composable
internal fun BoxScope.SubtitleListSheet(env: LsEnv, live: Int, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val svc = env.svc
    var version by remember { mutableIntStateOf(0) }
    val items = remember(version) { SubtitleLinks.list(env.context) }
    val current = svc.subtitleUri
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    live.let { }

    fun link(item: SubtitleLinks.Item) {
        if (svc.currentTrackUri == null) {
            env.toast("먼저 오디오 파일을 추가해 주세요.")
            return
        }
        svc.setSubtitle(item.link)
        onChanged()
        env.toast("지금 곡의 자막으로 연결했어요.")
        version++
    }

    BottomSheet(0.7f, onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)) {
            Text("자막 목록", color = LsText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "‘자막 만들기’로 만든 자막 파일이에요. 줄을 누르면 지금 재생 중인 곡의 자막으로 연결돼요.",
                color = LsText2, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            if (items.isEmpty()) {
                Text(
                    "아직 만든 자막이 없어요.\n재생목록의 곡 메뉴나 플레이어의 ‘자막 만들기’로 만들 수 있어요.",
                    color = LsText2, fontSize = 14.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(items, key = { it.link }) { item ->
                        val isCurrent = item.link == current
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { link(item) }.padding(start = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    (if (isCurrent) "✔ " else "") + item.audioName, color = if (isCurrent) LsTealDark else LsText,
                                    fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${dateFormat.format(Date(item.savedAt))} · ${item.sizeBytes / 1024 + 1}KB" + if (isCurrent) " · 지금 곡에 연결됨" else "",
                                    color = LsText2, fontSize = 12.sp
                                )
                            }
                            Box {
                                PlainIconButton(R.drawable.ls_ic_more, "메뉴", size = 48.dp) { menuFor = item.link }
                                IconMenu(
                                    menuFor == item.link, { menuFor = null },
                                    listOf(
                                        MenuItem(1, "지금 곡의 자막으로 연결", R.drawable.ls_ic_subtitles),
                                        MenuItem(2, "자막 파일 삭제", R.drawable.ls_ic_delete, destructive = true, dividerBefore = true)
                                    )
                                ) { id ->
                                    when (id) {
                                        1 -> link(item)
                                        2 -> {
                                            if (svc.subtitleUri == item.link) svc.setSubtitle(null)
                                            SubtitleLinks.remove(env.context, item.link)
                                            onChanged()
                                            version++
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

// ------------------------------------------------------------------ making subtitles (SRT)

/** Asks where and under what name the subtitle file(s) are saved, then starts the SRT job. */
@Composable
internal fun SrtDialog(env: LsEnv, entries: List<TrackStore.Entry>, onDismiss: () -> Unit) {
    val context = env.context
    val todo = remember(entries) { entries.distinctBy { it.uri }.filter { SubtitleLinks.find(context.applicationContext, it.uri, it.name) == null } }
    if (todo.isEmpty()) {
        LaunchedEffect(Unit) {
            env.toast("이미 모두 자막이 있어요. (플레이어의 자막 버튼에서 볼 수 있어요)")
            onDismiss()
        }
        return
    }
    val single = todo.size == 1
    var name by remember { mutableStateOf(if (single) Storage.cleanName(todo[0].name.substringBeforeLast('.', todo[0].name), "srt") else "") }
    var folderVersion by remember { mutableIntStateOf(0) }
    // English uses the English-only model on Android; Korean (or automatic detection) needs the multilingual one.
    val languages = listOf("en" to "영어", "ko" to "한국어", "" to "자동 감지")
    var language by remember { mutableIntStateOf(0) }
    val folderLabel = remember(folderVersion) { SaveFolder.label(context) }
    val hasFolder = remember(folderVersion) { SaveFolder.get(context) != null }

    LsDialog(
        "자막(SRT) 만들기", onDismiss, "만들기",
        {
            val names = if (single) mapOf(todo[0].uri to Storage.cleanName(name, "srt")) else emptyMap()
            SrtMaker.start(context, todo, languages[language].first, names)
            onDismiss()
        }
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                if (single) "‘${todo[0].name}’의 자막을 만들어요. 말을 받아쓰는 작업이라 곡이 길면 오래 걸리고, 처음에는 음성 모델을 내려받아요."
                else "곡 ${todo.size}개의 자막을 차례로 만들어요. 이미 자막이 있는 곡은 건너뛰어요. 처음에는 음성 모델을 내려받아요.",
                color = LsText, fontSize = 14.sp
            )
            if (single) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    placeholder = { Text("파일 이름 (비우면 곡 이름)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = LsTeal, cursorColor = LsTeal)
                )
                Text("확장자 .srt 는 자동으로 붙어요.", color = LsText2, fontSize = 12.sp)
            } else {
                Text("파일 이름은 곡 이름을 그대로 써요.", color = LsText2, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
            Text("말하는 언어", color = LsText, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                languages.forEachIndexed { i, (_, label) ->
                    androidx.compose.material3.RadioButton(
                        selected = language == i, onClick = { language = i },
                        colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = LsTeal)
                    )
                    Text(label, color = LsText, fontSize = 14.sp, modifier = Modifier.clickable { language = i }.padding(end = 8.dp))
                }
            }
            Text("저장 폴더: $folderLabel", color = LsText, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
            Row {
                TextButton({ env.pickSaveFolder { folderVersion++ } }) { Text("저장 폴더 선택", color = LsTeal) }
                if (hasFolder) TextButton({ SaveFolder.set(context, null); folderVersion++ }) { Text("기본 폴더로", color = LsTeal) }
            }
            Text("자막은 곡에 바로 연결돼 플레이어에서 보이고, 같은 파일이 위 폴더에도 저장돼요.", color = LsText2, fontSize = 12.sp)
        }
    }
}
