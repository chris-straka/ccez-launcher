package uk.ccez.launcher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Widget hosting (Stage 6): the few widgets the owner keeps, hosted by
 * AppWidgetHost over the pins. Ids live in the layout file; a restore on
 * another phone rebinds by provider or drops the entry.
 */
object Widgets {
    private const val HOST_ID = 1024
    private var host: AppWidgetHost? = null

    fun host(ctx: Context): AppWidgetHost =
        host ?: AppWidgetHost(ctx.applicationContext, HOST_ID).also { host = it }

    fun manager(ctx: Context): AppWidgetManager = AppWidgetManager.getInstance(ctx)

    fun providers(ctx: Context): List<AppWidgetProviderInfo> =
        runCatching { manager(ctx).installedProviders }.getOrDefault(emptyList())

    fun info(ctx: Context, entry: WidgetEntry): AppWidgetProviderInfo? =
        runCatching { manager(ctx).getAppWidgetInfo(entry.appWidgetId) }.getOrNull()

    /** An id bound and (where needed) configured: put it on home. */
    fun addAllocated(ctx: Context, appWidgetId: Int): Boolean {
        val info = runCatching { manager(ctx).getAppWidgetInfo(appWidgetId) }.getOrNull() ?: return false
        val entry = WidgetEntry(appWidgetId, info.provider.packageName, info.provider.className)
        App.instance.layout.update { d ->
            if (d.widgets.none { it.appWidgetId == appWidgetId }) d.widgets.add(entry)
        }
        App.instance.launcherLog.log("info", "widget-added", "provider" to entry.pkg)
        return true
    }

    fun remove(ctx: Context, entry: WidgetEntry) {
        runCatching { host(ctx).deleteAppWidgetId(entry.appWidgetId) }
        App.instance.layout.update { it.widgets.removeAll { w -> w.appWidgetId == entry.appWidgetId } }
    }

    /** After a restore (or an app update): drop or rebind dead ids, by provider. */
    fun validate(ctx: Context) {
        val app = App.instance
        val entries = app.layout.data.value.widgets.toList()
        if (entries.isEmpty()) return
        val mgr = manager(ctx)
        for (entry in entries) {
            if (info(ctx, entry) != null) continue
            val component = ComponentName(entry.pkg, entry.cls)
            val exists = providers(ctx).any { it.provider == component }
            var rebound: WidgetEntry? = null
            if (exists) {
                val newId = host(ctx).allocateAppWidgetId()
                if (runCatching { mgr.bindAppWidgetIdIfAllowed(newId, component) }.getOrDefault(false)) {
                    rebound = WidgetEntry(newId, entry.pkg, entry.cls)
                } else {
                    runCatching { host(ctx).deleteAppWidgetId(newId) }
                }
            }
            app.layout.update { d ->
                val i = d.widgets.indexOfFirst { it.appWidgetId == entry.appWidgetId }
                if (i >= 0) {
                    if (rebound != null) d.widgets[i] = rebound else d.widgets.removeAt(i)
                }
            }
            app.launcherLog.log("info", "widget-validate", "provider" to entry.pkg, "rebound" to (rebound != null))
        }
    }

    fun startListening(ctx: Context) = runCatching { host(ctx).startListening() }
    fun stopListening(ctx: Context) = runCatching { host?.stopListening() }
}

/** The hosted widgets, over the pinned row. A hold takes one off. */
@Composable fun WidgetStrip() {
    val app = App.instance
    val ctx = LocalContext.current
    val layout by app.layout.data.collectAsState()
    var removing by remember { mutableStateOf<WidgetEntry?>(null) }
    if (layout.widgets.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).testTag("widgets")) {
        for (entry in layout.widgets) {
            key(entry.appWidgetId) {
                val info = remember(entry.appWidgetId) { Widgets.info(ctx, entry) }
                if (info != null) {
                    val h = info.minHeight.coerceIn(72, 220)
                    AndroidView(
                        factory = { c ->
                            Widgets.host(c).createView(c, entry.appWidgetId, info)
                        },
                        modifier = Modifier.fillMaxWidth().height(h.dp).padding(vertical = 4.dp)
                            .pointerInput(entry.appWidgetId) {
                                detectTapGestures(onLongPress = { removing = entry })
                            }
                            .testTag("widget:${entry.appWidgetId}"),
                    )
                }
            }
        }
    }
    removing?.let { entry ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = Color(0xFF101010),
            title = { Text("Widget", color = Palette.fg, fontSize = 17.sp) },
            text = { Text(entry.pkg, color = Palette.dim, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = { Widgets.remove(ctx, entry); removing = null }) {
                    Text("Remove", color = Palette.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text("Close", color = Palette.dim) }
            },
        )
    }
}
