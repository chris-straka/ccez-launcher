package uk.ccez.launcher

import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** Pure black (AMOLED), white, grays. No branding, no badges. */
object Palette {
    val bg = Color.Black
    val fg = Color(0xFFF2F2F2)
    /** App names: a touch under white, so icons lead. */
    val label = Color(0xFFD4D4D4)
    val dim = Color(0xFF8E8E8E)
    val faint = Color(0xFF575757)
    /** Surfaces on black: strips, fields, cards. Barely there. */
    val cell = Color(0xFF111111)
    /** A menu above everything. */
    val raised = Color(0xFF161616)
    val line = Color(0xFF222222)
    val accent = Color(0xFF8AB4F8)
}

/**
 * One grid for every screen: content starts [margin] in from each edge,
 * surfaces (strips, fields, cards) span the same edges and pad [inset]
 * inside, app icons are [icon] wide. Type sizes are a small scale.
 */
object Look {
    val margin = 20.dp
    val inset = 16.dp
    val icon = 50.dp
    val cell = 76.dp
    val field = 52.dp
    val radius = 18.dp
    val labelSize = 12.sp
    val bodySize = 16.sp
    val smallSize = 13.sp

    /** Side padding for an n-column grid of [cellW]-ish cells so the first and last icons sit on the margins. */
    fun gridPad(width: Dp, columns: Int): Dp {
        if (columns <= 1) return 0.dp
        val pad = (margin.value + icon.value / 2 - width.value / (2 * columns)) / (1f - 1f / columns)
        return pad.coerceIn(0f, margin.value).dp
    }

    /** Drawer and hidden space: as many 76 dp columns as fit, never under four. */
    fun drawerColumns(width: Dp): Int = (width.value / cell.value).toInt().coerceAtLeast(4)
}

/** A hairline, for the few places a group needs one. */
@Composable fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Palette.line))
}

/**
 * The one text field: a quiet filled pill (Search, Ask). The whole pill
 * is the field, so a tap anywhere on it focuses.
 */
@Composable fun PillField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = Look.field),
        singleLine = true,
        textStyle = TextStyle(color = Palette.fg, fontSize = Look.bodySize),
        cursorBrush = SolidColor(Palette.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = Look.field)
                    .clip(RoundedCornerShape(Look.radius))
                    .background(Palette.cell)
                    .padding(horizontal = Look.inset, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, color = Palette.faint, fontSize = Look.bodySize)
                inner()
            }
        },
    )
}

@Composable fun AppIconImage(entry: AppEntry, modifier: Modifier = Modifier) {
    val bmp by produceState<Bitmap?>(null, entry.key) {
        value = App.instance.apps.icon(entry)
    }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = null, modifier = modifier)
    } else {
        Box(modifier.clip(RoundedCornerShape(14.dp)).background(Palette.cell))
    }
}

@Composable fun ShortcutIconImage(pin: Pin.Shortcut, modifier: Modifier = Modifier) {
    val bmp by produceState<Bitmap?>(null, pin.pkg, pin.shortcutId) {
        value = App.instance.apps.shortcutIcon(pin)
    }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = null, modifier = modifier)
    } else {
        Box(modifier.clip(RoundedCornerShape(14.dp)).background(Palette.cell), contentAlignment = Alignment.Center) {
            Text(pin.label.take(1), color = Palette.fg, fontSize = 20.sp)
        }
    }
}

