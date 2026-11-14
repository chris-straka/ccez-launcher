package uk.ccez.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Columns that fit the width: phones get 5, wide screens more. */
fun columnsFor(widthDp: Int): Int = (widthDp / 76).coerceIn(4, 8)

/**
 * The dock keeps one row when one more column still leaves 64 dp a cell:
 * six pins on a phone sit in a row, not five and one alone.
 */
fun dockColumns(widthDp: Int, pins: Int): Int {
    val base = columnsFor(widthDp)
    return if (pins == base + 1 && widthDp / pins >= 64) pins else base
}

/** A long-press lift dropped on a cell: folder or join in its middle, a move near its edges (LayoutOps.drop). */
fun pinDrop(store: LayoutStore, from: Int, to: Int, middle: Boolean = true, after: Boolean = false) {
    store.update { d -> LayoutOps.drop(d, from, to, middle, after) }
}

/**
 * Swipe up anywhere on home opens the drawer, swipe down pulls the shade:
 * over the pins, the strips and the Ask field too. Watched on the way in
 * (Initial pass), so a vertical swipe is taken from whatever is under the
 * finger before its tap or hold fires. A hold that reaches the long-press
 * time first stays the child's (a pin's lift and drag, the home menu).
 */
fun Modifier.homeSwipes(onUp: () -> Unit, onDown: () -> Unit): Modifier = pointerInput(Unit) {
    val distance = 70f
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val holdMs = viewConfiguration.longPressTimeoutMillis
        val slop = viewConfiguration.touchSlop
        var taken = false
        var dy = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val moved = change.position - down.position
            if (change.changedToUpIgnoreConsumed()) {
                if (taken) {
                    change.consume()
                    dy = moved.y
                }
                break
            }
            if (taken) {
                change.consume()
                continue
            }
            if (change.isConsumed) break
            if (change.uptimeMillis - down.uptimeMillis >= holdMs) break
            if (abs(moved.y) > slop && abs(moved.y) > 2 * abs(moved.x)) {
                taken = true
                change.consume()
            }
        }
        if (taken) {
            when {
                dy < -distance -> onUp()
                dy > distance -> onDown()
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
    // The learning language of the day, once the core is up; picked again
    // when the day turns (it rotates daily, PLAN Stage 5).
    val today = now.toLocalDate()
    val lang by androidx.compose.runtime.produceState<String?>(null, inLang, today) {
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
    val saying by rememberUpdatedState(spoken to "$time. $date")
    // Big, thin, on the left margin. Its scale follows the font size only so
    // far: past 1.3x it would wrap a four-digit time.
    val fontScale = LocalDensity.current.fontScale
    val cap = if (fontScale > 1.3f) 1.3f / fontScale else 1f
    Column(
        Modifier.fillMaxWidth().padding(top = 40.dp, start = Look.margin, end = Look.margin)
            .pointerInput(Unit) {
                // Read at the tap: the handler outlives the minute it began in.
                detectTapGestures(onTap = {
                    val (lang, said) = saying
                    if (lang != null) Speaker.say(ctx, said, lang)
                })
            }
            .semantics { contentDescription = "$time, $date" }
            .testTag("clock"),
    ) {
        Text(
            time, color = Palette.fg,
            fontSize = (if (spoken != null) 34f else 76f).sp * cap,
            lineHeight = (if (spoken != null) 40f else 80f).sp * cap,
            fontWeight = if (spoken != null) FontWeight.Light else FontWeight.Thin,
            letterSpacing = if (spoken != null) 0.sp else (-1.5).sp,
            // Thin numerals carry a little side bearing: pulled onto the margin.
            modifier = Modifier.offset(x = if (spoken != null) 0.dp else (-3).dp),
        )
        Text(
            date, color = Palette.dim, fontSize = 15.sp, lineHeight = 20.sp,
            letterSpacing = 0.2.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
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

    // Widgets together take at most a quarter of the screen, so the clock,
    // the strips and the pins below them still fit on a phone.
    val widgetCap = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp / 4
    Column(
        Modifier.fillMaxSize().background(Palette.bg).testTag("home")
            .homeSwipes(onUp = onOpenDrawer, onDown = onShade),
    ) {
        if (showClock) ClockText()
        if (!isHome) {
            TextButton(
                onClick = onSetHome,
                modifier = Modifier.padding(start = Look.margin - 12.dp, top = 4.dp).testTag("set-home"),
            ) {
                Text("Set as home", color = Palette.accent, fontSize = 15.sp)
            }
        }
        TodayStrip()
        ClipStrip()
        Box(
            Modifier.weight(1f).fillMaxWidth().testTag("home-empty")
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { onDoubleTap() },
                        onLongPress = { onOpenSettings() },
                    )
                },
        ) {
            // Learning words live here from Stage 2 (HomeWords.kt).
            HomeWordLayer(Modifier.fillMaxSize(), onLaunch = onLaunch)
        }
        WidgetStrip(maxHeight = widgetCap)
        ContinueStrip(onLaunch = onLaunch)
        LlmAskStrip()
        Spacer(Modifier.height(4.dp))
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
    MenuSurface(
        onDismiss = onDismiss,
        title = folder.name.ifBlank { "Folder" },
        modifier = Modifier.testTag("folder"),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            for (row in folder.items.chunked(4)) {
                Row {
                    for (pin in row) {
                        val index = folder.items.indexOf(pin)
                        when (pin) {
                            is Pin.App -> {
                                val e = app.apps.byId(pin.id)
                                if (e != null) {
                                    AppCell(e, width = 76.dp, onClick = { onLaunch(e) }, onLongClick = {
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
    }
}

@Composable fun ShortcutCell(pin: Pin.Shortcut, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Box(
        Modifier.width(Look.cell).clip(RoundedCornerShape(16.dp))
            .pointerInput(pin) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick?.invoke() },
                )
            }
            .semantics { contentDescription = pin.label }
            .testTag("shortcut:${pin.pkg}/${pin.shortcutId}"),
    ) {
        IconLabel(pin.label, Look.cell) { ShortcutIconImage(pin, it) }
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
    // The gesture blocks below outlive compositions (pointerInput restarts
    // only on its keys), so they read the row through these: a cell tapped
    // or held after a move, or after the app list landed, acts on the pin
    // it shows now (review 2026-11-14: a moved pin opened the old app).
    val livePins by rememberUpdatedState(pins)
    val liveById by rememberUpdatedState(byId)
    val liveFolders by rememberUpdatedState(folders)
    val launch by rememberUpdatedState(onLaunch)
    val menu by rememberUpdatedState(onMenu)
    val openFolder by rememberUpdatedState(onOpenFolder)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("pins")) {
        val columns = dockColumns(maxWidth.value.toInt(), pins.size)
        // The first and last icons sit on the margins, as the strips above do.
        val side = Look.gridPad(maxWidth, columns)
        val totalWidth = maxWidth - side * 2
        val positions = remember { mutableStateMapOf<Int, Rect>() }
        var containerTopLeft by remember { mutableStateOf(Offset.Zero) }
        var dragIndex by remember { mutableStateOf<Int?>(null) }
        var dragPos by remember { mutableStateOf(Offset.Zero) }
        var dragMoved by remember { mutableStateOf(0f) }
        val density = LocalDensity.current
        val cellW = with(density) { (totalWidth / columns).toPx() }

        fun tap(index: Int) {
            when (val target = PinTarget.at(livePins, index, liveById, liveFolders)) {
                is PinTarget.AppPin -> launch(target.entry)
                is PinTarget.ShortcutPin -> app.apps.startShortcut(
                    target.pin.pkg, target.pin.shortcutId, app.apps.userForSerial(target.pin.user),
                )
                is PinTarget.FolderPin -> openFolder(target.folder)
                is PinTarget.GoneApp, null -> Unit
            }
        }

        fun finishDrag() {
            val from = dragIndex ?: return
            val to = positions.entries.firstOrNull { (_, r) -> r.contains(dragPos) }?.key
            dragIndex = null
            when {
                to == null -> Unit
                to == from -> when (val target = PinTarget.at(livePins, from, liveById, liveFolders)) {
                    is PinTarget.AppPin -> menu(MenuTarget.HomeApp(from, target.entry))
                    is PinTarget.ShortcutPin -> menu(MenuTarget.HomeShortcut(from, target.pin))
                    is PinTarget.FolderPin -> menu(MenuTarget.HomeFolder(from, target.folder))
                    is PinTarget.GoneApp -> menu(MenuTarget.HomeGone(from, target.pin))
                    null -> Unit
                }
                else -> {
                    // The middle half of the cell makes a folder; the rest moves it.
                    val r = positions[to] ?: return
                    val middle = kotlin.math.abs(dragPos.x - r.center.x) < r.width / 4 && kotlin.math.abs(dragPos.y - r.center.y) < r.height / 4
                    pinDrop(app.layout, from, to, middle, after = dragPos.x > r.center.x)
                }
            }
        }

        Box(Modifier.padding(horizontal = side).onGloballyPositioned { containerTopLeft = it.boundsInRoot().topLeft }) {
            Column {
                for (row in pins.withIndex().chunked(columns)) {
                    Row(Modifier.fillMaxWidth()) {
                        for ((index, pin) in row) {
                            Box(
                                Modifier.width(totalWidth / columns)
                                    .onGloballyPositioned { positions[index] = it.boundsInRoot() }
                                    .alpha(if (dragIndex == index) 0.35f else 1f)
                                    .pointerInput(index) {
                                        detectTapGestures(onTap = { tap(index) })
                                    }
                                    .pointerInput(index) {
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
                                PinCell(pin, folders, byId, totalWidth / columns)
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
                        PinCell(pin, folders, byId, totalWidth / columns)
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
    width: Dp = Look.cell,
) {
    val shownNames by AppNames.shown.collectAsState()
    when (pin) {
        is Pin.App -> {
            val e = byId[pin.id]
            if (e != null) {
                val shown = shownNames[e.key] ?: e.label
                // Shown only: the grid's own gestures handle tap and lift.
                Box(
                    Modifier.semantics { contentDescription = e.label }.testTag("app:${e.key}"),
                ) {
                    IconLabel(shown, width) { AppIconImage(e, it) }
                }
            } else {
                Box(Modifier.width(width).padding(8.dp), contentAlignment = Alignment.Center) {
                    Text(pin.id.pkg.substringAfterLast('.').take(8), color = Palette.faint, fontSize = 10.sp)
                }
            }
        }
        is Pin.Shortcut -> {
            Box(
                Modifier.semantics { contentDescription = pin.label }
                    .testTag("shortcut:${pin.pkg}/${pin.shortcutId}"),
            ) {
                IconLabel(pin.label, width) { ShortcutIconImage(pin, it) }
            }
        }
        is Pin.FolderRef -> {
            val folder = folders[pin.folderId]
            Box(
                Modifier.semantics { contentDescription = "Folder ${folder?.name.orEmpty()}" }
                    .testTag("folderpin:${pin.folderId}"),
            ) {
                IconLabel(folder?.name?.ifBlank { "Folder" } ?: "Folder", width) { m ->
                    Box(
                        m.clip(RoundedCornerShape(16.dp)).background(Palette.cell).padding(7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val items = folder?.items.orEmpty().take(4)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (r in items.chunked(2)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    for (p in r) {
                                        when (p) {
                                            is Pin.App -> byId[p.id]?.let { AppIconImage(it, Modifier.size(16.dp)) }
                                            is Pin.Shortcut -> ShortcutIconImage(p, Modifier.size(16.dp))
                                            is Pin.FolderRef -> Unit
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
