package uk.ccez.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/*
 * z at a glance (owner, 2026-11-18: "a widget on my phone that has the
 * fleet status and the game build thing to send build requests"): the
 * owner's Mac mini serves a small status feed over their tailnet
 * (ccez-dash); home shows it as a strip, a tap opens the whole of it.
 * The address is the `dash.server` setting, never in this repo.
 */

data class DashMachine(val name: String, val state: String, val jobs: Int, val note: String)
data class DashGroup(val name: String, val jobs: Int, val who: String, val line: String)
/** Something that wants a look: [level] "red" or "amber". */
data class DashFlag(val level: String, val text: String)
data class DashGame(
    val id: String,
    val name: String,
    val fresh: Boolean,
    val line: String,
    val building: String,
    val failed: String,
    val notes: Int,
    val canBuild: Boolean,
    val whyNot: String,
)

data class DashFeed(
    val at: Long,
    val machines: List<DashMachine>,
    val running: Int,
    val groups: List<DashGroup>,
    val queued: Int,
    val oldestQueuedMin: Int,
    val attention: List<DashFlag>,
    val carbon: List<DashFlag>,
    val games: List<DashGame>,
    val check: DashFlag?,
    /** This week's Claude use, as fleet reads it. */
    val claude: DashFlag?,
    val aiWatchDate: String,
    val aiWatch: String,
    val quiet: Boolean,
    val canQuiet: Boolean,
) {
    companion object {
        /** The feed's JSON (ccez-dash's `/status`); missing parts read as empty. */
        fun parse(json: String): DashFeed {
            val o = JSONObject(json)
            fun <T> list(key: String, f: (JSONObject) -> T): List<T> {
                val a = o.optJSONArray(key) ?: JSONArray()
                return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(f) }
            }
            fun flag(j: JSONObject) = DashFlag(j.optString("level", "amber"), j.optString("text"))
            val running = o.optJSONObject("running") ?: JSONObject()
            val groupsJson = running.optJSONArray("groups") ?: JSONArray()
            val queue = o.optJSONObject("queue") ?: JSONObject()
            val ai = o.optJSONObject("ai_watch")
            val quiet = o.optJSONObject("quiet") ?: JSONObject()
            return DashFeed(
                at = o.optLong("at"),
                machines = list("machines") {
                    DashMachine(it.optString("name"), it.optString("state", "idle"), it.optInt("jobs"), it.optString("note"))
                },
                running = running.optInt("count"),
                groups = (0 until groupsJson.length()).mapNotNull { i ->
                    groupsJson.optJSONObject(i)?.let {
                        DashGroup(it.optString("name"), it.optInt("jobs"), it.optString("who"), it.optString("line"))
                    }
                },
                queued = queue.optInt("count"),
                oldestQueuedMin = queue.optInt("oldest_min"),
                attention = list("attention", ::flag),
                carbon = list("carbon", ::flag),
                games = list("games") {
                    DashGame(
                        id = it.optString("id"),
                        name = it.optString("name"),
                        fresh = it.optBoolean("fresh"),
                        line = it.optString("line"),
                        building = it.optString("building"),
                        failed = it.optString("failed"),
                        notes = it.optInt("notes"),
                        canBuild = it.optBoolean("can_build"),
                        whyNot = it.optString("why_not"),
                    )
                },
                check = o.optJSONObject("check")?.let { DashFlag(if (it.optBoolean("ok")) "green" else "red", it.optString("text")) },
                claude = o.optJSONObject("claude")?.let(::flag),
                aiWatchDate = ai?.optString("date").orEmpty(),
                aiWatch = ai?.optString("text").orEmpty(),
                quiet = quiet.optBoolean("on"),
                canQuiet = quiet.optBoolean("can"),
            )
        }

        /** A request's answer in words: `{"ok":…,"text":…}`. */
        fun answer(json: String): Pair<Boolean, String> {
            val o = JSONObject(json)
            return o.optBoolean("ok") to o.optString("text")
        }
    }
}

object Dash {
    val feed = MutableStateFlow<DashFeed?>(null)
    /** The last fetch failed: when, in ms (0: the feed is current). */
    val failedAt = MutableStateFlow(0L)
    /** The answer to the last request (a rebuild, quiet), for half a minute. */
    val said = MutableStateFlow("")
    @Volatile private var saidAt = 0L

