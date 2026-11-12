package uk.ccez.launcher

import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The layout as one JSON file, for `launcher backup` / `launcher
 * restore`: pins, hidden apps, hidden space, folders and settings.
 * Read and checked whole before anything is written, as ccez-keys does.
 */
object Backup {
    class WriteFailed(cause: Throwable) : Exception("restore stopped partway: $cause", cause)

    fun export(): String {
        val app = App.instance
        return JSONObject()
            .put("ccez-launcher-backup", 1)
            .put("version", BuildConfig.VERSION_NAME)
            .put("made", System.currentTimeMillis())
            .put("settings", JSONObject(app.settings.toJson()))
            .put("layout", JSONObject(app.layout.data.value.toJson()))
            .toString(2)
    }

    fun import(json: String): String {
        val app = App.instance
        // All of it checked first: a bad backup changes nothing.
        val o = JSONObject(json)
        require(o.optInt("ccez-launcher-backup") == 1) { "not a ccez-launcher backup" }
        val settings = o.optJSONObject("settings")
        val checked = settings?.let { SettingDefs.validated(it) }
        val layoutObj = o.optJSONObject("layout")
        val layout = layoutObj?.let { LayoutData.parse(it.toString()) }
        require(checked != null || layout != null) { "nothing to restore" }
        try {
            checked?.let { app.settings.replaceAll(it) }
            layout?.let { app.layout.replace(it) }
        } catch (e: Exception) {
            throw WriteFailed(e)
        }
        app.settingsChanged()
        // Widget ids travel badly across phones: rebind by provider or drop.
        app.scope.launch { runCatching { Widgets.validate(app) } }
        return "restored settings=${checked != null} layout=${layout != null} from ${o.optString("version")}"
    }
}
