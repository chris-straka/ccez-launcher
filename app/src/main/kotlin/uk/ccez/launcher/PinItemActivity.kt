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
                    app.layout.update { LayoutOps.addShortcutOnce(it, pin) }
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
                pendingWidgetId = id
                pendingRequest = req
                pendingInfo = info
                val bound = runCatching {
                    Widgets.manager(this).bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
                }.getOrDefault(false)
                if (bound) {
                    configureOrAccept()
                    return
                }
                // A launcher that isn't a system app may bind only once the
                // owner says so: Android's own "allow this app to create
                // widgets" ask, once ("Always allow" covers the next ones),
                // as Launcher3 does. Without it every request was dropped.
                runCatching {
                    @Suppress("DEPRECATION")
                    startActivityForResult(
                        Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile),
                        BIND,
                    )
                    app.launcherLog.log("info", "pin-request-widget-bind-ask", "pkg" to info.provider.packageName)
                }.onFailure {
                    dropPending("pin-request-widget-unbound", it)
                    finish()
                }
            }
            else -> {
                app.launcherLog.log("info", "pin-request-refused", "type" to req.requestType)
                finish()
            }
        }
    }

    private var pendingInfo: android.appwidget.AppWidgetProviderInfo? = null

    /** Bound: configure it when the widget asks to be, else put it on home. */
    private fun configureOrAccept() {
        val req = pendingRequest ?: return finish()
        val id = pendingWidgetId
        val configure = pendingInfo?.configure
        if (configure == null) {
            clearPending()
            acceptWidget(req, id)
            finish()
            return
        }
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                    .setComponent(configure)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                CONFIGURE,
            )
        }.onFailure {
            clearPending()
            acceptWidget(req, id)
            finish()
        }
    }

    private fun clearPending() {
        pendingRequest = null
        pendingInfo = null
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    }

    private fun dropPending(event: String, error: Throwable? = null) {
        val id = pendingWidgetId
        val pkg = pendingInfo?.provider?.packageName
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) runCatching { Widgets.host(this).deleteAppWidgetId(id) }
        clearPending()
        App.instance.launcherLog.log("warn", event, "pkg" to pkg, "error" to error?.toString())
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

    @Deprecated("Legacy result for the widget bind and configure steps")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            BIND -> if (resultCode == RESULT_OK && pendingRequest != null) {
                configureOrAccept()
            } else {
                dropPending("pin-request-widget-bind-refused")
                finish()
            }
            CONFIGURE -> {
                val req = pendingRequest
                val id = pendingWidgetId
                if (resultCode == RESULT_OK && req != null && id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    clearPending()
                    acceptWidget(req, id)
                } else {
                    dropPending("pin-request-widget-not-configured")
                }
                finish()
            }
        }
    }

    private companion object {
        const val CONFIGURE = 7
        const val BIND = 8
    }
}
