package uk.ccez.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.delay

enum class Screen { HOME, DRAWER, SETTINGS }

/** The HOME activity: home, drawer (swipe up), settings (hold on empty home). */
class MainActivity : FragmentActivity() {
    private var screen by mutableStateOf(Screen.HOME)
    private var menuTarget by mutableStateOf<MenuTarget?>(null)

    // Stage 4 fills this in: the word shown before a gated app.
    internal var gateWord by mutableStateOf<GateWord?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = App.instance
        setContent {
            val layout by app.layout.data.collectAsState()
            val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            LaunchedEffect(Unit) {
                if (app.settings.bool("search.contacts") &&
                    checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
                ) {
                    askContacts.launch(android.Manifest.permission.READ_CONTACTS)
                }
                // Ask to be the home app once; Settings keeps the button after.
                val prefs = getSharedPreferences("launcher", Context.MODE_PRIVATE)
                if (!app.apps.isDefaultHome() && !prefs.getBoolean("asked_home", false)) {
                    prefs.edit().putBoolean("asked_home", true).apply()
                    delay(700)
                    requestHomeRole(this@MainActivity)
                }
            }

            val launch: (AppEntry) -> Unit = { e -> launchApp(e) }
            val openMenu: (MenuTarget) -> Unit = { menuTarget = it }

            Box(Modifier.fillMaxSize().background(Palette.bg).safeDrawingPadding()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val beside = screen == Screen.DRAWER && maxWidth >= 840.dp && maxWidth > maxHeight
                    when {
                        beside -> Row(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f)) {
                                HomeScreen(
                                    onOpenDrawer = {},
                                    onOpenSettings = { screen = Screen.SETTINGS },
                                    onSetHome = { requestHomeRole(this@MainActivity) },
                                    onShade = { expandShade() },
                                    onDoubleTap = { doubleTapLock() },
                                    onLaunch = launch,
                                    onMenu = openMenu,
                                )
                            }
                            Box(Modifier.weight(1.2f)) {
                                DrawerScreen(
                                    onClose = { screen = Screen.HOME },
                                    onLaunch = launch,
                                    onMenu = openMenu,
                                )
                            }
                        }
                        screen == Screen.HOME -> HomeScreen(
                            onOpenDrawer = { screen = Screen.DRAWER },
                            onOpenSettings = { screen = Screen.SETTINGS },
                            onSetHome = { requestHomeRole(this@MainActivity) },
                            onShade = { expandShade() },
                            onDoubleTap = { doubleTapLock() },
                            onLaunch = launch,
                            onMenu = openMenu,
                        )
                        screen == Screen.DRAWER -> DrawerScreen(
                            onClose = { screen = Screen.HOME },
                            onLaunch = launch,
                            onMenu = openMenu,
                        )
                        else -> SettingsScreen(
                            onBack = { screen = Screen.HOME },
                            onSetHome = { requestHomeRole(this@MainActivity) },
                        )
                    }
                }
                gateWord?.let { g -> GateDialog(g) }
            }

            menuTarget?.let { target ->
                val entry = when (target) {
                    is MenuTarget.DrawerApp -> target.entry
                    is MenuTarget.HiddenApp -> target.entry
                    is MenuTarget.HomeApp -> target.entry
                    else -> null
                }
                val pinned = entry != null && (
                    layout.pins.any { (it as? Pin.App)?.id == entry.id } ||
                        layout.folders.values.any { f -> f.items.any { (it as? Pin.App)?.id == entry.id } }
                    )
                val gated = entry != null && entry.key in layout.gates
                AppMenuDialog(
                    target = target,
                    pinned = pinned,
                    gated = gated,
                    onDismiss = { menuTarget = null },
                    onAction = { action ->
                        menuTarget = null
                        onMenuAction(action) { screen = Screen.HOME }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The home button: back to home, menus away.
        screen = Screen.HOME
        menuTarget = null
        gateWord = null
    }

    override fun onResume() {
        super.onResume()
        App.instance.homeVisible = true
        // The keyboard's overlay leaves home to the launcher (PLAN): it
        // reads the front app already; this says it out loud too.
        runCatching {
            sendBroadcast(
                Intent("uk.ccez.launcher.HOME_VISIBLE")
                    .setPackage("uk.ccez.keys")
                    .putExtra("home", packageName),
            )
        }
        Updater.maybe(this)
    }

    override fun onPause() {
        App.instance.homeVisible = false
        runCatching {
            sendBroadcast(Intent("uk.ccez.launcher.HOME_HIDDEN").setPackage("uk.ccez.keys"))
        }
        super.onPause()
    }

    /** One place every launch goes through (Stage 4 words live here). */
    fun launchApp(entry: AppEntry) {
        if (tryGateWord(entry)) return
        App.instance.apps.launch(entry)
    }

    private fun expandShade() {
        val app = App.instance
        if (!app.settings.bool("gestures.notifications")) return
        val ok = runCatching {
            val sb = getSystemService(Context.STATUS_BAR_SERVICE)
            sb.javaClass.getMethod("expandNotificationsPanel").invoke(sb)
            true
        }.getOrDefault(false)
        if (ok) {
            app.launcherLog.log("info", "shade", "way" to "statusbar")
        } else if (LockService.notifications()) {
            app.launcherLog.log("info", "shade", "way" to "accessibility")
        } else {
            app.launcherLog.log("warn", "shade-failed")
        }
    }

    private fun doubleTapLock() {
        val app = App.instance
        if (!app.settings.bool("gestures.double_tap_lock")) return
        if (LockService.enabled(this) && LockService.lock()) {
            app.launcherLog.log("info", "lock")
        } else {
            screen = Screen.SETTINGS
        }
    }

    private fun onMenuAction(action: MenuAction, goHome: () -> Unit) {
        val app = App.instance
        when (action) {
            is MenuAction.PinApp -> app.layout.update { d ->
                if (d.pins.none { (it as? Pin.App)?.id == action.entry.id }) d.pins.add(Pin.App(action.entry.id))
            }
            is MenuAction.Unpin -> app.layout.update { d ->
                if (action.index in d.pins.indices) d.pins.removeAt(action.index)
            }
            is MenuAction.Hide -> app.layout.update { d ->
                d.hidden.add(action.entry.key)
                d.pins.removeAll { (it as? Pin.App)?.id == action.entry.id }
                d.folders.values.forEach { f -> f.items.removeAll { (it as? Pin.App)?.id == action.entry.id } }
            }
            is MenuAction.Unhide -> app.layout.update { it.hidden.remove(action.entry.key) }
            is MenuAction.Info -> runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${action.entry.id.pkg}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            is MenuAction.Uninstall -> runCatching {
                startActivity(
                    Intent(Intent.ACTION_DELETE, Uri.parse("package:${action.entry.id.pkg}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            is MenuAction.StartShortcut -> {
                app.apps.startShortcut(action.entry.id.pkg, action.shortcut.id, action.entry.user)
                goHome()
            }
            is MenuAction.PinShortcut -> {
                val si = action.shortcut
                app.apps.pinShortcut(action.entry.id.pkg, si.id, action.entry.user)
                app.layout.update { d ->
                    d.pins.add(
                        Pin.Shortcut(
                            action.entry.id.pkg, si.id, action.entry.id.user,
                            si.shortLabel?.toString().orEmpty(),
                        ),
                    )
                }
            }
            is MenuAction.ToggleGate -> app.layout.update { d ->
                if (action.on) d.gates.add(action.entry.key) else d.gates.remove(action.entry.key)
            }
            is MenuAction.RemoveFromFolder -> app.layout.update { d ->
                val f = d.folders[action.folderId] ?: return@update
                if (action.index in f.items.indices) {
                    val pin = f.items.removeAt(action.index)
                    d.pins.add(pin)
                }
                if (f.items.isEmpty()) {
                    d.folders.remove(action.folderId)
                    d.pins.removeAll { (it as? Pin.FolderRef)?.folderId == action.folderId }
                }
            }
            is MenuAction.DissolveFolder -> app.layout.update { d ->
                val ref = d.pins.getOrNull(action.index) as? Pin.FolderRef ?: return@update
                val items = d.folders.remove(ref.folderId)?.items.orEmpty()
                d.pins.removeAt(action.index)
                d.pins.addAll(action.index, items)
            }
            is MenuAction.RenameFolder -> app.layout.update { d ->
                d.folders[action.folder.id]?.name = action.name
            }
        }
        app.launcherLog.log("info", "menu", "action" to action.javaClass.simpleName)
    }
}
