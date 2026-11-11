package uk.ccez.launcher

import android.app.Application
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The launcher's one set of stores. Home shows from these, nothing loads on it. */
class App : Application() {
    init {
        instance = this
    }

    private val dir by lazy { File(filesDir, "launcher").also { it.mkdirs() } }

    val launcherLog by lazy { LauncherLog(dir) }
    val settings by lazy { LauncherSettings(dir, launcherLog) }
    val layout by lazy { LayoutStore(dir, launcherLog) }

    /** Background work. A failure is logged, never a dead home screen. */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            runCatching { launcherLog.log("error", "job-failed", "error" to e.toString()) }
        },
    )

    val apps by lazy { AppRepository(this, scope, launcherLog) }

    /** True while home is on screen (Control status; the overlay truce). */
    @Volatile var homeVisible: Boolean = false

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { launcherLog.log("error", "crash", "thread" to t.name, "error" to e.toString()) }
            previous?.uncaughtException(t, e)
        }
        launcherLog.log("info", "start", "version" to BuildConfig.VERSION_NAME)
        apps.start()
        seedPinsOnce()
        // The word core wakes up after home does.
        scope.launch {
            kotlinx.coroutines.delay(2_000)
            Words.warm(this@App, scope)
        }
    }

    /** First run: a short row to start from (phone, messages, camera, settings). */
    private fun seedPinsOnce() {
        if (!layout.fresh) return
        scope.launch {
            // Wait for the first app list, briefly.
            var waited = 0
            while (!apps.loaded.value && waited < 50) {
                kotlinx.coroutines.delay(100)
                waited++
            }
            val all = apps.apps.value
            if (all.isEmpty() || layout.data.value.pins.isNotEmpty()) return@launch
            val wanted = listOf("dialer", "messaging", "camera", "settings", "clock", "browser", "photos")
            val picked = ArrayList<AppEntry>()
            for (w in wanted) {
                all.firstOrNull { !it.otherProfile && (it.id.pkg.contains(w, true) || it.label.equals(w, true)) && it !in picked }
                    ?.let { picked.add(it) }
            }
            val seed = (picked + all.filter { !it.otherProfile }).distinct().take(5)
            if (seed.isNotEmpty()) {
                layout.update { d -> seed.forEach { d.pins.add(Pin.App(it.id)) } }
                launcherLog.log("info", "seed-pins", "count" to seed.size)
            }
        }
    }

    /** Settings changed from outside the core: words.* mirror into it. */
    fun settingsChanged() {
        scope.launch {
            val e = Words.engineOrNull() ?: return@launch
            Words.applyWordSettings(this@App, e)
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
