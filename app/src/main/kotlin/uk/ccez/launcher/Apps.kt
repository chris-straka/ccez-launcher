package uk.ccez.launcher

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.LruCache
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** An app as the drawer shows it, on whichever profile it lives. */
data class AppEntry(
    val id: AppId,
    val label: String,
    val user: UserHandle,
    val info: LauncherActivityInfo,
    val system: Boolean,
) {
    val key: String get() = id.key()
    val otherProfile: Boolean get() = user != Process.myUserHandle()
}

/**
 * The app list (LauncherApps, every profile), cached and refreshed in the
 * background on package changes. Home never waits on this: it draws from
 * the state flow as it is.
 */
class AppRepository(private val ctx: Context, private val scope: CoroutineScope, private val log: LauncherLog) {
    private val launcherApps = ctx.getSystemService(LauncherApps::class.java)
    private val userManager = ctx.getSystemService(UserManager::class.java)
    private val density = ctx.resources.configuration.densityDpi

    val apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val loaded = MutableStateFlow(false)
    /** Bumped when any app's shortcuts change (menus read shortcuts fresh). */
    val shortcutsVersion = MutableStateFlow(0)

    // By bytes, not count: a phone's 170 px icon is 110 KB, so 256 of them
    // could reach 28 MB (audit 2026-11-18).
    private val icons = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val shortcutIcons = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /**
     * Home's own apps (the row and its folders) kept on disk, icon and
     * name, so a restarted launcher draws them on its first frame instead
     * of names first and icons after (owner, 2026-11-18). Read at start
     * before anything else; drawn again from the app once the list is in.
     */
    private val homeDir = File(ctx.cacheDir, "home-icons")
    /** Names of home's apps as last saved, until the app list lands. */
    val savedLabels = MutableStateFlow<Map<String, String>>(emptyMap())
    /** The saved icons are in memory (or there are none): home can show. */
    val savedReady = MutableStateFlow(false)
    /** Bumped when an icon on screen was drawn again (an update, a new theme). */
    val iconsVersion = MutableStateFlow(0)

    /** The profiles (serials) whose apps the last refresh listed in full:
     * on, not paused, with apps. A pin elsewhere is left alone (its app
     * may only be hidden behind a paused work profile). */
    @Volatile var listedUsers: Set<Long> = emptySet()
        private set