    fun server(): String = (App.instance.settings.get("dash.server") as String).trim().trimEnd('/')

    private fun log(level: String, event: String, vararg ctx: Pair<String, Any?>) =
        App.instance.launcherLog.log(level, event, *ctx)

    private fun call(method: String, path: String): String {
        val c = URL(server() + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 4000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/json")
        if (method == "POST") {
            c.doOutput = true
            c.outputStream.use { }
        }
        try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299 && !body.trimStart().startsWith("{")) error("HTTP $code")
            return body
        } finally {
            c.disconnect()
        }
    }

    suspend fun refresh() {
        if (server().isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching { DashFeed.parse(call("GET", "/status")) }
                .onSuccess {
                    feed.value = it
                    failedAt.value = 0
                    if (said.value.isNotEmpty() && System.currentTimeMillis() - saidAt > 30_000) said.value = ""
                }
                .onFailure {
                    if (failedAt.value == 0L) log("warn", "dash-fetch-failed", "error" to it.javaClass.simpleName, "message" to it.message)
                    failedAt.value = System.currentTimeMillis()
                }
        }
    }

    /** Asks z to rebuild a game's Play build; the answer, in words, goes to [said]. */
    suspend fun rebuild(game: String) = request("/rebuild?game=" + URLEncoder.encode(game, "UTF-8"), "rebuild", game)

    suspend fun quiet(on: Boolean) = request("/quiet?on=" + (if (on) "1" else "0"), "quiet", on.toString())

    private suspend fun request(path: String, what: String, arg: String) {
        withContext(Dispatchers.IO) {
            val (ok, text) = runCatching { DashFeed.answer(call("POST", path)) }
                .getOrElse { false to "z didn't answer" }
            log(if (ok) "info" else "warn", "dash-$what", "arg" to arg, "answer" to text)
            saidAt = System.currentTimeMillis()
            said.value = text
        }
        refresh()
    }
}

/** The feed again on every return to home, and every 30 s while it shows. */
@Composable private fun DashPolling() {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                Dash.refresh()
                delay(30_000)
            }
        }
    }
}

private fun stateColor(state: String): Color = when (state) {
    "busy" -> Palette.accent
    "off" -> DashColors.red
    "held", "warn" -> DashColors.amber
    else -> Palette.faint
}

private object DashColors {
    val red = Color(0xFFF28B82)
    val amber = Color(0xFFFDD663)
    val green = Color(0xFF81C995)
}

private fun flagColor(level: String): Color = when (level) {
    "red" -> DashColors.red
    "green" -> DashColors.green
    else -> DashColors.amber
}

@Composable private fun Dot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

/** "z", "f 5": each machine with a dot, its jobs when busy. */
@Composable private fun MachineChips(machines: List<DashMachine>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        for (m in machines) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Dot(stateColor(m.state))
                Text(
                    if (m.jobs > 0) "${shortName(m.name)} ${m.jobs}" else shortName(m.name),
                    color = if (m.state == "off") Palette.dim else Palette.label, fontSize = Look.smallSize, maxLines = 1,
                )
            }
        }
    }
}

/** "studio-macbook-pro-4" → "studio": a machine as a chip has room for its first word. */
fun shortName(name: String): String = name.substringBefore('-').take(10)

/** "HLH 4, game tools 2 · 14 queued": what runs, by project, in one line. */
fun runningLine(f: DashFeed): String {
    val runs = f.groups.joinToString(", ") { "${it.name} ${it.jobs}" }
    val queued = if (f.queued > 0) "${f.queued} queued" else ""
    return listOf(runs, queued).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "Nothing running" }
}

private fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
    val min = ((now - at) / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> "now"
        min < 60 -> "$min min ago"
        else -> "${min / 60} h ago"
    }
}

/** A game's build in a few words: building, failed, fresh or behind. */
private fun gameWords(g: DashGame): Pair<String, Color> = when {
    g.building.isNotEmpty() -> "Building · ${g.building}" to Palette.accent
    g.failed.isNotEmpty() -> "Build failed" to DashColors.red
    else -> g.line to (if (g.fresh) Palette.dim else DashColors.amber)
}

