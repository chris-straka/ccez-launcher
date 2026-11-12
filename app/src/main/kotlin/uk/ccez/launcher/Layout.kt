package uk.ccez.launcher

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** An app on any profile, as stored: package, activity, user serial. */
data class AppId(val pkg: String, val cls: String, val user: Long) {
    fun key(): String = "$pkg/$cls@$user"

    companion object {
        fun parse(s: String): AppId {
            val at = s.lastIndexOf('@')
            require(at > 0) { "bad app id: $s" }
            val user = s.substring(at + 1).toLongOrNull() ?: throw IllegalArgumentException("bad app id: $s")
            val slash = s.indexOf('/')
            require(slash > 0) { "bad app id: $s" }
            return AppId(s.substring(0, slash), s.substring(slash + 1, at), user)
        }
    }
}

/** One slot on home: an app, a pinned shortcut, or a folder. */
sealed class Pin {
    data class App(val id: AppId) : Pin()
    data class Shortcut(val pkg: String, val shortcutId: String, val user: Long, val label: String) : Pin()
    data class FolderRef(val folderId: String) : Pin()

    fun toJson(): JSONObject = when (this) {
        is App -> JSONObject().put("t", "app").put("pkg", id.pkg).put("cls", id.cls).put("user", id.user)
        is Shortcut -> JSONObject().put("t", "shortcut").put("pkg", pkg).put("id", shortcutId).put("user", user).put("label", label)
        is FolderRef -> JSONObject().put("t", "folder").put("id", folderId)
    }

    companion object {
        fun parse(o: JSONObject): Pin = when (val t = o.getString("t")) {
            "app" -> App(AppId(o.getString("pkg"), o.getString("cls"), o.optLong("user")))
            "shortcut" -> Shortcut(o.getString("pkg"), o.getString("id"), o.optLong("user"), o.optString("label"))
            "folder" -> FolderRef(o.getString("id"))
            else -> error("bad pin type: $t")
        }
    }
}

data class Folder(val id: String, var name: String, val items: MutableList<Pin>)

/** A hosted widget (Stage 6): its system id and provider, in home order. */
data class WidgetEntry(val appWidgetId: Int, val pkg: String, val cls: String) {
    fun toJson(): JSONObject =
        JSONObject().put("id", appWidgetId).put("pkg", pkg).put("cls", cls)

    companion object {
        fun parse(o: JSONObject): WidgetEntry =
            WidgetEntry(o.getInt("id"), o.getString("pkg"), o.getString("cls"))
    }
}

/**
 * The layout as one JSON file (files/launcher/layout.json): pins in
 * order, folders, hidden apps (the hidden space's; shown nowhere else,
 * in no search), and the Stage 4 word-before-app list. Validated whole
 * before a restore writes anything.
 */
data class LayoutData(
    val pins: MutableList<Pin> = mutableListOf(),
    val folders: MutableMap<String, Folder> = mutableMapOf(),
    val hidden: MutableSet<String> = mutableSetOf(),
    val gates: MutableSet<String> = mutableSetOf(),
    var nextFolder: Int = 1,
    val widgets: MutableList<WidgetEntry> = mutableListOf(),
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("pins", JSONArray().apply { pins.forEach { put(it.toJson()) } })
        val fs = JSONObject()
        for ((id, f) in folders) {
            fs.put(id, JSONObject().put("name", f.name)
                .put("items", JSONArray().apply { f.items.forEach { put(it.toJson()) } }))
        }
        o.put("folders", fs)
        o.put("hidden", JSONArray(hidden.toList()))
        o.put("gates", JSONArray(gates.toList()))
        o.put("nextFolder", nextFolder)
        o.put("widgets", JSONArray().apply { widgets.forEach { put(it.toJson()) } })
        return o.toString(2)
    }

    companion object {
        /** Throws on anything not shaped like a layout (restore relies on it). */
        fun parse(text: String): LayoutData {
            val o = JSONObject(text)
            val d = LayoutData()
            val pins = o.optJSONArray("pins") ?: JSONArray()
            for (i in 0 until pins.length()) d.pins.add(Pin.parse(pins.getJSONObject(i)))
            val folders = o.optJSONObject("folders") ?: JSONObject()
            val fkeys = folders.keys()
            while (fkeys.hasNext()) {
                val id = fkeys.next()
                val fo = folders.getJSONObject(id)
                val items = mutableListOf<Pin>()
                val arr = fo.optJSONArray("items") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val p = Pin.parse(arr.getJSONObject(i))
                    require(p !is Pin.FolderRef) { "a folder in a folder" }
                    items.add(p)
                }
                d.folders[id] = Folder(id, fo.optString("name"), items)
            }
            // A pin pointing at a folder that isn't there is a bad file.
            for (p in d.pins) {
                if (p is Pin.FolderRef) require(d.folders.containsKey(p.folderId)) { "pin for missing folder ${p.folderId}" }
            }
            val hidden = o.optJSONArray("hidden") ?: JSONArray()
            for (i in 0 until hidden.length()) {
                val s = hidden.getString(i)
                AppId.parse(s)
                d.hidden.add(s)
            }
            val gates = o.optJSONArray("gates") ?: JSONArray()
            for (i in 0 until gates.length()) {
                val s = gates.getString(i)
                AppId.parse(s)
                d.gates.add(s)
            }
            d.nextFolder = o.optInt("nextFolder", 1).coerceAtLeast(1)
            val widgets = o.optJSONArray("widgets") ?: JSONArray()
            for (i in 0 until widgets.length()) d.widgets.add(WidgetEntry.parse(widgets.getJSONObject(i)))
            return d
        }
    }
}

class LayoutStore(dir: File, private val log: LauncherLog) {
    private val file = File(dir, "layout.json")
    val data = MutableStateFlow(load())

    /** True when no layout file was there (first run: seed the pins). */
    var fresh: Boolean = false
        private set

    private fun load(): LayoutData {
        if (!file.exists()) {
            fresh = true
            return LayoutData()
        }
        return try {
            LayoutData.parse(file.readText())
        } catch (e: Exception) {
            log.log("error", "layout-bad-file", "error" to e.toString())
            runCatching { file.renameTo(File(file.parentFile, "layout.json.bad")) }
            LayoutData()
        }
    }

    /** Every change goes through here: mutate a copy's lists, save, publish. */
    @Synchronized fun update(block: (LayoutData) -> Unit) {
        val d = data.value
        block(d)
        writeSafely(file, d.toJson())
        // Same object, new tick: StateFlow only emits on change, so copy.
        data.value = LayoutData(
            d.pins.toMutableList(),
            d.folders.mapValues { (_, f) -> Folder(f.id, f.name, f.items.toMutableList()) }.toMutableMap(),
            d.hidden.toMutableSet(),
            d.gates.toMutableSet(),
            d.nextFolder,
            d.widgets.toMutableList(),
        )
    }

    @Synchronized fun replace(d: LayoutData) {
        writeSafely(file, d.toJson())
        data.value = d
    }
}