    /** The Android 15 Private space profile, when the phone has one. */
    @Volatile var privateUser: UserHandle? = null
        private set

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(pkg: String?, user: UserHandle?) = refresh(listOfNotNull(pkg))
        override fun onPackageAdded(pkg: String?, user: UserHandle?) = refresh(listOfNotNull(pkg))
        override fun onPackageChanged(pkg: String?, user: UserHandle?) = refresh(listOfNotNull(pkg))
        override fun onPackagesAvailable(pkgs: Array<out String>?, user: UserHandle?, replacing: Boolean) = refresh(pkgs?.toList().orEmpty())
        override fun onPackagesUnavailable(pkgs: Array<out String>?, user: UserHandle?, replacing: Boolean) = refresh(pkgs?.toList().orEmpty())
        override fun onPackagesSuspended(pkgs: Array<out String>?, user: UserHandle?) = refresh()
        override fun onPackagesUnsuspended(pkgs: Array<out String>?, user: UserHandle?) = refresh()
        override fun onShortcutsChanged(pkg: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) {
            shortcutsVersion.value += 1
        }
    }

    fun start() {
        runCatching { launcherApps?.registerCallback(callback) }
            .onFailure { log.log("error", "apps-callback-failed", "error" to it.javaClass.simpleName) }
        scope.launch(Dispatchers.IO) {
            try {
                loadSaved()
            } finally {
                savedReady.value = true
            }
            refresh()
        }
    }

    /**
     * The app list again. [changed]: packages whose icons are dropped first
     * (updated, removed); the rest stay cached. Every icon used to be dropped
     * on any package event, and home drew names alone while they came back.
     */
    fun refresh(changed: List<String> = emptyList()) {
        scope.launch(Dispatchers.IO) {
            // One at a time, in order: a burst of package events (Play
            // updating several apps) ran in parallel and the last to finish
            // won, maybe not the newest (audit 2026-11-18).
            refreshing.withLock { list(changed) }
        }
    }

    private val refreshing = kotlinx.coroutines.sync.Mutex()

    /** Icon decodes a few at a time (the drawer opening asked for 64 at once). */
    private val iconWork = Dispatchers.IO.limitedParallelism(4)

    private fun list(changed: List<String>) {
        run {
            for (pkg in changed) forgetIcons(pkg)
            val started = System.nanoTime()
            val la = launcherApps ?: return
            val um = userManager
            val users = LinkedHashSet<UserHandle>()
            runCatching { users.addAll(um?.userProfiles.orEmpty()) }
            runCatching { users.addAll(la.profiles) }
            if (users.isEmpty()) users.add(Process.myUserHandle())
            privateUser = users.firstOrNull { isPrivateProfile(it) }
            val out = ArrayList<AppEntry>()
            val listed = HashSet<Long>()
            for (user in users) {
                val serial = runCatching { um?.getSerialNumberForUser(user) ?: 0L }.getOrDefault(0L)
                val list = runCatching { la.getActivityList(null, user) }
                    .onFailure { log.log("error", "apps-list-failed", "profile" to serial, "error" to it.javaClass.simpleName) }
                    .getOrNull().orEmpty()
                if (list.isNotEmpty() && !isQuiet(user)) listed.add(serial)
                for (info in list) {
                    val cn = info.componentName
                    if (cn.packageName == ctx.packageName) continue
                    val system = (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    out.add(AppEntry(AppId(cn.packageName, cn.className, serial), info.label?.toString().orEmpty(), user, info, system))
                }
            }
            out.sortBy { normalized(it.label) }
            listedUsers = listed
            apps.value = out
            loaded.value = true
            // Cells showing a changed package's icon draw it again.
            if (changed.isNotEmpty()) iconsVersion.value += 1
            log.log("info", "apps", "count" to out.size, "profiles" to users.size, "ms" to (System.nanoTime() - started) / 1_000_000)
        }
    }

    private fun isPrivateProfile(user: UserHandle): Boolean {
        if (Build.VERSION.SDK_INT < 35) return false
        return runCatching {
            val info = launcherApps?.getLauncherUserInfo(user)
            info?.userType?.contains("PRIVATE", ignoreCase = true) == true
        }.getOrDefault(false)
    }

    fun isQuiet(user: UserHandle): Boolean =
        runCatching { userManager?.isQuietModeEnabled(user) == true }.getOrDefault(false)

    fun requestProfileUnlock(user: UserHandle) {
        runCatching { userManager?.requestQuietModeEnabled(false, user) }
            .onFailure { log.log("warn", "profile-unlock-failed", "error" to it.toString()) }
    }

    /**
     * Whether [id]'s package is still installed and enabled for its user,
     * listed or not (a Play update takes it out of the list for a moment).
     * Uninstalled, or disabled by the owner: false.
     */
    fun isInstalledAndEnabled(id: AppId): Boolean {
        val la = launcherApps ?: return true
        return runCatching { la.getApplicationInfo(id.pkg, 0, userForSerial(id.user)).enabled }.getOrDefault(false)
    }

    fun byKey(key: String): AppEntry? = apps.value.firstOrNull { it.key == key }
    fun byId(id: AppId): AppEntry? = apps.value.firstOrNull { it.id == id }

    fun userForSerial(serial: Long): UserHandle =
        runCatching { userManager?.getUserForSerialNumber(serial) }.getOrNull() ?: Process.myUserHandle()

    /** An icon already in memory, for a first frame that has it. */
    fun cachedIcon(key: String): Bitmap? = icons.get(key)

    /** An app's icon, badged for its profile. Cached; call from the UI. */
    suspend fun icon(entry: AppEntry): Bitmap? {
        icons.get(entry.key)?.let { return it }
        val bmp = withContext(iconWork) { render(entry) }
        if (bmp != null) icons.put(entry.key, bmp)
        return bmp
    }

    private fun render(entry: AppEntry): Bitmap? = runCatching {
        var d: Drawable = entry.info.getIcon(density)
        if (entry.otherProfile) {
            runCatching { d = ctx.packageManager.getUserBadgedIcon(d, entry.user) }
        }
        drawableToBitmap(d)
    }.onFailure { log.log("warn", "icon-failed", "pkg" to entry.id.pkg, "error" to it.javaClass.simpleName) }.getOrNull()

    private fun forgetIcons(pkg: String) {
        val prefix = "$pkg/"
        icons.snapshot().keys.filter { it.startsWith(prefix) }.forEach { icons.remove(it) }
        shortcutIcons.snapshot().keys.filter { it.startsWith(prefix) }.forEach { shortcutIcons.remove(it) }
        homeDir.listFiles()?.filter { it.name.startsWith(fileStem(prefix)) }?.forEach { it.delete() }
    }

    /** A key as a file name: readable, and its package first so a package's files can be found. */
    private fun fileStem(key: String): String = key.replace(Regex("[^A-Za-z0-9._@-]"), "_")

    private fun loadSaved() {
        val index = File(homeDir, "labels.json")
        if (!index.exists()) return
        val labels = runCatching {
            val o = org.json.JSONObject(index.readText())
            o.keys().asSequence().associateWith { o.getString(it) }
        }.onFailure { log.log("warn", "home-icons-unreadable", "error" to it.javaClass.simpleName) }.getOrNull() ?: return
        for (key in labels.keys) {
            val f = File(homeDir, fileStem(key) + ".png")
            if (!f.exists()) continue
            android.graphics.BitmapFactory.decodeFile(f.path)?.let { icons.put(key, it) }
        }
        savedLabels.value = labels
    }

    /**
     * Home's apps ([keys]) drawn fresh and saved for the next start; an icon
     * that changed (an update, a theme) replaces the one on screen. Called
     * off the main thread after each app list and layout change.
     */
    fun saveHome(keys: Set<String>) {
        val list = apps.value
        if (list.isEmpty()) return
        homeDir.mkdirs()
        val byKey = list.associateBy { it.key }
        val labels = LinkedHashMap<String, String>()
        var changed = false
        for (key in keys) {
            val entry = byKey[key] ?: continue
            labels[key] = entry.label
            val file = File(homeDir, fileStem(key) + ".png")
            val now = icons.get(key)
            // Drawn again once a run (a theme or an update may have changed
            // it while the launcher wasn't running); after that, only when gone.
            if (now != null && file.exists() && key in checked) continue
            val fresh = render(entry) ?: continue
            checked.add(key)
            if (now == null || !now.sameAs(fresh)) {
                icons.put(key, fresh)
                if (now != null) changed = true
            }
            if (!file.exists() || now == null || !now.sameAs(fresh)) {
                runCatching { file.outputStream().use { fresh.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    .onFailure { log.log("warn", "home-icon-save-failed", "error" to it.javaClass.simpleName) }
            }
        }
        if (labels != savedLabels.value) {
            runCatching { File(homeDir, "labels.json").writeText(org.json.JSONObject(labels as Map<*, *>).toString()) }
                .onFailure { log.log("warn", "home-icons-save-failed", "error" to it.javaClass.simpleName) }
            savedLabels.value = labels
        }
        // Icons no longer on home leave the disk.
        val stems = keys.mapTo(HashSet()) { fileStem(it) + ".png" }
        homeDir.listFiles()?.filter { it.name.endsWith(".png") && it.name !in stems }?.forEach { it.delete() }
        if (changed) iconsVersion.value += 1
    }

    /** Home icons drawn again this run. */
    private val checked = java.util.Collections.synchronizedSet(HashSet<String>())

    fun drawableToBitmap(d: Drawable): Bitmap {
        val size = (ctx.resources.displayMetrics.density * 56).toInt().coerceIn(72, 288)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(canvas)
        return bmp
    }

    fun launch(entry: AppEntry) {
        log.log("info", "launch", "pkg" to entry.id.pkg, "user" to entry.id.user)
        LastApp.remember(App.instance, entry.id.pkg)
        runCatching {
            val cn = ComponentName(entry.id.pkg, entry.id.cls)
            val opts = ActivityOptions.makeBasic().toBundle()
            launcherApps?.startMainActivity(cn, entry.user, null, opts)
        }.onFailure { log.log("error", "launch-failed", "pkg" to entry.id.pkg, "error" to it.toString()) }
    }

    /** The app's own shortcuts (the default home app may read them). */
    suspend fun shortcuts(entry: AppEntry): List<ShortcutInfo> = withContext(Dispatchers.IO) {
        val la = launcherApps ?: return@withContext emptyList()
        runCatching {
            val q = LauncherApps.ShortcutQuery()
                .setPackage(entry.id.pkg)
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
                )
            la.getShortcuts(q, entry.user).orEmpty()
        }.getOrDefault(emptyList())
    }

    fun shortcut(id: Pin.Shortcut): ShortcutInfo? {
        val la = launcherApps ?: return null
        val user = userForSerial(id.user)
        return runCatching {
            val q = LauncherApps.ShortcutQuery()
                .setPackage(id.pkg)
                .setShortcutIds(listOf(id.shortcutId))
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
                )
            la.getShortcuts(q, user)?.firstOrNull()
        }.getOrNull()
    }

    suspend fun shortcutIcon(pin: Pin.Shortcut): Bitmap? {
        val cacheKey = "${pin.pkg}/${pin.shortcutId}@${pin.user}"
        shortcutIcons.get(cacheKey)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            val si = shortcut(pin) ?: return@withContext null
            runCatching { launcherApps?.getShortcutIconDrawable(si, density)?.let { drawableToBitmap(it) } }.getOrNull()
        }
        if (bmp != null) shortcutIcons.put(cacheKey, bmp)
        return bmp
    }

    fun startShortcut(pkg: String, shortcutId: String, user: UserHandle) {
        log.log("info", "shortcut", "pkg" to pkg, "id" to shortcutId)
        runCatching {
            launcherApps?.startShortcut(pkg, shortcutId, null, ActivityOptions.makeBasic().toBundle(), user)
        }.onFailure { log.log("error", "shortcut-failed", "pkg" to pkg, "error" to it.toString()) }
    }

    /** Keeps a home shortcut pinned with the system, where allowed. */
    fun pinShortcut(pkg: String, shortcutId: String, user: UserHandle) {
        runCatching { launcherApps?.pinShortcuts(pkg, listOf(shortcutId), user) }
            .onFailure { log.log("info", "pin-shortcut-skipped", "pkg" to pkg, "error" to it.javaClass.simpleName) }
    }

    fun isDefaultHome(): Boolean {
        val home = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        val resolved = ctx.packageManager.resolveActivity(home, 0)
        return resolved?.activityInfo?.packageName == ctx.packageName
    }
}
