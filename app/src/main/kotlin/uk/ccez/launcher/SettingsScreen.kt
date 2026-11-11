package uk.ccez.launcher

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The few settings, on the phone. Agents set the same ones over adb. */
@Composable fun SettingsScreen(
    onBack: () -> Unit,
    onSetHome: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val app = App.instance
    val ctx = LocalContext.current
    val tick by app.settings.version.collectAsState()
    val isHome = remember(tick) { app.apps.isDefaultHome() }
    val lockOn = remember(tick) { app.settings.bool("gestures.double_tap_lock") }
    val lockReady = remember(tick) { LockService.enabled(ctx) }

    Column(Modifier.fillMaxSize().background(Palette.bg).testTag("settings")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Home", color = Palette.accent, fontSize = 16.sp) }
            Text("Settings", color = Palette.fg, fontSize = 20.sp)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                SettingAction(
                    "Home app",
                    if (isHome) "This launcher" else "Not this launcher",
                    "setting-home",
                ) { onSetHome() }
            }
            items(SettingDefs.ALL, key = { it.key }) { def ->
                SettingRow(def, tick)
            }
            item {
                SettingAction(
                    "Double-tap lock service",
                    when {
                        !lockOn -> "Off in the switch above"
                        lockReady -> "On"
                        else -> "Needs turning on"
                    },
                    "setting-lock-service",
                ) {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            item {
                SettingAction("Check for update", "", "setting-update") {
                    Updater.force(ctx)
                }
            }
            item {
                Text(
                    BuildConfig.VERSION_NAME,
                    color = Palette.faint,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable private fun SettingAction(title: String, sub: String, tag: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp).testTag(tag),
    ) {
        Text(title, color = Palette.fg, fontSize = 16.sp)
        if (sub.isNotBlank()) Text(sub, color = Palette.dim, fontSize = 13.sp)
    }
}

@Composable private fun SettingRow(def: SettingDefs.Def, tick: Int) {
    val app = App.instance
    if (def.kind == SettingDefs.Kind.Text) {
        var text by androidx.compose.runtime.remember(tick, def.key) {
            androidx.compose.runtime.mutableStateOf(app.settings.get(def.key) as String)
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("setting:${def.key}"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(prettyKey(def.key), color = Palette.fg, fontSize = 16.sp)
            Text(def.about, color = Palette.dim, fontSize = 12.sp, lineHeight = 15.sp)
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                    runCatching { app.settings.set(def.key, org.json.JSONObject.quote(text.trim())); app.settingsChanged() }
                }),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Palette.fg,
                    unfocusedTextColor = Palette.fg,
                    focusedBorderColor = Palette.dim,
                    unfocusedBorderColor = Palette.line,
                    cursorColor = Palette.accent,
                ),
            )
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("setting:${def.key}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(prettyKey(def.key), color = Palette.fg, fontSize = 16.sp)
            Text(def.about, color = Palette.dim, fontSize = 12.sp, lineHeight = 15.sp)
        }
        when (val k = def.kind) {
            SettingDefs.Kind.Bool -> {
                val v = remember(tick, def.key) { app.settings.bool(def.key) }
                Switch(
                    checked = v,
                    onCheckedChange = { app.settings.set(def.key, it.toString()); app.settingsChanged() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Palette.fg,
                        checkedTrackColor = Palette.accent,
                        uncheckedThumbColor = Palette.dim,
                        uncheckedTrackColor = Palette.cell,
                    ),
                )
            }
            is SettingDefs.Kind.IntRange -> {
                val v = remember(tick, def.key) { app.settings.number(def.key) }
                TextButton(
                    onClick = { app.settings.set(def.key, (v - 1).toString()); app.settingsChanged() },
                    enabled = v > k.min,
                ) { Text("−", color = if (v > k.min) Palette.accent else Palette.faint, fontSize = 20.sp) }
                Text(v.toString(), color = Palette.fg, fontSize = 16.sp)
                TextButton(
                    onClick = { app.settings.set(def.key, (v + 1).toString()); app.settingsChanged() },
                    enabled = v < k.max,
                ) { Text("+", color = if (v < k.max) Palette.accent else Palette.faint, fontSize = 20.sp) }
            }
            SettingDefs.Kind.Text -> Unit
        }
    }
}

/** `words.count` reads "Words count" (sentence case, few words). */
private fun prettyKey(key: String): String =
    key.replace('.', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }

/** The ROLE_HOME ask, or the settings page on old Android. */
fun requestHomeRole(ctx: Context) {
    val app = App.instance
    if (android.os.Build.VERSION.SDK_INT >= 29) {
        val rm = ctx.getSystemService(android.app.role.RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) &&
            !rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)
        ) {
            runCatching {
                ctx.startActivity(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME))
                return
            }
        }
    }
    runCatching { ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { app.launcherLog.log("warn", "home-role-failed", "error" to it.toString()) }
}
