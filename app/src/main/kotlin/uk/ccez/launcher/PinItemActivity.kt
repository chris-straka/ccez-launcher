package uk.ccez.launcher

import android.content.pm.LauncherApps
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Another app asking to pin something on home. Shortcuts land as a pin
 * (Stage 1). Widgets wait for Stage 6 hosting: refused by not accepting.
 */
class PinItemActivity : ComponentActivity() {
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
            }
            else -> app.launcherLog.log("info", "pin-request-refused", "type" to req.requestType)
        }
        finish()
    }
}
