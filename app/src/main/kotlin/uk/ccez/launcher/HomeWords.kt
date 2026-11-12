package uk.ccez.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.ccez.keys.core.Grade
import uk.ccez.keys.core.ScreenWord

/** One word up on home: where it sits (0..1 across the empty part) and its state. */
private class UpWord(
    val word: ScreenWord,
    val x: Float,
    val y: Float,
    val bornMs: Long,
) {
    var touched by mutableStateOf(false)
    var held by mutableStateOf(false)
    var gone by mutableStateOf(false)
}

private const val LEAVE_MS = 90_000L
private const val TICK_MS = 3_000L

/**
 * The room words have, in dp, and the font scale: spans and line gaps
 * follow them, so big text spaces words further apart.
 */
private class Area(val wDp: Float, val hDp: Float, val fontScale: Float)

/** About how much of the width a word takes (0..1): ~12 dp a letter at 22 sp. */
private fun spanOf(word: ScreenWord, a: Area): Float =
    ((word.shown.length * 12.5f + 20f) * a.fontScale / a.wDp).coerceIn(0.12f, 0.9f)

/**
 * A steadier spot for each word: its own hash, nudged off its neighbours.
 * Two words never share a band where their spans would touch: the band
 * is the word's line and a two-line meaning under it.
 */
private fun spotFor(word: ScreenWord, up: List<UpWord>, a: Area): Pair<Float, Float>? {
    // Long words start further left, so they never run off the edge.
    val est = spanOf(word, a)
    val span = ((0.94f - est - 0.05f) * 1000).toInt().coerceAtLeast(50)
    val band = ((26f + 40f) * a.fontScale + 12f) / a.hDp
    val ySpan = ((0.96f - band * 0.6f - 0.04f) * 1000).toInt().coerceAtLeast(50)
    var h = (word.lang + "\t" + word.word).hashCode().toLong() and 0xFFFFFFFFL
    fun clear(x: Float, y: Float) = up.none {
        val apart = x + est < it.x || it.x + spanOf(it.word, a) < x
        abs(it.y - y) < band && !apart
    }
    var first: Pair<Float, Float>? = null
    repeat(32) {
        val x = 0.05f + (h % span) / 1000f
        val y = 0.04f + ((h / span) % ySpan) / 1000f
        if (first == null) first = x to y
        if (clear(x, y)) return x to y
        h = h * 2654435761L and 0xFFFFFFFFL
    }
    // No room without touching another: the first word always shows,
    // the rest wait for a later tick (big text, a crowded home).
    return if (up.isEmpty()) first else null
}

/**
 * Learning words in the empty part of home (Stage 2), from the core's
 * screen_words: faint, in slowly over two seconds, gone after ninety
 * (never under a finger). A touch shows the meaning under the word
 * without moving it. Tap Good, hold Again, double-tap Easy: the core's
 * FSRS schedule says when it comes back, synced through ccez-words.
 */
@Composable fun HomeWordLayer(modifier: Modifier = Modifier, onLaunch: (AppEntry) -> Unit = {}) {
    val app = App.instance
    val ctx = LocalContext.current
    val settingsTick by app.settings.version.collectAsState()
    val scope = rememberCoroutineScope()
    val up = remember { mutableStateOf<List<UpWord>>(emptyList()) }
    val density = LocalDensity.current
    var area by remember { mutableStateOf(Area(411f, 450f, 1f)) }

    LaunchedEffect(settingsTick) {
        WordSync.maybe(ctx)
        while (true) {
            val count = app.settings.number("words.count")
            val now = System.currentTimeMillis()
            // Words whose time is up leave, quietly (not under a finger).
            up.value = up.value.filter { u ->
                !u.gone && (u.held || u.touched || now - u.bornMs < LEAVE_MS)
            }
            if (count > 0 && up.value.size < count) {
                val current = up.value.map { it.word }
                val fresh = withContext(Dispatchers.IO) { Words.screenWords(ctx, current) }
                if (fresh.isNotEmpty()) {
                    val placed = up.value.toMutableList()
                    for (w in fresh) {
                        val (x, y) = spotFor(w, placed, area) ?: continue
                        placed.add(UpWord(w, x, y, System.currentTimeMillis()))
                    }
                    up.value = placed
                }
            }
            if (count <= 0) up.value = emptyList()
            delay(TICK_MS)
        }
    }

    fun answer(u: UpWord, grade: Grade) {
        if (u.gone) return
        u.touched = true
        Words.grade(ctx, u.word, grade, app.scope)
        WordSync.soon(ctx)
        scope.launch {
            delay(1_100)
            u.gone = true
            up.value = up.value - u
        }
    }

    BoxWithConstraints(
        modifier.testTag("home-words").onSizeChanged {
            with(density) { area = Area(it.width.toDp().value, it.height.toDp().value, fontScale) }
        },
    ) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        for (u in up.value) {
            // The meaning wraps before the right margin, never off the screen.
            val room = (maxWidth * (1f - u.x) - Look.margin).coerceAtLeast(140.dp)
            WordCell(
                up = u,
                modifier = Modifier.offset {
                    IntOffset((u.x * wPx).roundToInt(), (u.y * hPx).roundToInt())
                }.widthIn(max = room),
                onTap = { answer(u, Grade.GOOD) },
                onDoubleTap = { answer(u, Grade.EASY) },
                onLongPress = { answer(u, Grade.AGAIN) },
                onPress = { u.held = it },
            )
        }
    }
}

@Composable private fun WordCell(
    up: UpWord,
    modifier: Modifier,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit,
    onPress: (Boolean) -> Unit,
) {
    val w = up.word
    val fade = remember(w.lang, w.word) { Animatable(0f) }
    LaunchedEffect(w.lang, w.word) {
        fade.animateTo(1f, tween(2_000, easing = LinearEasing))
    }
    val peek = up.touched || up.held
    val baseAlpha = if (peek) 1f else 0.6f
    Column(
        modifier
            .alpha(baseAlpha * fade.value)
            .pointerInput(w.lang, w.word) {
                detectTapGestures(
                    onPress = {
                        onPress(true)
                        try {
                            tryAwaitRelease()
                        } finally {
                            onPress(false)
                        }
                    },
                    onTap = { onTap() },
                    onDoubleTap = { onDoubleTap() },
                    onLongPress = { onLongPress() },
                )
            }
            .semantics(mergeDescendants = true) {
                contentDescription =
                    if (w.meaning.isBlank()) w.shown else "${w.shown}, ${w.meaning}"
                onClick("Good") { onTap(); true }
                customActions = listOf(
                    CustomAccessibilityAction("Again") { onLongPress(); true },
                    CustomAccessibilityAction("Easy") { onDoubleTap(); true },
                )
            }
            .testTag("word:${w.lang}:${w.word}")
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        // The word itself never wraps; only its meaning does.
        Text(
            w.shown, color = Palette.fg, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Light,
            softWrap = false, overflow = TextOverflow.Visible,
        )
        // Room for the meaning is always there, so nothing below shifts.
        Box(Modifier.heightIn(min = 20.dp).padding(top = 3.dp)) {
            if (peek) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (w.meaning.isNotBlank()) {
                        Text(
                            w.meaning,
                            color = Palette.dim,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (w.tags.isNotEmpty()) {
                        Text(
                            w.tags.joinToString(" · "),
                            color = Palette.faint,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            letterSpacing = 0.3.sp,
                        )
                    }
                }
            }
        }
    }
}
