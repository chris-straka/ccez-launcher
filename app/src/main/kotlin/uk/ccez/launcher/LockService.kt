package uk.ccez.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Double-tap to lock, and nothing else: no window content, no events
 * read. Only runs when the owner turns it on in Settings; the launcher
 * asks it for GLOBAL_ACTION_LOCK_SCREEN.
 */
class LockService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        @Volatile private var instance: LockService? = null

        fun lock(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false

        /** The shade, when the status-bar way is shut and this is on. */
        fun notifications(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) ?: false

        fun enabled(ctx: Context): Boolean {
            val want = "${ctx.packageName}/${LockService::class.java.name}"
            val set = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return set.split(':').any { it == want }
        }
    }
}
