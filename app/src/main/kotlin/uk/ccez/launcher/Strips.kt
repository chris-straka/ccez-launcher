package uk.ccez.launcher

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.ccez.keys.core.WordStatus

/**
 * The Stage 6 strips: today's quiet numbers, the latest copy as a word,
 * carrying on in ccez-tube, and asking ccez-llm. Each shows only when
 * it has something to say.
 */
@Composable fun TodayStrip() {
    val reviewed by Today.reviewed.collectAsState()
    if (reviewed <= 0) return
    Text(
        if (reviewed == 1) "1 word today" else "$reviewed words today",
        color = Palette.faint, fontSize = Look.smallSize,
        modifier = Modifier.padding(horizontal = Look.margin, vertical = 4.dp).testTag("today"),
    )
}

@Composable fun ClipStrip() {
    val app = App.instance
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    var dismissed by remember { mutableStateOf("") }
    val clip by produceState(LastCopy.text, tick) {
        value = runCatching { LastCopy.read(ctx) }.getOrDefault("")
    }
    val text = clip.trim()
    if (text.isEmpty() || text.length > 48 || '\n' in text || text == dismissed) return
    if (text.split(Regex("\\s+")).size > 3) return
    val hit by produceState<WordHit?>(null, text) {
        value = withContext(Dispatchers.IO) {
            val e = Words.engineOrNull() ?: return@withContext null
            val langs = runCatching { e.settingStrings("languages.learning") }.getOrDefault(emptyList())
            var found: WordHit? = null
            for (lang in langs) {
                val card = runCatching { e.wordCard(lang, text, "") }.getOrNull()
                if (card != null) {
                    found = WordHit(lang, text, card)
                    break
                }
            }
            found
        }
    }
    val word = hit ?: return
    val head = word.card.lemma.ifEmpty { word.card.word }
    var saved by remember(text) { mutableStateOf(word.card.status == WordStatus.LEARNING) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Look.margin, vertical = 4.dp)
            .clip(RoundedCornerShape(Look.radius)).background(Palette.cell)
            .padding(start = Look.inset, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .testTag("clip-strip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                head, color = Palette.fg, fontSize = Look.bodySize, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            word.card.meanings.firstOrNull()?.let {
                Text(it, color = Palette.dim, fontSize = Look.smallSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (saved) {
            IconAction(LauncherIcons.Check, "Learning", Modifier.testTag("clip-save"), tint = Palette.dim, onClick = null)
        } else {
            IconAction(LauncherIcons.Add, "Save", Modifier.testTag("clip-save"), tint = Palette.accent) {
                Words.setStatus(ctx, word.lang, head, WordStatus.LEARNING, app.scope)
                WordSync.soon(ctx)
                saved = true
            }
        }
        IconAction(LauncherIcons.Close, "Dismiss", Modifier.testTag("clip-dismiss"), tint = Palette.faint) {
            dismissed = text
        }
    }
}

/**
 * Carry on in the app used last (owner, 2026-11-14: it only ever offered
 * ccez-tube): Android's usage record when the launcher may read it
 * (`appops set uk.ccez.launcher GET_USAGE_STATS allow`), else the last app
 * opened from here. Read again each time home comes back.
 */
@Composable fun ContinueStrip(onLaunch: (AppEntry) -> Unit) {
    val app = App.instance
    val ctx = LocalContext.current
    val apps by app.apps.apps.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val last by produceState<AppEntry?>(null, apps, tick) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { LastApp.find(ctx, apps) }
    }
    val entry = last ?: return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Look.margin, vertical = 4.dp)
            .clip(RoundedCornerShape(Look.radius)).background(Palette.cell)
            .clickable { onLaunch(entry) }
            .heightIn(min = Look.field)
            .padding(horizontal = Look.inset, vertical = 10.dp)
            .testTag("continue-strip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIconImage(entry, Modifier.size(26.dp))
        Text(
            AppNames.nameFor(entry), color = Palette.fg, fontSize = 15.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Text("Continue", color = Palette.dim, fontSize = Look.smallSize)
    }
}

/** The app used last, among the launchable ones (never this launcher or the keyboard). */
object LastApp {
    private val skip = setOf("uk.ccez.launcher", "uk.ccez.keys", "com.android.systemui", "com.sec.android.app.launcher")

    fun remember(ctx: android.content.Context, pkg: String) {
        ctx.getSharedPreferences("continue", android.content.Context.MODE_PRIVATE).edit().putString("last", pkg).apply()
    }

    fun find(ctx: android.content.Context, apps: List<AppEntry>): AppEntry? {
        val mine = apps.filter { !it.otherProfile && it.id.pkg !in skip }
        val byPkg = mine.associateBy { it.id.pkg }
        used(ctx, byPkg.keys)?.let { pkg -> byPkg[pkg]?.let { return it } }
        val opened = ctx.getSharedPreferences("continue", android.content.Context.MODE_PRIVATE).getString("last", null)
        return opened?.let { byPkg[it] } ?: byPkg["uk.ccez.tube"]
    }

    /**
     * The newest package in [resumed] (oldest first) that has a drawer
     * entry: Recents, permission dialogs and the share sheet come to the
     * front too and would hide the app under them (review 2026-11-14).
     */
    fun lastLaunchable(resumed: List<String>, launchable: Set<String>): String? =
        resumed.lastOrNull { it in launchable && it !in skip }

    /**
     * The last launchable app brought to the front in the past six hours,
     * from Android's usage record; null without access or with none.
     * Six hours, not days: this runs on every return to home.
     */
    private fun used(ctx: android.content.Context, launchable: Set<String>): String? = runCatching {
        val ops = ctx.getSystemService(android.app.AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
        if (mode != android.app.AppOpsManager.MODE_ALLOWED) return null
        val usm = ctx.getSystemService(android.app.usage.UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 6 * 3600_000L, now)
        val e = android.app.usage.UsageEvents.Event()
        val resumed = ArrayList<String>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) resumed.add(e.packageName)
        }
        lastLaunchable(resumed, launchable)
    }.getOrNull()
}

