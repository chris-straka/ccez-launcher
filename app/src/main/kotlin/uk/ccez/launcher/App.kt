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
            runCatching {
                launcherLog.log("error", "job-failed", "error" to e.toString(), "stack" to android.util.Log.getStackTraceString(e))
            }
        },
    )

    val apps by lazy { AppRepository(this, scope, launcherLog) }

    /** True while home is on screen (Control status; the overlay truce). */
    @Volatile var homeVisible: Boolean = false

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                launcherLog.log("error", "crash", "thread" to t.name, "error" to e.toString(), "stack" to android.util.Log.getStackTraceString(e))
            }
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
                .collect { list ->
                    if (apps.loaded.value && list.isNotEmpty()) {
                        reconcilePins(list)
                        // Home's icons and names saved for the next start's first frame.
                        apps.saveHome(homeKeys(layout.data.value))
                    }
                }
        }
        // The word core wakes up after home does.
        scope.launch {
            kotlinx.coroutines.delay(2_000)
            Words.warm(this@App, scope)
        }
    }

    /**
     * Pins of apps that aren't installed any more (owner, 2026-11-12: Samsung's
     * Clock, uninstalled, stayed in the row as "clockpac" with no icon and
     * no menu to unpin it) go; a pin whose app is there under another
     * entry point (an update renamed it) moves to it. Only in profiles the
     * list covered in full: a paused work profile's pins stay. A package
     * still installed and enabled keeps its pin and folder place even when
     * one listing misses it (mid-update, unavailable for a moment), and a
     * folder left with one app becomes the app (LayoutOps.reconcile).
     */
    internal fun reconcilePins(list: List<AppEntry>) {
        val ids = list.mapTo(HashSet()) { it.id }
        val listed = apps.listedUsers
        val movedTo = { id: AppId -> list.firstOrNull { it.id.pkg == id.pkg && it.id.user == id.user }?.id }
        val installed = { id: AppId -> apps.isInstalledAndEnabled(id) }
        val now = layout.data.value
        val trial = now.copyDeep()
        LayoutOps.reconcile(trial, ids, listed, movedTo, installed)
        if (trial.pins == now.pins && trial.folders == now.folders) return
        var gone = 0
        layout.update { d -> gone = LayoutOps.reconcile(d, ids, listed, movedTo, installed) }
        launcherLog.log("info", "pins-reconciled", "removed" to gone)
    }

    /** Home's apps: the row, and the first four of each folder in it (their preview). */
    private fun homeKeys(d: LayoutData): Set<String> {
        val keys = LinkedHashSet<String>()
        for (p in d.pins) when (p) {
            is Pin.App -> keys.add(p.id.key())
            is Pin.FolderRef -> d.folders[p.folderId]?.items?.take(4)?.forEach { if (it is Pin.App) keys.add(it.id.key()) }
            is Pin.Shortcut -> Unit
        }
        return keys
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
