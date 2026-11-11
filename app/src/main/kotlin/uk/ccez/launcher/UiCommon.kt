package uk.ccez.launcher

import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Pure black (AMOLED), white, grays. No branding, no badges. */
object Palette {
    val bg = Color.Black
    val fg = Color.White
    val dim = Color(0xFF9A9A9A)
    val faint = Color(0xFF5A5A5A)
    val cell = Color(0xFF141414)
    val line = Color(0xFF2A2A2A)
    val accent = Color(0xFF8AB4F8)
}

@Composable fun AppIconImage(entry: AppEntry, modifier: Modifier = Modifier) {
    val bmp by produceState<Bitmap?>(null, entry.key) {
        value = App.instance.apps.icon(entry)
    }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = null, modifier = modifier)
    } else {
        Box(modifier.clip(RoundedCornerShape(12.dp)).background(Palette.cell))
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
        Box(modifier.clip(RoundedCornerShape(12.dp)).background(Palette.cell), contentAlignment = Alignment.Center) {
            Text(pin.label.take(1), color = Palette.fg, fontSize = 22.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable fun AppCell(
    entry: AppEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    labelOverride: String? = null,
) {
    val label = labelOverride ?: entry.label
    Column(
        modifier
            .width(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(top = 8.dp, bottom = 6.dp)
            .semantics { contentDescription = if (entry.otherProfile) "$label, work" else label }
            .testTag("app:${entry.key}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppIconImage(entry, Modifier.size(52.dp))
        Text(
            label,
            color = Palette.fg,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(72.dp),
        )
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

@Composable private fun MenuRow(text: String, testTag: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp).testTag(testTag),
    ) {
        Text(text, color = Palette.fg, fontSize = 16.sp)
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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101010),
        title = { Text(title, color = Palette.fg, fontSize = 17.sp) },
        text = {
            Column {
                when (target) {
                    is MenuTarget.DrawerApp -> {
                        if (pinned) MenuRow("Pinned", "menu-pinned") {}
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
                    MenuRow(if (gated) "Word first: on" else "Word first: off", "menu-gate") {
                        onAction(MenuAction.ToggleGate(entry, !gated))
                    }
                    for (si in shortcuts) {
                        val label = si.shortLabel?.toString().orEmpty().ifBlank { si.id }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.weight(1f)) {
                                MenuRow(label, "menu-shortcut") { onAction(MenuAction.StartShortcut(entry, si)) }
                            }
                            TextButton(onClick = { onAction(MenuAction.PinShortcut(entry, si)) }) {
                                Text("Pin", color = Palette.accent, fontSize = 14.sp)
                            }
                        }
                    }
                    MenuRow("App info", "menu-info") { onAction(MenuAction.Info(entry)) }
                    MenuRow("Uninstall", "menu-uninstall") { onAction(MenuAction.Uninstall(entry)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Palette.dim) }
        },
    )

    if (renaming && target is MenuTarget.HomeFolder) {
        var name by remember { mutableStateOf(target.folder.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = Color(0xFF101010),
            title = { Text("Folder name", color = Palette.fg, fontSize = 17.sp) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Palette.fg,
                        unfocusedTextColor = Palette.fg,
                        focusedBorderColor = Palette.dim,
                        unfocusedBorderColor = Palette.line,
                        cursorColor = Palette.accent,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { onAction(MenuAction.RenameFolder(target.folder, name.trim())) }) {
                    Text("Save", color = Palette.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }) { Text("Close", color = Palette.dim) }
            },
        )
    }
}