/** Ask ccez-llm, through its `ccez-llm://send?text=` deep link. */
@Composable fun LlmAskStrip() {
    val app = App.instance
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var text by remember { mutableStateOf("") }
    // The answer panel is home's (HomeScreen.kt, over the strips).
    // The thread gone (reset, carried on, closed): the field lets go too, or
    // the keyboard comes back up on the way back to home.
    val asked by Ask.turns.collectAsState()
    androidx.compose.runtime.LaunchedEffect(asked.isEmpty()) {
        if (asked.isEmpty()) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    PillField(
        value = text,
        onValueChange = { text = it },
        placeholder = "Ask",
        modifier = Modifier.padding(horizontal = Look.margin, vertical = 4.dp).testTag("llm-ask"),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = {
            val q = text.trim()
            // Answered here by ccez-llm's AskService; the old way (ccez-llm
            // opens with the question) when it can't.
            if (q.isNotEmpty() && Ask.send(ctx, q)) {
                text = ""
                keyboard?.hide()
            } else if (q.isNotEmpty()) {
                // Home stays composed under ccez-llm: a field left focused
                // raised the keyboard again on the way back.
                focus.clearFocus()
                keyboard?.hide()
                runCatching {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("ccez-llm://send?text=${Uri.encode(q)}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onSuccess { text = "" }
                    // Not sent (ccez-llm not installed): the question stays.
                    .onFailure { app.launcherLog.log("warn", "llm-ask-failed", "error" to it.toString(), "chars" to q.length) }
            }
        }),
    )
}

/**
 * The latest copy, read only when something new was copied: Android says
 * "ccez-launcher pasted from your clipboard" on every read, and home came
 * back many times a minute (2026-11-11). The clip's description (its time,
 * its kind) is read freely; the text only once per copy, and never what
 * isn't text.
 */
private object LastCopy {
    var text = ""
    private var seen = -1L

    fun read(ctx: android.content.Context): String {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return ""
        val desc = cm.primaryClipDescription ?: return "".also { text = ""; seen = -1L }
        val at = desc.timestamp
        if (at == seen) return text
        seen = at
        text = if (desc.hasMimeType("text/*")) {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
        } else {
            ""
        }
        return text
    }
}
