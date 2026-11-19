package uk.ccez.launcher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/*
 * Ask on home (owner, 2026-11-18: "an AI chat that I can do right on the
 * home screen that resets after a certain point of inactivity"): the answer
 * streams into a panel that grows from the Ask field; follow-ups continue
 * the thread. ccez-llm answers through its AskService (a bound service only
 * the owner's own apps may bind) and keeps every exchange in its history;
 * this app holds no key and no model code. The panel resets by itself
 * after `ask.reset_minutes` without activity, when the screen goes off, on
 * back, or on a swipe down.
 */

/** One side of the thread: the question, or the answer as it streams. */
data class AskTurn(val user: Boolean, val text: String, val cited: List<String> = emptyList(), val failed: String = "", val done: Boolean = true)

/** The thread's pure rules, tested on the JVM. */
object AskThread {
    /** What ccez-llm is sent: every finished turn, failed answers left out. */
    fun messages(turns: List<AskTurn>): String {
        val a = JSONArray()
        for (t in turns) {
            if (t.text.isBlank() || t.failed.isNotEmpty()) continue
            if (!t.user && !t.done) continue
            a.put(JSONObject().put("role", if (t.user) "user" else "assistant").put("content", t.text))
        }
        return a.toString()
    }

    /** Asked, now idle this long (ms), with nothing streaming: time to clear. */
    fun stale(turns: List<AskTurn>, idleMs: Long, resetMinutes: Int): Boolean =
        turns.isNotEmpty() && turns.none { !it.done } && idleMs >= resetMinutes * 60_000L

    /** "fr.wiktionary.org": a source as a short label. */
    fun host(url: String): String = runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: url

    /** A run of an answer and how it shows: markdown's bold, italics and code. */
    data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

    private val MARKS = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])")

    /**
     * An answer's light markdown as runs to style: `**bold**`, `*italic*`,
     * `` `code` ``, and "- " or "* " list lines as bullets. Anything else
     * shows as written.
     */
    fun spans(text: String): List<Span> {
        val lines = text.lines().joinToString("\n") { l ->
            val t = l.trimStart()
            if (t.startsWith("- ") || t.startsWith("* ")) "• " + t.drop(2) else l
        }
        val out = ArrayList<Span>()
        var at = 0
        for (m in MARKS.findAll(lines)) {
            if (m.range.first > at) out += Span(lines.substring(at, m.range.first))
            when {
                m.groupValues[1].isNotEmpty() -> out += Span(m.groupValues[1], bold = true)
                m.groupValues[2].isNotEmpty() -> out += Span(m.groupValues[2], code = true)
                else -> out += Span(m.groupValues[3], italic = true)
            }
            at = m.range.last + 1
        }
        if (at < lines.length) out += Span(lines.substring(at))
        return out
    }

    /** A thread id ccez-llm accepts (its chat id is `home-<this>`). */
    fun newId(): String = java.util.UUID.randomUUID().toString()
}

object Ask {
    private const val ASK = 1
    private const val DELTA = 2
    private const val DONE = 3
    private const val FAILED = 4
    /** ccez-llm's service: the release, else a debug build (`.debug`, the emulator's). */
    private val SERVICES = listOf("studio.ccez.app", "studio.ccez.app.debug").map { ComponentName(it, "studio.ccez.app.AskService") }

    val turns = MutableStateFlow<List<AskTurn>>(emptyList())
    /** The thread's id while there is one. */
    @Volatile var chat = AskThread.newId()
        private set
    @Volatile var lastActive = 0L
        private set
    private var asking = 0L
    private var service: Messenger? = null
    private var bound = false
    private var pending: (() -> Unit)? = null

