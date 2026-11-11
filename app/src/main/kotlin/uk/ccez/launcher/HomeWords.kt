package uk.ccez.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
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

/** A steadier spot for each word: its own hash, nudged off its neighbours. */
private fun spotFor(word: ScreenWord, up: List<UpWord>): Pair<Float, Float> {
    // Long words start further left, so they never run off the edge.
    val est = (word.shown.length * 28 / 1000f).coerceIn(0.1f, 0.6f)
    val span = ((0.92f - est) * 1000).toInt().coerceAtLeast(100)
    var h = (word.lang + "\t" + word.word).hashCode().toLong() and 0xFFFFFFFFL
    repeat(8) {
        val x = 0.04f + (h % span) / 1000f
        val y = 0.05f + ((h / span) % 820) / 1000f
        if (up.none { abs(it.x - x) < 0.22f && abs(it.y - y) < 0.13f }) return x to y
        h = h * 2654435761L and 0xFFFFFFFFL
    }
    return 0.04f + (h % span) / 1000f to 0.05f + ((h / span) % 820) / 1000f
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
                        val (x, y) = spotFor(w, placed)
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

    BoxWithConstraints(modifier.testTag("home-words")) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        for (u in up.value) {
            WordCell(
                up = u,
                modifier = Modifier.offset {
                    IntOffset((u.x * wPx).roundToInt(), (u.y * hPx).roundToInt())
                },
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
    val baseAlpha = if (peek) 1f else 0.55f
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
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(w.shown, color = Palette.fg, fontSize = 21.sp, lineHeight = 24.sp)
        // The meaning's row is always there, so the word never jumps.
        Box(Modifier.height(22.dp).padding(top = 2.dp)) {
            if (peek) {
                Row {
                    for (tag in w.tags) {
                        Text(
                            tag,
                            color = Palette.dim,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Palette.cell)
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                    if (w.meaning.isNotBlank()) {
                        Text(
                            w.meaning,
                            color = Palette.dim,
                            fontSize = 14.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(start = if (w.tags.isEmpty()) 0.dp else 6.dp),
                        )
                    }
                }
            }
        }
    }
}
