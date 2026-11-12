package uk.ccez.launcher

import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * The layout over adb, for agents (`scripts/launcher.ts apps|pins|pin|…`):
 * Control hands each call here with its arguments as one JSON object.
 * Every change goes through LayoutStore.update (the open screen redraws at
 * once), is checked before anything is written (LayoutOps throws, nothing
 * changes) and is logged as an "agent" event.
 */
object AgentCommands {
    val methods = setOf(
        "apps", "pins", "hidden", "pin", "unpin", "move", "hide", "unhide",
        "folder", "unfolder", "rename-folder", "gate", "open", "home", "drawer",
        "shortcuts", "pin-shortcut",
    )

    /** MainActivity reads this extra: which screen to show. */
    const val EXTRA_SCREEN = "uk.ccez.launcher.SCREEN"

    fun run(app: App, method: String, args: JSONObject): String {
        val result = when (method) {
            "apps" -> apps(app, args.optString("q"))
            "pins" -> pins(app)
            "hidden" -> hidden(app)
            "pin" -> {
                val a = findApp(app, args.str("app"))
                var at = -1
                app.layout.update { d -> at = LayoutOps.pin(d, a, args.intOrNull("at")) }
                "pinned ${a.label} at $at"
            }
            "unpin" -> {
                val q = args.str("pin")
                var said = ""
                app.layout.update { d ->
                    val spot = LayoutOps.locate(d, q, labelOf(app), inFolders = true)
                    val label = when (spot) {
                        is Spot.Row -> LayoutOps.pinLabel(d, d.pins[spot.index], labelOf(app))
                        is Spot.InFolder -> LayoutOps.pinLabel(d, d.folders.getValue(spot.folderId).items[spot.index], labelOf(app))
                    }
                    val from = (spot as? Spot.InFolder)?.let { d.folders[it.folderId]?.name?.ifBlank { null } ?: it.folderId }
                    LayoutOps.unpin(d, spot)
                    said = if (from != null) "unpinned $label from $from" else "unpinned $label"
                }
                said
            }
            "move" -> {
                val q = args.str("pin")
                val to = args.intOrNull("to") ?: throw AgentError("move needs a position to move to")
                var said = ""
                app.layout.update { d ->
                    val from = (LayoutOps.locate(d, q, labelOf(app)) as Spot.Row).index
                    val label = LayoutOps.pinLabel(d, d.pins[from], labelOf(app))
                    LayoutOps.move(d, from, to)
                    said = "moved $label from $from to $to"
                }
                said
            }
            "hide" -> {
                val a = findApp(app, args.str("app"))
                app.layout.update { d -> LayoutOps.hide(d, a) }
                "hid ${a.label}"
            }
            "unhide" -> {
                val q = args.str("app")
                // Hidden apps only, by the same matching (an uninstalled one by key).
                val hidden = app.layout.data.value.hidden.map { key ->
                    app.apps.byKey(key)?.let { ref(app, it) } ?: AppRef(AppId.parse(key), key, "gone")
                }
                val a = Match.find(q, hidden, { it.label }, { listOf(it.key, it.id.pkg) }).one("hidden app", q) { it.describe() }
                app.layout.update { d -> LayoutOps.unhide(d, a.key, a.label) }
                "unhid ${a.label}"
            }
            "folder" -> {
                val name = args.str("name")
                val list = args.optJSONArray("apps") ?: JSONArray()
                val wanted = (0 until list.length()).map { findApp(app, list.getString(it)) }
                var said = ""
                app.layout.update { d ->
                    val (f, made) = LayoutOps.folder(d, name, wanted)
                    said = (if (made) "made folder ${f.name}" else "added to folder ${f.name}") +
                        ": " + f.items.joinToString(", ") { LayoutOps.pinLabel(d, it, labelOf(app)) }
                }
                said
            }
            "unfolder" -> {
                val q = args.str("name")
                var said = ""
                app.layout.update { d ->
                    val f = LayoutOps.findFolder(d, q)
                    val items = LayoutOps.unfolder(d, f)
                    said = "removed folder ${f.name.ifBlank { f.id }}; ${items.size} back in the row"
                }
                said
            }
            "rename-folder" -> {
                val q = args.str("old")
                val name = args.str("new")
                var said = ""
                app.layout.update { d ->
                    val f = LayoutOps.findFolder(d, q)
                    val old = f.name.ifBlank { f.id }
                    LayoutOps.renameFolder(d, f, name)
                    said = "renamed folder $old to ${name.trim()}"
                }
                said
            }
            "gate" -> {
                val a = findApp(app, args.str("app"))
                val on = when (args.str("on").lowercase()) {
                    "on", "true", "1" -> true
                    "off", "false", "0" -> false
                    else -> throw AgentError("gate takes on or off")
                }
                var changed = false
                app.layout.update { d -> changed = LayoutOps.gate(d, a.key, on) }
                "word first ${if (on) "on" else "off"} for ${a.label}" + if (changed) "" else " (already)"
            }
            "open" -> {
                val a = findApp(app, args.str("app"))
                // Straight to the app: an agent's open is not a word moment.
                app.apps.launch(app.apps.byId(a.id) ?: throw AgentError("${a.label} isn't installed"))
                "opened ${a.label}"
            }
            "home", "drawer" -> {
                app.startActivity(
                    Intent(app, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(EXTRA_SCREEN, method),
                )
                "showing $method"
            }
            "shortcuts" -> {
                val a = findApp(app, args.str("app"))
                shortcutsJson(app, a)
            }
            "pin-shortcut" -> {
                val a = findApp(app, args.str("app"))
                val q = args.str("shortcut")
                val si = Match.find(q, shortcutsOf(app, a), { it.label() }, { listOf(it.id) })
                    .one("shortcut", q) { "${it.label()}  ${it.id}" }
                if (!si.isEnabled) throw AgentError("${si.label()} is turned off by ${a.label}")
                val pin = Pin.Shortcut(a.id.pkg, si.id, a.id.user, si.label())
                var at = -1
                app.layout.update { d -> at = LayoutOps.pinShortcut(d, pin, args.intOrNull("at")) }
                app.apps.pinShortcut(a.id.pkg, si.id, app.apps.userForSerial(a.id.user))
                "pinned ${si.label()} (${a.label}) at $at"
            }
            else -> throw AgentError("no command called $method")
        }
        if (method !in setOf("apps", "pins", "hidden", "shortcuts")) {
            app.launcherLog.log("info", "agent", "cmd" to method, "result" to result)
        }
        return result
    }

    private fun JSONObject.str(name: String): String =
        optString(name).trim().ifEmpty { throw AgentError("missing $name") }

    private fun JSONObject.intOrNull(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        return optString(name).trim().toIntOrNull() ?: throw AgentError("$name should be a number")
    }

    private fun profile(app: App, e: AppEntry): String = when {
        !e.otherProfile -> "main"
        e.user == app.apps.privateUser -> "private"
        else -> "work"
    }

    private fun ref(app: App, e: AppEntry) = AppRef(e.id, e.label, profile(app, e))

    private fun refs(app: App): List<AppRef> {
        if (!app.apps.loaded.value) throw AgentError("the app list is still loading; try again")
        return app.apps.apps.value.map { ref(app, it) }
    }

    /** Only installed apps match: a pin for an app that isn't there can't be made. */
    private fun findApp(app: App, q: String): AppRef = LayoutOps.findApp(refs(app), q)

    private fun labelOf(app: App): (AppId) -> String? = { id -> app.apps.byId(id)?.label }

    private fun apps(app: App, q: String): String {
        val d = app.layout.data.value
        val nq = normalized(q)
        val out = JSONArray()
        for (e in app.apps.apps.value) {
            if (nq.isNotEmpty() && listOf(e.label, e.key).none { normalized(it).contains(nq) }) continue
            out.put(
                JSONObject()
                    .put("label", e.label)
                    .put("key", e.key)
                    .put("pkg", e.id.pkg)
                    .put("profile", profile(app, e))
                    .put("hidden", e.key in d.hidden)
                    .put("pinned", LayoutOps.rowIndexOf(d, e.id).takeIf { it >= 0 } ?: JSONObject.NULL)
                    .put("folder", LayoutOps.folderOf(d, e.id)?.let { it.name.ifBlank { it.id } } ?: JSONObject.NULL)
                    .put("gated", e.key in d.gates)
                    .put("system", e.system),
            )
        }
        return out.toString(2)
    }

    private fun pinJson(app: App, d: LayoutData, p: Pin): JSONObject {
        val o = JSONObject().put("label", LayoutOps.pinLabel(d, p, labelOf(app)))
        return when (p) {
            is Pin.App -> o.put("type", "app").put("key", p.id.key()).put("installed", app.apps.byId(p.id) != null)
            is Pin.Shortcut -> o.put("type", "shortcut").put("pkg", p.pkg).put("id", p.shortcutId)
                .put("app", app.apps.apps.value.firstOrNull { it.id.pkg == p.pkg && it.id.user == p.user }?.label ?: p.pkg)
            is Pin.FolderRef -> o.put("type", "folder").put("id", p.folderId)
                .put("items", JSONArray().apply { d.folders[p.folderId]?.items?.forEach { put(pinJson(app, d, it)) } })
        }
    }

    private fun pins(app: App): String {
        val d = app.layout.data.value
        val out = JSONArray()
        d.pins.forEachIndexed { i, p -> out.put(pinJson(app, d, p).put("index", i)) }
        return out.toString(2)
    }

    private fun hidden(app: App): String {
        val out = JSONArray()
        for (key in app.layout.data.value.hidden.sorted()) {
            val e = app.apps.byKey(key)
            out.put(JSONObject().put("label", e?.label ?: JSONObject.NULL).put("key", key).put("installed", e != null))
        }
        return out.toString(2)
    }

    private fun ShortcutInfo.label(): String = shortLabel?.toString()?.ifBlank { null } ?: longLabel?.toString() ?: id

    private fun shortcutsOf(app: App, a: AppRef): List<ShortcutInfo> {
        val la = app.getSystemService(LauncherApps::class.java)
        if (la?.hasShortcutHostPermission() != true) throw AgentError("app shortcuts need ccez-launcher as the home app")
        val e = app.apps.byId(a.id) ?: throw AgentError("${a.label} isn't installed")
        return runBlocking { app.apps.shortcuts(e) }
    }

    private fun shortcutsJson(app: App, a: AppRef): String {
        val out = JSONArray()
        for (si in shortcutsOf(app, a)) {
            out.put(
                JSONObject()
                    .put("id", si.id)
                    .put("label", si.label())
                    .put("long_label", si.longLabel?.toString() ?: JSONObject.NULL)
                    .put("kind", when { si.isDynamic -> "dynamic"; si.isDeclaredInManifest -> "manifest"; else -> "pinned" })
                    .put("enabled", si.isEnabled),
            )
        }
        return out.toString(2)
    }
}