    private fun log(level: String, event: String, vararg ctx: Pair<String, Any?>) = App.instance.launcherLog.log(level, event, *ctx)

    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(m: Message) {
            val id = m.data.getLong("id")
            if (id != asking) return
            val text = m.data.getString("text").orEmpty()
            lastActive = System.currentTimeMillis()
            when (m.what) {
                DELTA -> update { it.copy(text = it.text + text) }
                DONE -> {
                    val cited = runCatching { JSONArray(m.data.getString("cited") ?: "[]") }.getOrNull()
                    val urls = (0 until (cited?.length() ?: 0)).mapNotNull { cited?.optString(it)?.takeIf { u -> u.startsWith("http") } }
                    update { it.copy(text = text.ifEmpty { it.text }, cited = urls, done = true) }
                    log("info", "ask-answered", "chars" to text.length, "sources" to urls.size)
                }
                FAILED -> {
                    update { it.copy(failed = text.ifEmpty { "No answer." }, done = true) }
                    log("warn", "ask-failed", "why" to text)
                }
            }
        }
    })

    /** The streaming answer (the last turn) changed. */
    private fun update(f: (AskTurn) -> AskTurn) {
        val now = turns.value
        val last = now.lastOrNull() ?: return
        if (last.user) return
        turns.value = now.dropLast(1) + f(last)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            pending?.invoke()
            pending = null
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            update { if (it.done) it else it.copy(failed = "ccez-llm stopped before answering.", done = true) }
        }
    }

    /**
     * Asks [question] in the thread. False when ccez-llm can't answer here
     * (not installed, too old, or not allowed): the caller hands the
     * question to ccez-llm the old way.
     */
    fun send(ctx: Context, question: String): Boolean {
        val q = question.trim()
        if (q.isEmpty() || turns.value.any { !it.done }) return true
        if (!bound) {
            for (c in SERVICES) {
                bound = runCatching {
                    ctx.applicationContext.bindService(Intent().setComponent(c), connection, Context.BIND_AUTO_CREATE)
                }.onFailure { log("warn", "ask-bind-failed", "service" to c.packageName, "error" to it.javaClass.simpleName) }.getOrDefault(false)
                if (bound) break
                runCatching { ctx.applicationContext.unbindService(connection) }
            }
            if (!bound) {
                log("info", "ask-no-service", "chars" to q.length)
                return false
            }
        }
        turns.value = turns.value + AskTurn(user = true, text = q) + AskTurn(user = false, text = "", done = false)
        lastActive = System.currentTimeMillis()
        val go = { deliver() }
        if (service != null) go() else pending = go
        return true
    }

    /** The last question again, after a failed answer. */
    fun retry() {
        val now = turns.value
        if (now.size < 2 || now.last().failed.isEmpty()) return
        turns.value = now.dropLast(1) + AskTurn(user = false, text = "", done = false)
        lastActive = System.currentTimeMillis()
        if (service != null) deliver() else pending = { deliver() }
    }

    private fun deliver() {
        val s = service ?: return
        asking += 1
        val m = Message.obtain(null, ASK)
        m.replyTo = incoming
        m.data = Bundle().apply {
            putLong("id", asking)
            putString("chat", chat)
            putString("messages", AskThread.messages(turns.value.dropLast(1)))
        }
        runCatching { s.send(m) }.onFailure {
            update { t -> t.copy(failed = "ccez-llm didn't take the question.", done = true) }
        }
    }

    /** Home plain again: the thread goes (ccez-llm keeps it), a new one starts next time. */
    fun clear(ctx: Context, why: String) {
        if (turns.value.isEmpty()) return
        log("info", "ask-reset", "why" to why, "turns" to turns.value.size)
        turns.value = emptyList()
        chat = AskThread.newId()
        asking += 1 // an answer still coming is dropped
        pending = null
        if (bound) {
            runCatching { ctx.applicationContext.unbindService(connection) }
            bound = false
            service = null
        }
    }

    /** The thread in ccez-llm (it takes it into its history first), and home plain. */
    fun carryOn(ctx: Context) {
        val id = chat
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("ccez-llm://chat?id=home-$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { log("warn", "ask-carry-on-failed", "error" to it.javaClass.simpleName) }
        clear(ctx, "carried on")
    }
}

