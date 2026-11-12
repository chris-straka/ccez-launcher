package uk.ccez.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Columns that fit the width: phones get 5, wide screens more. */
fun columnsFor(widthDp: Int): Int = (widthDp / 76).coerceIn(4, 8)

/** A long-press lift dropped on a cell: folder, join, or reorder. */
fun pinDrop(store: LayoutStore, from: Int, to: Int) {
    if (from == to) return
    store.update { d ->
        if (from !in d.pins.indices || to !in d.pins.indices) return@update
        val dragged = d.pins[from]
        val target = d.pins[to]
        when {
            dragged is Pin.FolderRef -> {
                d.pins.removeAt(from)
                d.pins.add(to.coerceIn(0, d.pins.size), dragged)
            }
            target is Pin.FolderRef -> {
                d.pins.removeAt(from)
                d.folders[target.folderId]?.items?.add(dragged)
            }
            else -> {
                val id = "f${d.nextFolder}"
                d.nextFolder += 1
                d.folders[id] = Folder(id, "", mutableListOf(target, dragged))
                val at = minOf(from, to)
                for (i in listOf(from, to).sortedDescending()) d.pins.removeAt(i)
                d.pins.add(at.coerceIn(0, d.pins.size), Pin.FolderRef(id))
            }
        }
    }
}

@Composable fun ClockText() {
    val app = App.instance
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val settingsTick by app.settings.version.collectAsState()
    val inLang = remember(settingsTick) { app.settings.bool("words.clock_lang") }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            val intoMinute = (now.second * 1000 + now.nano / 1_000_000).toLong()
            delay((60_000 - intoMinute).coerceAtLeast(250))
        }
    }
    // The learning language of the day, once the core is up.
    val lang by androidx.compose.runtime.produceState<String?>(null, inLang) {
        if (!inLang) return@produceState
        repeat(24) {
            val l = LangOfDay.todayFromCore()
            if (l != null) {
                value = l
                return@produceState
            }
            delay(500)
        }
    }
    val spoken = lang?.takeIf { inLang }
    val time: String
    val date: String
    if (spoken != null) {
        time = remember(now, spoken) { ClockWords.time(spoken, now) }
        date = remember(now, spoken) { ClockWords.date(spoken, now.toLocalDate()) }
    } else {
        time = remember(now) { now.format(DateTimeFormatter.ofPattern("H:mm")) }
        date = remember(now) {
            now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.ENGLISH))
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 4.dp, start = 16.dp, end = 16.dp)
            .pointerInput(spoken) {
                detectTapGestures(onTap = {
                    if (spoken != null) Speaker.say(ctx, "$time. $date", spoken)
                })
            }
            .semantics { contentDescription = "$time, $date" }
            .testTag("clock"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            time, color = Palette.fg,
            fontSize = if (spoken != null) 30.sp else 56.sp,
            lineHeight = if (spoken != null) 36.sp else 58.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(date, color = Palette.dim, fontSize = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable fun HomeScreen(
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onSetHome: () -> Unit,
    onShade: () -> Unit,
    onDoubleTap: () -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onMenu: (MenuTarget) -> Unit,
) {
    val app = App.instance
    val layout by app.layout.data.collectAsState()
    val settingsTick by app.settings.version.collectAsState()
    val showClock = remember(settingsTick) { app.settings.bool("home.clock") }
    val isHome = remember(settingsTick) { app.apps.isDefaultHome() }
    var folderOpen by remember { mutableStateOf<Folder?>(null) }
    var dragTotal by remember { mutableStateOf(0f) }

    Column(Modifier.fillMaxSize().background(Palette.bg).testTag("home")) {
        if (showClock) ClockText()
        if (!isHome) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(onClick = onSetHome, modifier = Modifier.testTag("set-home")) {
                    Text("Set as home", color = Palette.accent, fontSize = 15.sp)
                }
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().testTag("home-empty")
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { onDoubleTap() },
                        onLongPress = { onOpenSettings() },
                    )
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { dragTotal = 0f },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            dragTotal += amount
                        },
                        onDragEnd = {
                            when {
                                dragTotal < -70f -> onOpenDrawer()
                                dragTotal > 70f -> onShade()
                            }
                            dragTotal = 0f
                        },
                    )
                },
        ) {
            // Learning words live here from Stage 2 (HomeWords.kt).
            HomeWordLayer(Modifier.fillMaxSize(), onLaunch = onLaunch)
        }
        PinnedGrid(
            pins = layout.pins,
            folders = layout.folders,
            onLaunch = onLaunch,
            onMenu = onMenu,
            onOpenFolder = { folderOpen = it },
        )
    }

    folderOpen?.let { folder ->
        val live = layout.folders[folder.id] ?: folder
        FolderDialog(
            folder = live,
            onDismiss = { folderOpen = null },
            onLaunch = { e -> folderOpen = null; onLaunch(e) },
            onMenu = onMenu,
        )
    }
}