@Composable private fun RebuildButton(g: DashGame, tag: String) {
    val scope = rememberCoroutineScope()
    var asked by remember(g.id, g.building) { mutableStateOf(false) }
    if (g.fresh || g.building.isNotEmpty() || !g.canBuild || asked) return
    IconAction(LauncherIcons.Refresh, "Rebuild ${g.name}", Modifier.testTag(tag), tint = Palette.accent) {
        asked = true
        scope.launch { Dash.rebuild(g.id) }
    }
}

/**
 * Home's strip: the machines, what wants a look, and each game whose Play
 * build is behind with a button that rebuilds it on z. Nothing without a
 * server set; a tap opens the whole feed.
 */
@Composable fun DashStrip(onOpen: () -> Unit) {
    val app = App.instance
    val tick by app.settings.version.collectAsState()
    val server = remember(tick) { Dash.server() }
    if (server.isEmpty()) return
    DashPolling()
    val feed by Dash.feed.collectAsState()
    val failedAt by Dash.failedAt.collectAsState()
    val said by Dash.said.collectAsState()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Look.margin, vertical = 4.dp)
            .clip(RoundedCornerShape(Look.radius)).background(Palette.cell)
            .clickable { onOpen() }
            .padding(start = Look.inset, end = 8.dp, top = 10.dp, bottom = 10.dp)
            .testTag("dash-strip"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val f = feed
        if (f == null) {
            Text(if (failedAt > 0) "z unreachable" else "z…", color = Palette.dim, fontSize = Look.smallSize)
            return@Column
        }
        MachineChips(f.machines)
        Text(
            if (failedAt > 0) "z unreachable · from ${ago(f.at)}" else runningLine(f),
            color = if (failedAt > 0) DashColors.amber else Palette.dim, fontSize = Look.smallSize,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        val flags = stripFlags(f)
        for (fl in flags.take(2)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(flagColor(fl.level), 6)
                Text(fl.text, color = Palette.label, fontSize = Look.smallSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (flags.size > 2) Text("${flags.size - 2} more", color = Palette.dim, fontSize = Look.smallSize)
        for (g in f.games) {
            // A fresh build in one quiet word; the rest as playbar says it.
            val (words, color) = if (g.fresh && g.building.isEmpty() && g.failed.isEmpty()) "fresh" to Palette.dim else gameWords(g)
            Row(Modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(g.name, color = Palette.fg, fontSize = Look.smallSize, fontWeight = FontWeight.Medium)
                Text(
                    "  $words", color = color, fontSize = Look.smallSize, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                RebuildButton(g, "dash-rebuild-${g.id}")
            }
        }
        if (said.isNotEmpty()) Text(said, color = Palette.dim, fontSize = Look.smallSize, maxLines = 2)
    }
}

/**
 * What the strip names, most pressing first: fleet's problems, the night's
 * check (its first line), failed Carbon runs as one line, a Claude week
 * nearly used.
 */
fun stripFlags(f: DashFeed): List<DashFlag> {
    val carbonRed = f.carbon.count { it.level == "red" }
    return f.attention +
        listOfNotNull(f.check?.takeIf { it.level == "red" }?.let { it.copy(text = it.text.lineSequence().first()) }) +
        (if (carbonRed > 0) listOf(DashFlag("red", if (carbonRed == 1) "1 Carbon run failed" else "$carbonRed Carbon runs failed")) else emptyList()) +
        listOfNotNull(f.claude?.takeIf { it.level == "red" || it.level == "amber" })
}

@Composable private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Look.margin, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Palette.dim, fontSize = Look.labelSize, fontWeight = FontWeight.Medium)
        content()
    }
}

@Composable private fun Line(text: String, color: Color = Palette.label, sub: String = "", dot: Color? = null) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (dot != null) Box(Modifier.padding(top = 6.dp)) { Dot(dot) }
        Column(Modifier.weight(1f)) {
            Text(text, color = color, fontSize = 15.sp)
            if (sub.isNotEmpty()) Text(sub, color = Palette.dim, fontSize = Look.smallSize)
        }
    }
}

