package uk.ccez.launcher

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every launcher setting in one JSON file the app watches
 * (files/launcher/settings.json), so agents run the launcher from outside
 * (`scripts/launcher.ts get|set`, through Control). One table here,
 * like the core's Settings: a new setting shows up in the settings
 * screen, the schema and the CLI at once.
 */
object SettingDefs {
    sealed class Kind {
        data object Bool : Kind()
        data class IntRange(val min: Int, val max: Int) : Kind()
        data object Text : Kind()
    }

    data class Def(val key: String, val kind: Kind, val default: Any, val about: String)

    val ALL: List<Def> = listOf(
        Def("home.clock", Kind.Bool, true, "The clock and date on home."),
        Def("search.contacts", Kind.Bool, true, "Search finds people too (needs the contacts permission)."),
        Def("hidden.biometric", Kind.Bool, false, "The hidden space asks for a fingerprint first."),
        Def("gestures.notifications", Kind.Bool, true, "Swipe down on home pulls the notifications down."),
        Def("gestures.double_tap_lock", Kind.Bool, false, "Double-tap on home locks (needs the accessibility service on)."),
        Def("words.count", Kind.IntRange(0, 12), 4, "Learning words on home (0: off). Mirrors the core's learning.screen."),
        Def("words.langs", Kind.IntRange(0, 24), 0, "How many learning languages home words come from (0: all)."),
        Def("words.app_name_langs", Kind.Bool, false, "App names in the learning languages (Stage 5)."),
        Def("words.clock_lang", Kind.Bool, false, "The clock written out in a learning language (Stage 5)."),
        Def("updates.self", Kind.Bool, false, "The launcher installs releases itself from GitHub."),
    )

    fun def(key: String): Def? = ALL.firstOrNull { it.key == key }

    /** Checked value for [def] from a JSON value, or an error saying why not. */
    fun checked(def: Def, v: Any?): Any {
        return when (val k = def.kind) {
            Kind.Bool -> v as? Boolean ?: error("${def.key} takes true or false")
            is Kind.IntRange -> {
                val n = (v as? Number)?.toInt() ?: error("${def.key} takes a number from ${k.min} to ${k.max}")
                require(n in k.min..k.max) { "${def.key} takes a number from ${k.min} to ${k.max}" }
                n
            }
            Kind.Text -> v as? String ?: error("${def.key} takes a string")
        }
    }

    fun schema(): String {
        val a = JSONArray()
        for (d in ALL) {
            val kind: Any = when (val k = d.kind) {
                Kind.Bool -> "bool"
                is Kind.IntRange -> JSONObject().put("int", JSONArray().put(k.min).put(k.max))
                Kind.Text -> "string"
            }
            a.put(JSONObject().put("key", d.key).put("kind", kind).put("default", d.default).put("about", d.about))
        }
        return a.toString(2)
    }

    /** A whole settings object checked key by key (backup restore uses this first). */
    fun validated(o: JSONObject): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val d = def(key) ?: error("no setting called $key; `launcher schema` lists them")
            out[key] = checked(d, o.get(key))
        }
        return out
    }
}

class LauncherSettings(dir: File, private val log: LauncherLog) {
    private val file = File(dir, "settings.json")
    private val values = HashMap<String, Any>()

    /** Bumped on every change, from the screen, `launcher set` or the file itself. */
    val version = MutableStateFlow(0)

    init {
        load()
        watch()
    }

    @Synchronized fun get(key: String): Any {
        val d = SettingDefs.def(key) ?: error("no setting called $key; `launcher schema` lists them")
        return values[key] ?: d.default
    }

    /** One setting as JSON, as `launcher get <key>` shows it. */
    fun getJson(key: String): String {
        val v = get(key)
        return if (v is String) JSONObject.quote(v) else v.toString()
    }

    fun bool(key: String): Boolean = get(key) as Boolean
    fun number(key: String): Int = (get(key) as Number).toInt()

    /** Sets one setting from JSON (`true`, `4`); a bare word is a string. Returns it as stored. */
    @Synchronized fun set(key: String, jsonValue: String): String {
        val d = SettingDefs.def(key) ?: error("no setting called $key; `launcher schema` lists them")
        val parsed: Any = runCatching { org.json.JSONTokener(jsonValue).nextValue() }.getOrDefault(jsonValue)
        val v = SettingDefs.checked(d, parsed)
        values[key] = v
        save()
        version.value += 1
        log.log("info", "setting", "key" to key, "value" to v.toString())
        return if (v is String) JSONObject.quote(v) else v.toString()
    }

    @Synchronized fun replaceAll(checkedValues: Map<String, Any>) {
        values.clear()
        values.putAll(checkedValues)
        save()
        version.value += 1
    }

    @Synchronized fun toJson(): String {
        val o = JSONObject()
        for (d in SettingDefs.ALL) o.put(d.key, values[d.key] ?: d.default)
        return o.toString(2)
    }

    @Synchronized private fun load() {
        values.clear()
        val text = runCatching { file.readText() }.getOrNull()
        if (!text.isNullOrBlank()) {
            val o = runCatching { JSONObject(text) }.getOrNull()
            if (o != null) {
                for (d in SettingDefs.ALL) {
                    if (o.has(d.key)) runCatching { values[d.key] = SettingDefs.checked(d, o.get(d.key)) }
                }
            } else {
                log.log("warn", "settings-bad-file")
            }
        }
    }

    @Synchronized private fun save() = writeSafely(file, toJson())

    private fun watch() {
        runCatching {
            val flags = android.os.FileObserver.CLOSE_WRITE or android.os.FileObserver.MOVED_TO
            @Suppress("DEPRECATION")
            val w = object : android.os.FileObserver(file.parentFile, flags) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == file.name) {
                        load()
                        version.value += 1
                    }
                }
            }
            w.startWatching()
        }
    }
}
