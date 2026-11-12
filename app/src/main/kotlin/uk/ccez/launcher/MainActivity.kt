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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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

/**
 * The drawer rises a little as it fades in and sinks back as it goes;
 * home just fades under it. Short: the swipe already said where it goes.
 */
fun screenMotion(from: Screen, to: Screen): ContentTransform {
    val ease = FastOutSlowInEasing
    return when {
        to == Screen.DRAWER ->
            (fadeIn(tween(200, easing = ease)) + slideInVertically(tween(260, easing = ease)) { it / 14 }) togetherWith
                fadeOut(tween(120))
        from == Screen.DRAWER ->
            fadeIn(tween(200, delayMillis = 40)) togetherWith
                (fadeOut(tween(150, easing = ease)) + slideOutVertically(tween(200, easing = ease)) { it / 20 })
        else -> fadeIn(tween(180)) togetherWith fadeOut(tween(120))
    }
}

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
        screen = screenAsked(intent)
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
                        else -> AnimatedContent(
                            targetState = screen,
                            transitionSpec = { screenMotion(initialState, targetState) },
                            label = "screen",
                        ) { s ->
                            when (s) {
                                Screen.HOME -> HomeScreen(
                                    onOpenDrawer = { screen = Screen.DRAWER },
                                    onOpenSettings = { screen = Screen.SETTINGS },
                                    onSetHome = { requestHomeRole(this@MainActivity) },
                                    onShade = { expandShade() },
                                    onDoubleTap = { doubleTapLock() },
                                    onLaunch = launch,
                                    onMenu = openMenu,
                                )
                                Screen.DRAWER -> DrawerScreen(
                                    onClose = { screen = Screen.HOME },
                                    onLaunch = launch,
                                    onMenu = openMenu,
                                )
                                Screen.SETTINGS -> SettingsScreen(
                                    onBack = { screen = Screen.HOME },
                                    onSetHome = { requestHomeRole(this@MainActivity) },
                                    onAddWidget = { startAddWidget() },
                                )
                            }
                        }
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
        // The home button: back to home, menus away. `launcher drawer`
        // (AgentCommands) comes the same way, asking for the drawer.
        screen = screenAsked(intent)
        menuTarget = null
        gateWord = null
    }

    private fun screenAsked(intent: Intent?): Screen {
        val asked = intent?.getStringExtra(AgentCommands.EXTRA_SCREEN) ?: return Screen.HOME
        App.instance.launcherLog.log("info", "screen", "asked" to asked)
        return if (asked == "drawer") Screen.DRAWER else Screen.HOME
    }

    private var pendingWidgetId: Int = android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID

    private val pickWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val id = r.data?.getIntExtra(
            android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID,
            android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: pendingWidgetId
        if (r.resultCode == android.app.Activity.RESULT_OK && id != android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) {
            afterPickWidget(id)
            // pendingWidgetId stays set only while configuring (afterPickWidget owns it).
            if (pendingWidgetId == id && Widgets.info(this, WidgetEntry(id, "", ""))?.configure == null) {
                pendingWidgetId = android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
            }
        } else if (pendingWidgetId != android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) {
            runCatching { Widgets.host(this).deleteAppWidgetId(pendingWidgetId) }
            pendingWidgetId = android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
        }
    }

    private val configureWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val id = pendingWidgetId
        pendingWidgetId = android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
        if (r.resultCode == android.app.Activity.RESULT_OK && id != android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) {
            Widgets.addAllocated(this, id)
            screen = Screen.HOME
        } else if (id != android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) {
            runCatching { Widgets.host(this).deleteAppWidgetId(id) }
        }
    }

    /** Settings → Add widget: pick one, bind it, configure it if it asks. */
    fun startAddWidget() {
        pendingWidgetId = Widgets.host(this).allocateAppWidgetId()
        val pick = Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_PICK)
            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId)
        runCatching { pickWidget.launch(pick) }
            .onFailure { App.instance.launcherLog.log("warn", "widget-pick-failed", "error" to it.toString()) }
    }

    private fun afterPickWidget(id: Int) {
        val mgr = Widgets.manager(this)
        val info = runCatching { mgr.getAppWidgetInfo(id) }.getOrNull()
        if (info == null) {
            // Picked but not bound yet: ask Android to bind it for us.
            // The pick result carries the provider Android chose... if it
            // didn't bind, there's nothing to host; drop the id.
            runCatching { Widgets.host(this).deleteAppWidgetId(id) }
            App.instance.launcherLog.log("warn", "widget-not-bound")
            return
        }
        val configure = info.configure
        if (configure != null) {
            pendingWidgetId = id
            val intent = Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                .setComponent(configure)
                .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            runCatching { configureWidget.launch(intent) }
                .onFailure {
                    pendingWidgetId = android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
                    Widgets.addAllocated(this, id)
                    screen = Screen.HOME
                }
        } else {
            Widgets.addAllocated(this, id)
            screen = Screen.HOME
        }
    }

    override fun onStart() {
        super.onStart()
        Widgets.startListening(this)
    }

    override fun onStop() {
        Widgets.stopListening(this)
        super.onStop()
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
        AppNames.maybeRefresh(this)
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
