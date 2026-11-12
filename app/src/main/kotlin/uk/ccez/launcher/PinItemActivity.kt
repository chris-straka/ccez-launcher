package uk.ccez.launcher

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Another app asking to pin something on home. Shortcuts land as a pin;
 * widgets are hosted from Stage 6 (bound here, configured when asked).
 */
class PinItemActivity : ComponentActivity() {
    private var pendingWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var pendingRequest: LauncherApps.PinItemRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = App.instance
        val la = getSystemService(LauncherApps::class.java)
        val req = runCatching { la?.getPinItemRequest(intent) }.getOrNull()
        if (req == null) {
            finish()
            return
        }
        when (req.requestType) {
            LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT -> {
                val si = req.getShortcutInfo()
                if (si != null) {
                    val user = runCatching {
                        getSystemService(android.os.UserManager::class.java)?.getSerialNumberForUser(si.userHandle) ?: 0L
                    }.getOrDefault(0L)
                    val pin = Pin.Shortcut(si.`package`, si.id, user, si.shortLabel?.toString().orEmpty())
                    app.layout.update { it.pins.add(pin) }
                    runCatching { req.accept() }
                    app.launcherLog.log("info", "pin-request-shortcut", "pkg" to si.`package`)
                }
                finish()
            }
            LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET -> {
                val info = runCatching { req.getAppWidgetProviderInfo(this) }.getOrNull()
                if (info == null) {
                    finish()
                    return
                }
                val id = Widgets.host(this).allocateAppWidgetId()
                val bound = runCatching {
                    Widgets.manager(this).bindAppWidgetIdIfAllowed(id, info.provider)
                }.getOrDefault(false)
                if (!bound) {
                    runCatching { Widgets.host(this).deleteAppWidgetId(id) }
                    app.launcherLog.log("warn", "pin-request-widget-unbound", "pkg" to info.provider.packageName)
                    finish()
                    return
                }
                if (info.configure != null) {
                    pendingWidgetId = id
                    pendingRequest = req
                    runCatching {
                        @Suppress("DEPRECATION")
                        startActivityForResult(
                            Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                                .setComponent(info.configure)
                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                            7,
                        )
                    }.onFailure {
                        acceptWidget(req, id)
                        finish()
                    }
                } else {
                    acceptWidget(req, id)
                    finish()
                }
            }
            else -> {
                app.launcherLog.log("info", "pin-request-refused", "type" to req.requestType)
                finish()
            }
        }
    }

    private fun acceptWidget(req: LauncherApps.PinItemRequest, appWidgetId: Int) {
        val app = App.instance
        if (Widgets.addAllocated(this, appWidgetId)) {
            runCatching {
                req.accept(Bundle().apply {
                    putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                })
            }
            app.launcherLog.log("info", "pin-request-widget", "id" to appWidgetId)
        }
    }

    @Deprecated("Legacy result for the widget configure step")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7) {
            val req = pendingRequest
            val id = pendingWidgetId
            pendingRequest = null
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            if (resultCode == RESULT_OK && req != null && id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                acceptWidget(req, id)
            } else if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                runCatching { Widgets.host(this).deleteAppWidgetId(id) }
            }
            finish()
        }
    }
}
