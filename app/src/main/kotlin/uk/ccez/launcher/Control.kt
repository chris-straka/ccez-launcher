package uk.ccez.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Base64
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * What `launcher` on the Mac calls, through `adb shell content call
 * --uri content://uk.ccez.launcher.control --method <...>`. Answers one
 * extra, "out" (or "error"), base64 so any text survives the shell.
 * Only the shell user (adb) and this app may call it.
 */
class Control : ContentProvider() {
    override fun onCreate() = true

    private fun checkCaller() {
        val uid = Binder.getCallingUid()
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID && uid != Process.myUid()) {
            throw SecurityException("only adb may control ccez-launcher")
        }
    }

    /**
     * `adb exec-out content read --uri content://uk.ccez.launcher.control/backup`
     * streams a backup; `content write --uri .../restore` takes one back.
     */
    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor {
        checkCaller()
        val app = App.instance
        val (read, write) = android.os.ParcelFileDescriptor.createPipe()
        when (uri.path) {
            "/backup" -> {
                Thread {
                    runCatching { android.os.ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(Backup.export().toByteArray()) } }
                        .onFailure { app.launcherLog.log("error", "backup", "error" to it.toString()) }
                }.start()
                return read
            }
            "/restore" -> {
                Thread {
                    val text = runCatching { android.os.ParcelFileDescriptor.AutoCloseInputStream(read).use { it.readBytes().decodeToString() } }
                    val said = text.mapCatching { Backup.import(it) }
                    said.onSuccess { app.launcherLog.log("info", "restore", "result" to it) }
                        .onFailure { app.launcherLog.log("error", "restore", "error" to it.toString()) }
                }.start()
                return write
            }
        }
        read.close(); write.close()
        throw java.io.FileNotFoundException("no ${uri.path}")
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        checkCaller()
        val app = App.instance
        val out = Bundle()
        try {
            val result = when (method) {
                "status" -> status(app)
                "get" -> if (arg == null) app.settings.toJson() else app.settings.getJson(arg)
                "set" -> {
                    val key = arg ?: error("set needs a setting name")
                    val value = extras?.getString("value")?.let { String(Base64.decode(it, Base64.DEFAULT)) }
                        ?: error("set needs a value")
                    app.settings.set(key, value).also { app.settingsChanged() }
                }
                "schema" -> SettingDefs.schema()
                "log" -> app.launcherLog.tail((arg?.toIntOrNull() ?: 50).coerceIn(1, 5000))
                "dump" -> JSONObject()
                    .put("settings", JSONObject(app.settings.toJson()))
                    .put("layout", JSONObject(app.layout.data.value.toJson()))
                    .put("apps", app.apps.apps.value.size)
                    .put("home_visible", app.homeVisible)
                    .toString(2)
                "update" -> {
                    Updater.force(app)
                    "checking"
                }
                // Statuses + schedule to and from ccez-words, dictionaries fetched.
                // `launcher sync` for every learning language, `launcher sync fr` for one.
                "sync" -> {
                    WordSync.soon(app, 0)
                    val lang = arg?.trim().orEmpty()
                    if (lang.isNotEmpty()) {
                        app.scope.launch { runCatching { Dictionaries.ensure(app, lang) } }
                    }
                    "syncing"
                }
                // A word's status in the core: `launcher word fr libre [new|learning|known]`.
                "word" -> word(app, arg)
                // The words home would show now (agents check Stage 2 with this).
                "screen" -> {
                    val e = Words.engineOrNull() ?: error("core not up")
                    val words = Words.screenWords(app, emptyList())
                    val a = org.json.JSONArray()
                    for (w in words) {
                        a.put(JSONObject().put("lang", w.lang).put("word", w.word).put("shown", w.shown).put("meaning", w.meaning))
                    }
                    JSONObject().put("core", e.version()).put("words", a).toString(2)
                }
                else -> error("no command called $method")
            }
            out.putString("out", b64(result))
        } catch (e: Exception) {
            app.launcherLog.log("error", "control", "method" to method, "error" to e.toString())
            out.putString("error", b64(e.message ?: e.toString()))
        }
        return out
    }

    private fun word(app: App, arg: String?): String {
        val parts = (arg ?: error("word needs: lang word [status]")).split(' ')
        if (parts.size < 2) error("word needs: lang word [status]")
        val e = Words.engine(app)
        val (lang, w) = parts
        if (parts.size > 2) {
            val st = when (parts[2]) {
                "new" -> uk.ccez.keys.core.WordStatus.NEW
                "learning" -> uk.ccez.keys.core.WordStatus.LEARNING
                "known" -> uk.ccez.keys.core.WordStatus.KNOWN
                else -> error("status is new, learning or known")
            }
            e.setWordStatus(lang, w, st)
        }
        return e.wordStatus(lang, w).name.lowercase()
    }

    private fun status(app: App): String {
        val o = JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("core", Words.engineOrNull()?.version() ?: "not up")
            .put("default_home", app.apps.isDefaultHome())
            .put("home_visible", app.homeVisible)
            .put("apps", app.apps.apps.value.size)
            .put("apps_loaded", app.apps.loaded.value)
            .put("lock_service", LockService.enabled(app))
        return o.toString(2)
    }

    private fun b64(s: String) = Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