@Composable fun FolderDialog(
    folder: Folder,
    onDismiss: () -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onMenu: (MenuTarget) -> Unit,
) {
    val app = App.instance
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101010),
        title = { Text(folder.name.ifBlank { "Folder" }, color = Palette.fg, fontSize = 17.sp) },
        modifier = Modifier.testTag("folder"),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (row in folder.items.chunked(4)) {
                    Row {
                        for (pin in row) {
                            val index = folder.items.indexOf(pin)
                            when (pin) {
                                is Pin.App -> {
                                    val e = app.apps.byId(pin.id)
                                    if (e != null) {
                                        AppCell(e, onClick = { onLaunch(e) }, onLongClick = {
                                            onMenu(MenuTarget.FolderItem(folder.id, index, pin)); onDismiss()
                                        })
                                    }
                                }
                                is Pin.Shortcut -> {
                                    ShortcutCell(pin, onClick = {
                                        val user = app.apps.userForSerial(pin.user)
                                        app.apps.startShortcut(pin.pkg, pin.shortcutId, user)
                                        onDismiss()
                                    }, onLongClick = {
                                        onMenu(MenuTarget.FolderItem(folder.id, index, pin)); onDismiss()
                                    })
                                }
                                is Pin.FolderRef -> Unit
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Palette.dim) }
        },
    )
}

@Composable fun ShortcutCell(pin: Pin.Shortcut, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(12.dp))
            .pointerInput(pin) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick?.invoke() },
                )
            }
            .padding(top = 8.dp, bottom = 6.dp)
            .semantics { contentDescription = pin.label }
            .testTag("shortcut:${pin.pkg}/${pin.shortcutId}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ShortcutIconImage(pin, Modifier.size(52.dp))
        Text(
            pin.label, color = Palette.fg, fontSize = 11.sp, lineHeight = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.width(72.dp),
        )
    }
}

/**
 * The pinned apps at the bottom. A hold lifts one: dropped on another it
 * makes a folder, on a folder it joins, elsewhere it moves. A hold
 * dropped back where it started opens its menu.
 */
@Composable fun PinnedGrid(
    pins: List<Pin>,
    folders: Map<String, Folder>,
    onLaunch: (AppEntry) -> Unit,
    onMenu: (MenuTarget) -> Unit,
    onOpenFolder: (Folder) -> Unit,
) {
    val app = App.instance
    if (pins.isEmpty()) return
    // Collected so the row appears as soon as the app list lands.
    val entries by app.apps.apps.collectAsState()
    val byId = remember(entries) { entries.associateBy { it.id } }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp).testTag("pins")) {
        val totalWidth = maxWidth
        val columns = columnsFor(totalWidth.value.toInt())
        val positions = remember { mutableStateMapOf<Int, Rect>() }
        var containerTopLeft by remember { mutableStateOf(Offset.Zero) }
        var dragIndex by remember { mutableStateOf<Int?>(null) }
        var dragPos by remember { mutableStateOf(Offset.Zero) }
        var dragMoved by remember { mutableStateOf(0f) }
        val density = LocalDensity.current
        val cellW = with(density) { (totalWidth / columns).toPx() }

        fun finishDrag() {
            val from = dragIndex ?: return
            val to = positions.entries.firstOrNull { (_, r) -> r.contains(dragPos) }?.key
            dragIndex = null
            when {
                to == null -> Unit
                to == from -> {
                    val pin = pins.getOrNull(from) ?: return
                    when (pin) {
                        is Pin.App -> byId[pin.id]?.let { onMenu(MenuTarget.HomeApp(from, it)) }
                        is Pin.Shortcut -> onMenu(MenuTarget.HomeShortcut(from, pin))
                        is Pin.FolderRef -> folders[pin.folderId]?.let { onMenu(MenuTarget.HomeFolder(from, it)) }
                    }
                }
                else -> pinDrop(app.layout, from, to)
            }
        }

        Box(Modifier.onGloballyPositioned { containerTopLeft = it.boundsInRoot().topLeft }) {
            Column {
                for (row in pins.withIndex().chunked(columns)) {
                    Row(Modifier.fillMaxWidth()) {
                        for ((index, pin) in row) {
                            Box(
                                Modifier.width(totalWidth / columns)
                                    .onGloballyPositioned { positions[index] = it.boundsInRoot() }
                                    .alpha(if (dragIndex == index) 0.35f else 1f)
                                    .pointerInput(index, pins.size) {
                                        detectTapGestures(onTap = {
                                            when (pin) {
                                                is Pin.App -> byId[pin.id]?.let(onLaunch)
                                                is Pin.Shortcut -> app.apps.startShortcut(
                                                    pin.pkg, pin.shortcutId, app.apps.userForSerial(pin.user),
                                                )
                                                is Pin.FolderRef -> folders[pin.folderId]?.let(onOpenFolder)
                                            }
                                        })
                                    }
                                    .pointerInput(index, pins.size) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { local ->
                                                val rect = positions[index] ?: Rect.Zero
                                                dragIndex = index
                                                dragMoved = 0f
                                                dragPos = rect.topLeft + local
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragPos += amount
                                                dragMoved += amount.getDistance()
                                            },
                                            onDragEnd = { finishDrag() },
                                            onDragCancel = { dragIndex = null },
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                PinCell(pin, folders, byId)
                            }
                        }
                    }
                }
            }
            // The lifted copy, under the finger.
            dragIndex?.let { i ->
                val pin = pins.getOrNull(i)
                if (pin != null && dragMoved > 4f) {
                    Box(
                        Modifier.offset {
                            IntOffset(
                                (dragPos.x - containerTopLeft.x - cellW / 2).roundToInt(),
                                (dragPos.y - containerTopLeft.y - cellW / 2).roundToInt(),
                            )
                        }.alpha(0.9f),
                    ) {
                        PinCell(pin, folders, byId)
                    }
                }
            }
        }
    }
}

