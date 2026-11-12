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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

    private val icons = LruCache<String, Bitmap>(256)
    private val shortcutIcons = LruCache<String, Bitmap>(128)

    /** The profiles (serials) whose apps the last refresh listed in full:
     * on, not paused, with apps. A pin elsewhere is left alone (its app
     * may only be hidden behind a paused work profile). */
    @Volatile var listedUsers: Set<Long> = emptySet()
        private set

    /** The Android 15 Private space profile, when the phone has one. */
    @Volatile var privateUser: UserHandle? = null
        private set

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(pkg: String?, user: UserHandle?) = refresh()
        override fun onPackageAdded(pkg: String?, user: UserHandle?) = refresh()
        override fun onPackageChanged(pkg: String?, user: UserHandle?) = refresh()
        override fun onPackagesAvailable(pkgs: Array<out String>?, user: UserHandle?, replacing: Boolean) = refresh()
        override fun onPackagesUnavailable(pkgs: Array<out String>?, user: UserHandle?, replacing: Boolean) = refresh()
        override fun onPackagesSuspended(pkgs: Array<out String>?, user: UserHandle?) = refresh()
        override fun onPackagesUnsuspended(pkgs: Array<out String>?, user: UserHandle?) = refresh()
        override fun onShortcutsChanged(pkg: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) {
            shortcutsVersion.value += 1
        }
    }

    fun start() {
        runCatching { launcherApps?.registerCallback(callback) }
        refresh()
    }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val started = System.nanoTime()
            val la = launcherApps ?: return@launch
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
                val list = runCatching { la.getActivityList(null, user) }.getOrNull().orEmpty()
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
            icons.evictAll()
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

    fun byKey(key: String): AppEntry? = apps.value.firstOrNull { it.key == key }
    fun byId(id: AppId): AppEntry? = apps.value.firstOrNull { it.id == id }

    fun userForSerial(serial: Long): UserHandle =
        runCatching { userManager?.getUserForSerialNumber(serial) }.getOrNull() ?: Process.myUserHandle()

    /** An app's icon, badged for its profile. Cached; call from the UI. */
    suspend fun icon(entry: AppEntry): Bitmap? {
        icons.get(entry.key)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                var d: Drawable = entry.info.getIcon(density)
                if (entry.otherProfile) {
                    runCatching { d = ctx.packageManager.getUserBadgedIcon(d, entry.user) }
                }
                drawableToBitmap(d)
            }.getOrNull()
        }
        if (bmp != null) icons.put(entry.key, bmp)
        return bmp
    }

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
