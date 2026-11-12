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
        Today.load(this)
        apps.start()
        scope.launch { runCatching { Widgets.validate(this@App) } }
        seedPinsOnce()
        // Pins follow the apps: on every new app list (installs and
        // uninstalls bring one) and every new layout (a restore), a pin whose
        // app is gone goes, and one whose app moved its entry point follows
        // it. A pass that changes nothing writes nothing, so it settles.
        scope.launch {
            kotlinx.coroutines.flow.combine(apps.apps, layout.data) { list, _ -> list }
                .collect { list -> if (apps.loaded.value && list.isNotEmpty()) reconcilePins(list) }
        }
        // The word core wakes up after home does.
        scope.launch {
            kotlinx.coroutines.delay(2_000)
            Words.warm(this@App, scope)
        }
    }

    /** First run: a short row to start from (phone, messages, camera, settings). */
    /**
     * Pins of apps that aren't installed any more (owner, 2026-11-12: Samsung's
     * Clock, uninstalled, stayed in the row as "clockpac" with no icon and
     * no menu to unpin it) go; a pin whose app is there under another
     * entry point (an update renamed it) moves to it. Only in profiles the
     * list covered in full: a paused work profile's pins stay.
     */
    internal fun reconcilePins(list: List<AppEntry>) {
        val ids = list.mapTo(HashSet()) { it.id }
        val listed = apps.listedUsers
        fun fixed(p: Pin): Pin? = when (p) {
            is Pin.App -> when {
                p.id in ids || p.id.user !in listed -> p
                else -> list.firstOrNull { it.id.pkg == p.id.pkg && it.id.user == p.id.user }?.let { Pin.App(it.id) }
            }
            else -> p
        }
        val now = layout.data.value
        val pins = now.pins.mapNotNull(::fixed)
        val folders = now.folders.mapValues { (_, f) -> f.items.mapNotNull(::fixed) }
        if (pins == now.pins && folders.all { (k, v) -> v == now.folders[k]?.items }) return
        val gone = (now.pins + now.folders.values.flatMap { it.items }).count { fixed(it) == null }
        layout.update { d ->
            d.pins.clear()
            d.pins.addAll(pins)
            d.folders.values.forEach { f -> f.items.clear(); f.items.addAll(folders[f.id].orEmpty()) }
        }
        launcherLog.log("info", "pins-reconciled", "removed" to gone)
    }

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