/** The whole feed: machines, running, queue, what wants a look, games, the night's check, AI watch, quiet. */
@Composable fun DashScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    DashPolling()
    val feed by Dash.feed.collectAsState()
    val failedAt by Dash.failedAt.collectAsState()
    val said by Dash.said.collectAsState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(Palette.bg).testTag("dash")) {
        Row(
            Modifier.fillMaxWidth().padding(start = Look.margin - 12.dp, end = Look.margin, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(LauncherIcons.Back, "Back", onClick = onBack)
            Text("z", color = Palette.fg, fontSize = 22.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
            val f = feed
            Text(
                when {
                    f == null -> if (failedAt > 0) "unreachable" else ""
                    failedAt > 0 -> "unreachable · from ${ago(f.at)}"
                    else -> "updated ${ago(f.at)}"
                },
                color = if (failedAt > 0) DashColors.amber else Palette.dim, fontSize = Look.smallSize,
            )
        }
        val f = feed ?: return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            if (said.isNotEmpty()) Text(said, color = Palette.label, fontSize = Look.smallSize, modifier = Modifier.padding(horizontal = Look.margin, vertical = 6.dp))
            // Carbon's runs are listed in their own section: one line here.
            val carbonRed = f.carbon.count { it.level == "red" }
            val flags = f.attention + (if (carbonRed > 0) listOf(DashFlag("red", if (carbonRed == 1) "1 Carbon run failed" else "$carbonRed Carbon runs failed")) else emptyList())
            if (flags.isNotEmpty()) Section("Needs a look") {
                for (fl in flags) Line(fl.text, dot = flagColor(fl.level))
            }
            Section("Games") {
                if (f.games.isEmpty()) Line("No game with a Play build", Palette.dim)
                for (g in f.games) {
                    val (words, color) = gameWords(g)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g.name, color = Palette.fg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(words, color = color, fontSize = Look.smallSize)
                            if (g.failed.isNotEmpty()) Text(g.failed, color = Palette.dim, fontSize = Look.smallSize, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (!g.canBuild && g.whyNot.isNotEmpty() && !g.fresh) Text(g.whyNot, color = Palette.dim, fontSize = Look.smallSize)
                            if (g.notes > 0) Text(if (g.notes == 1) "1 unsent note" else "${g.notes} unsent notes", color = Palette.dim, fontSize = Look.smallSize)
                        }
                        RebuildButton(g, "dash-screen-rebuild-${g.id}")
                    }
                }
                f.check?.let { Line(it.text, dot = flagColor(it.level)) }
            }
            Section("Machines") {
                for (m in f.machines) {
                    val words = when (m.state) {
                        "off" -> "off line"
                        "busy" -> if (m.jobs == 1) "1 job" else "${m.jobs} jobs"
                        "held" -> "kept quiet"
                        else -> "idle"
                    }
                    Line("${m.name}  ·  $words", sub = m.note, dot = stateColor(m.state))
                }
            }
            if (f.groups.isNotEmpty() || f.queued > 0) Section("Running") {
                for (g in f.groups) Line("${g.name} · ${g.jobs}${if (g.who.isNotEmpty()) " · ${g.who}" else ""}", sub = g.line)
                if (f.queued > 0) Line("${f.queued} queued", Palette.dim, sub = if (f.oldestQueuedMin > 0) "oldest waits ${f.oldestQueuedMin} min" else "")
            }
            if (f.carbon.isNotEmpty()) Section("Carbon") {
                for (c in f.carbon) Line(c.text, dot = flagColor(c.level))
            }
            f.claude?.let { c -> Section("Claude") { Line(c.text, dot = flagColor(c.level)) } }
            if (f.aiWatch.isNotEmpty()) Section("AI watch${if (f.aiWatchDate.isNotEmpty()) " · ${f.aiWatchDate}" else ""}") {
                Text(f.aiWatch, color = Palette.label, fontSize = 14.sp)
            }
            if (f.canQuiet) Row(
                Modifier.fillMaxWidth().padding(horizontal = Look.margin, vertical = 12.dp).testTag("dash-quiet"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Keep z quiet", color = Palette.fg, fontSize = 16.sp)
                }
                Switch(
                    checked = f.quiet,
                    onCheckedChange = { on -> scope.launch { Dash.quiet(on) } },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Palette.fg,
                        checkedTrackColor = Palette.accent,
                        uncheckedThumbColor = Palette.dim,
                        uncheckedTrackColor = Palette.cell,
                    ),
                )
            }
        }
    }
}