/** An icon over its name: the drawer's, the dock's and folders' one cell. */
@Composable fun IconLabel(
    label: String,
    width: Dp,
    icon: @Composable (Modifier) -> Unit,
) {
    Column(
        Modifier.width(width).padding(top = 8.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon(Modifier.size(Look.icon))
        Text(
            label,
            color = Palette.label,
            fontSize = Look.labelSize,
            lineHeight = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(width - 6.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable fun AppCell(
    entry: AppEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    labelOverride: String? = null,
    width: Dp = Look.cell,
) {
    // The name in today's learning language when Stage 5 is on.
    val shownNames by AppNames.shown.collectAsState()
    val label = labelOverride ?: shownNames[entry.key] ?: entry.label
    Box(
        modifier
            .width(width)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = if (entry.otherProfile) "$label, work" else label }
            .testTag("app:${entry.key}"),
    ) {
        IconLabel(label, width) { AppIconImage(entry, it) }
    }
}

sealed class MenuTarget {
    data class DrawerApp(val entry: AppEntry) : MenuTarget()
    data class HiddenApp(val entry: AppEntry) : MenuTarget()
    data class HomeApp(val index: Int, val entry: AppEntry) : MenuTarget()
    data class HomeShortcut(val index: Int, val pin: Pin.Shortcut) : MenuTarget()
    data class HomeFolder(val index: Int, val folder: Folder) : MenuTarget()
    data class FolderItem(val folderId: String, val index: Int, val pin: Pin) : MenuTarget()
}

sealed class MenuAction {
    data class PinApp(val entry: AppEntry) : MenuAction()
    data class Unpin(val index: Int) : MenuAction()
    data class Hide(val entry: AppEntry) : MenuAction()
    data class Unhide(val entry: AppEntry) : MenuAction()
    data class Info(val entry: AppEntry) : MenuAction()
    data class Uninstall(val entry: AppEntry) : MenuAction()
    data class StartShortcut(val entry: AppEntry, val shortcut: ShortcutInfo) : MenuAction()
    data class PinShortcut(val entry: AppEntry, val shortcut: ShortcutInfo) : MenuAction()
    data class ToggleGate(val entry: AppEntry, val on: Boolean) : MenuAction()
    data class RemoveFromFolder(val folderId: String, val index: Int) : MenuAction()
    data class DissolveFolder(val index: Int) : MenuAction()
    data class RenameFolder(val folder: Folder, val name: String) : MenuAction()
}

/**
 * A menu's card: raised a step off black, in with a short scale and fade
 * (the window's own fade carries it out). Back or a tap outside closes it.
 */
@Composable fun MenuSurface(
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    icon: (@Composable (Modifier) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        val enter = remember { Animatable(0f) }
        LaunchedEffect(Unit) { enter.animateTo(1f, tween(180, easing = FastOutSlowInEasing)) }
        Column(
            modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = enter.value
                    val s = 0.94f + 0.06f * enter.value
                    scaleX = s
                    scaleY = s
                }
                .clip(RoundedCornerShape(24.dp))
                .background(Palette.raised)
                .semantics { paneTitle = title }
                .padding(vertical = 8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                icon?.invoke(Modifier.size(36.dp))
                Text(
                    title, color = Palette.fg, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Hairline(Modifier.padding(bottom = 6.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) { content() }
        }
    }
}

/** One line in a menu: the action, and on the right a quiet state or a second action. */
@Composable fun MenuRow(
    text: String,
    testTag: String,
    trailing: String? = null,
    color: Color = Palette.fg,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp).testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = color, fontSize = Look.bodySize, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = Palette.dim, fontSize = Look.smallSize)
    }
}

@Composable fun AppMenuDialog(
    target: MenuTarget,
    pinned: Boolean,
    gated: Boolean,
    onDismiss: () -> Unit,
    onAction: (MenuAction) -> Unit,
) {
    val entry: AppEntry? = when (target) {
        is MenuTarget.DrawerApp -> target.entry
        is MenuTarget.HiddenApp -> target.entry
        is MenuTarget.HomeApp -> target.entry
        is MenuTarget.FolderItem -> (target.pin as? Pin.App)?.let { App.instance.apps.byId(it.id) }
        else -> null
    }
    val title = when (target) {
        is MenuTarget.HomeShortcut -> target.pin.label
        is MenuTarget.HomeFolder -> target.folder.name.ifBlank { "Folder" }
        else -> entry?.label.orEmpty()
    }
    val shortcuts by produceState<List<ShortcutInfo>>(emptyList(), entry?.key) {
        value = entry?.let { App.instance.apps.shortcuts(it) }.orEmpty()
    }
    var renaming by remember { mutableStateOf(false) }

    val icon: (@Composable (Modifier) -> Unit)? = when {
        entry != null -> { m -> AppIconImage(entry, m) }
        target is MenuTarget.HomeShortcut -> { m -> ShortcutIconImage(target.pin, m) }
        else -> null
    }

    MenuSurface(onDismiss = onDismiss, title = title, icon = icon) {
        when (target) {
            is MenuTarget.DrawerApp -> {
                if (pinned) MenuRow("Pinned", "menu-pinned", color = Palette.dim) {}
                else MenuRow("Pin to home", "menu-pin") { onAction(MenuAction.PinApp(target.entry)) }
                MenuRow("Hide", "menu-hide") { onAction(MenuAction.Hide(target.entry)) }
            }
            is MenuTarget.HiddenApp -> MenuRow("Unhide", "menu-unhide") { onAction(MenuAction.Unhide(target.entry)) }
            is MenuTarget.HomeApp -> MenuRow("Unpin", "menu-unpin") { onAction(MenuAction.Unpin(target.index)) }
            is MenuTarget.HomeShortcut -> MenuRow("Unpin", "menu-unpin") { onAction(MenuAction.Unpin(target.index)) }
            is MenuTarget.HomeFolder -> {
                MenuRow("Rename", "menu-rename") { renaming = true }
                MenuRow("Remove folder", "menu-dissolve") { onAction(MenuAction.DissolveFolder(target.index)) }
            }
            is MenuTarget.FolderItem -> MenuRow("Take out", "menu-takeout") {
                onAction(MenuAction.RemoveFromFolder(target.folderId, target.index))
            }
        }
        if (entry != null) {
            MenuRow("Word first", "menu-gate", trailing = if (gated) "On" else "Off") {
                onAction(MenuAction.ToggleGate(entry, !gated))
            }
            if (shortcuts.isNotEmpty()) Hairline(Modifier.padding(vertical = 6.dp))
            for (si in shortcuts) {
                val label = si.shortLabel?.toString().orEmpty().ifBlank { si.id }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        MenuRow(label, "menu-shortcut") { onAction(MenuAction.StartShortcut(entry, si)) }
                    }
                    Text(
                        "Pin", color = Palette.accent, fontSize = Look.smallSize,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp))
                            .clickable { onAction(MenuAction.PinShortcut(entry, si)) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
            Hairline(Modifier.padding(vertical = 6.dp))
            MenuRow("App info", "menu-info") { onAction(MenuAction.Info(entry)) }
            MenuRow("Uninstall", "menu-uninstall") { onAction(MenuAction.Uninstall(entry)) }
        }
    }

    if (renaming && target is MenuTarget.HomeFolder) {
        var name by remember { mutableStateOf(target.folder.name) }
        MenuSurface(onDismiss = { renaming = false }, title = "Folder name") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                PillField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "Folder",
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onAction(MenuAction.RenameFolder(target.folder, name.trim())) }),
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End) {
                Text(
                    "Save", color = Palette.accent, fontSize = Look.bodySize,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                        .clickable { onAction(MenuAction.RenameFolder(target.folder, name.trim())) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}