/** One pin drawn (app, shortcut, or a folder's first four). Taps live in PinnedGrid. */
@Composable fun PinCell(
    pin: Pin,
    folders: Map<String, Folder>,
    byId: Map<AppId, AppEntry>,
) {
    val shownNames by AppNames.shown.collectAsState()
    when (pin) {
        is Pin.App -> {
            val e = byId[pin.id]
            if (e != null) {
                val shown = shownNames[e.key] ?: e.label
                // Shown only: the grid's own gestures handle tap and lift.
                Column(
                    Modifier.width(76.dp).padding(top = 8.dp, bottom = 6.dp)
                        .semantics { contentDescription = e.label }
                        .testTag("app:${e.key}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AppIconImage(e, Modifier.size(52.dp))
                    Text(
                        shown, color = Palette.fg, fontSize = 11.sp, lineHeight = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.width(72.dp),
                    )
                }
            } else {
                Box(Modifier.width(76.dp).padding(8.dp), contentAlignment = Alignment.Center) {
                    Text(pin.id.pkg.substringAfterLast('.').take(8), color = Palette.faint, fontSize = 10.sp)
                }
            }
        }
        is Pin.Shortcut -> {
            Column(
                Modifier.width(76.dp).padding(top = 8.dp, bottom = 6.dp)
                    .semantics { contentDescription = pin.label }
                    .testTag("shortcut:${pin.pkg}/${pin.shortcutId}"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ShortcutIconImage(pin, Modifier.size(52.dp))
                Text(
                    pin.label, color = Palette.fg, fontSize = 11.sp, lineHeight = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.width(72.dp),
                )
            }
        }
        is Pin.FolderRef -> {
            val folder = folders[pin.folderId]
            Column(
                Modifier.width(76.dp).padding(top = 8.dp, bottom = 6.dp)
                    .semantics { contentDescription = "Folder ${folder?.name.orEmpty()}" }
                    .testTag("folderpin:${pin.folderId}"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Palette.cell).padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val items = folder?.items.orEmpty().take(4)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (r in items.chunked(2)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                for (p in r) {
                                    when (p) {
                                        is Pin.App -> byId[p.id]?.let { AppIconImage(it, Modifier.size(18.dp)) }
                                        is Pin.Shortcut -> ShortcutIconImage(p, Modifier.size(18.dp))
                                        is Pin.FolderRef -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
                Text(
                    folder?.name?.ifBlank { "Folder" } ?: "Folder",
                    color = Palette.fg, fontSize = 11.sp, lineHeight = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.width(72.dp),
                )
            }
        }
    }
}
