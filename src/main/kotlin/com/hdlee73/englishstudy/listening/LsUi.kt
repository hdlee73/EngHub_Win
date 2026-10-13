package com.hdlee73.englishstudy.listening

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The colours of the Android app's ls_colors.xml and the shapes of its ls_bg_*.xml drawables.
internal val LsBg = Color(0xFFEAF3F1)
internal val LsBorder = Color(0xFFDCE9E6)
internal val LsMint = Color(0xFFDDF1EC)
internal val LsTeal = Color(0xFF168C82)
internal val LsTealDark = Color(0xFF0C6F67)
internal val LsText = Color(0xFF172B2A)
internal val LsText2 = Color(0xFF6B7D7A)
internal val LsCoral = Color(0xFFF28C72)
internal val LsRed = Color(0xFFC7452F)
internal val LsScrim = Color(0x52000000)

/** A vector icon from the app's drawable folder (ls_ic_*.xml), tinted like the Android ImageButtons. */
@Composable
internal fun LsIcon(res: Int, tint: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Icon(painterResource(res), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/** ls_HeaderIcon: 36dp round button on the border colour with a teal icon. */
@Composable
internal fun HeaderIcon(res: Int, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(36.dp).clip(RoundedCornerShape(22.dp)).background(LsBorder).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(res), contentDescription = description, tint = LsTealDark, modifier = Modifier.size(24.dp))
    }
}

/** A small icon button without background (track rows, menu buttons). */
@Composable
internal fun PlainIconButton(res: Int, description: String, tint: Color = LsText2, size: Dp = 40.dp, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(res), contentDescription = description, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/** ls_QuickChip: the mint pill with small bold teal text. */
@Composable
internal fun QuickChip(
    text: String,
    modifier: Modifier = Modifier,
    height: Dp = 28.dp,
    background: Color? = LsMint,
    color: Color = LsTealDark,
    fontSize: Int = 11,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.height(height).clip(shape).then(if (background != null) Modifier.background(background) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = color, fontSize = fontSize.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * The Material3 filled buttons of the player: a full-width pill with centred text.
 * [bold] and [fontSize] follow ls_CompactPrimaryButton / ls_SelectionButton / ls_StepButton.
 */
@Composable
internal fun LsButton(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = LsBorder,
    color: Color = LsText,
    height: Dp = 46.dp,
    fontSize: Int = 12,
    bold: Boolean = false,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(50),
    onClick: () -> Unit
) {
    val bg = if (enabled) background else LsText.copy(alpha = 0.12f)
    val fg = if (enabled) color else LsText.copy(alpha = 0.38f)
    Box(
        modifier.height(height).clip(shape).background(bg).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = fg, fontSize = fontSize.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}

/** The 36 x 4 dp grab handle at the top of every sheet. */
@Composable
internal fun SheetHandle() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(LsBorder))
    }
}

/**
 * Bottom sheet over the Listening screen. Tapping the dimmed area closes it.
 * [heightFraction] null = as high as its content.
 */
@Composable
internal fun BoxScope.BottomSheet(heightFraction: Float?, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.matchParentSize().background(LsScrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
    )
    Box(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .then(if (heightFraction != null) Modifier.fillMaxHeight(heightFraction) else Modifier)
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(Color.White)
            // Swallow clicks so the scrim behind the sheet does not receive them.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
    ) { content() }
}

/** One row of an [IconMenu]. [icon] 0 leaves the icon column empty (still aligned). */
internal class MenuItem(
    val id: Int,
    val label: String,
    val icon: Int = 0,
    val destructive: Boolean = false,
    val dividerBefore: Boolean = false
)

/** The rounded pop-up menu with a leading icon on every row (the menu style of the whole Listening tab). Place it inside its anchor. */
@Composable
internal fun IconMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<MenuItem>, onPick: (Int) -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, LsBorder),
        modifier = Modifier.widthIn(min = 236.dp)
    ) {
        items.forEach { item ->
            if (item.dividerBefore) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).height(1.dp).background(LsBorder))
            }
            val tint = if (item.destructive) LsRed else LsTeal
            DropdownMenuItem(
                text = { Text(item.label, fontSize = 15.sp, color = if (item.destructive) LsRed else LsText) },
                leadingIcon = {
                    if (item.icon != 0) LsIcon(item.icon, tint, 22.dp) else Spacer(Modifier.size(22.dp))
                },
                contentPadding = PaddingValues(start = 16.dp, end = 20.dp),
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = {
                    onDismiss()
                    onPick(item.id)
                }
            )
        }
    }
}

/** MaterialAlertDialog look: white card, 28dp corners, bold title. */
@Composable
internal fun LsDialog(
    title: String?,
    onDismiss: () -> Unit,
    confirmText: String? = null,
    onConfirm: () -> Unit = {},
    dismissText: String? = "취소",
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(28.dp),
        title = title?.let { { Text(it, color = LsText, fontSize = 20.sp) } },
        text = content,
        confirmButton = { if (confirmText != null) TextButton(onClick = onConfirm) { Text(confirmText, color = LsTeal) } },
        dismissButton = { if (dismissText != null) TextButton(onClick = onDismiss) { Text(dismissText, color = LsTeal) } }
    )
}

/** A text-input dialog (names of folders and saved ranges). */
@Composable
internal fun PromptDialog(title: String, initial: String, positive: String = "확인", onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LsDialog(title, onDismiss, positive, { onOk(value.text) }) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = LsTeal, cursorColor = LsTeal)
        )
    }
}

/** A message with a confirming button. */
@Composable
internal fun ConfirmDialog(message: String, positive: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    LsDialog(null, onDismiss, positive, { onDismiss(); onOk() }) { Text(message, color = LsText, fontSize = 16.sp) }
}

/** A list of choices in a dialog (MaterialAlertDialog setItems). */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun ChoiceDialog(
    title: String,
    labels: List<String>,
    onDismiss: () -> Unit,
    onLongPick: ((Int) -> Unit)? = null,
    onPick: (Int) -> Unit
) {
    LsDialog(title, onDismiss) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            labels.forEachIndexed { index, label ->
                Text(
                    label,
                    color = LsText,
                    fontSize = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                        .combinedClickable(onClick = { onDismiss(); onPick(index) }, onLongClick = onLongPick?.let { long -> { long(index) } })
                        .padding(vertical = 12.dp)
                )
            }
        }
    }
}

/** Rounded white card with the 1dp border (ls_bg_card). */
@Composable
internal fun LsCard(modifier: Modifier = Modifier, padding: PaddingValues, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White)
            .border(1.dp, LsBorder, RoundedCornerShape(24.dp)).padding(padding)
    ) { content() }
}