/** An answer with its markdown shown, not written out. */
private fun styled(text: String): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    for (s in AskThread.spans(text)) {
        pushStyle(
            androidx.compose.ui.text.SpanStyle(
                fontWeight = if (s.bold) FontWeight.SemiBold else null,
                fontStyle = if (s.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                fontFamily = if (s.code) androidx.compose.ui.text.font.FontFamily.Monospace else null,
            ),
        )
        append(s.text)
        pop()
    }
}

/**
 * The answer panel, drawn only once something was asked. Home puts it over
 * the Continue strip and lets it grow up to just under the date
 * ([maxHeight], owner 2026-11-18). Resets after the idle time, when the
 * screen goes off, on back, and on a swipe down on it.
 */
@Composable fun AskPanel(maxHeight: Dp) {
    val app = App.instance
    val ctx = LocalContext.current
    val turns by Ask.turns.collectAsState()
    if (turns.isEmpty()) return
    val settingsTick by app.settings.version.collectAsState()
    val resetMinutes = androidx.compose.runtime.remember(settingsTick) { app.settings.number("ask.reset_minutes") }
    BackHandler { Ask.clear(ctx, "back") }
    // Idle: checked every 15 s while the panel is up.
    LaunchedEffect(resetMinutes) {
        while (true) {
            delay(15_000)
            if (AskThread.stale(Ask.turns.value, System.currentTimeMillis() - Ask.lastActive, resetMinutes)) {
                Ask.clear(ctx, "idle $resetMinutes min")
            }
        }
    }
    // The screen going off (locking): plain home when it comes back.
    DisposableEffect(Unit) {
        val off = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = Ask.clear(c, "screen off")
        }
        ctx.registerReceiver(off, IntentFilter(Intent.ACTION_SCREEN_OFF))
        onDispose { runCatching { ctx.unregisterReceiver(off) } }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(turns.lastOrNull()?.text?.length, turns.size) { scroll.animateScrollTo(scroll.maxValue) }
    Column(
        Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(horizontal = Look.margin, vertical = 4.dp)
            .clip(RoundedCornerShape(Look.radius)).background(Palette.cell)
            .pointerInput(Unit) {
                var pulled = 0f
                detectVerticalDragGestures(
                    onDragStart = { pulled = 0f },
                    onVerticalDrag = { _, dy -> pulled += dy },
                    onDragEnd = { if (pulled > 120f) Ask.clear(ctx, "swipe") },
                )
            }
            .testTag("ask-panel"),
    ) {
        Column(
            // As tall as the answer, up to what's left over the footer.
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(scroll)
                .padding(start = Look.inset, end = Look.inset, top = 12.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (t in turns) {
                if (t.user) {
                    Text(t.text, color = Palette.dim, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
                    continue
                }
                when {
                    t.failed.isNotEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.failed, color = Palette.label, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text(
                            "Retry", color = Palette.accent, fontSize = 15.sp,
                            modifier = Modifier.clickable { Ask.retry() }.padding(8.dp).testTag("ask-retry"),
                        )
                    }
                    t.text.isEmpty() -> Text("…", color = Palette.dim, fontSize = 16.sp)
                    else -> Text(styled(t.text), color = Palette.fg, fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.testTag("ask-answer"))
                }
                if (t.cited.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (u in t.cited.take(3)) {
                        Text(
                            AskThread.host(u), color = Palette.accent, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable {
                                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            },
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = Look.inset - 8.dp, end = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Carry on in ccez-llm", color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { Ask.carryOn(ctx) }.padding(8.dp).testTag("ask-carry-on"),
            )
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            IconAction(LauncherIcons.Close, "Close", Modifier.testTag("ask-close"), tint = Palette.faint) { Ask.clear(ctx, "closed") }
        }
    }
}
