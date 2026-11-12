package uk.ccez.launcher

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Swipe up: the drawer (A–Z) with search at the top. Swipe right in it:
 * the hidden space — hidden apps and Private space, nowhere else, in
 * no search. Behind a fingerprint when its setting says so.
 */
@Composable fun DrawerScreen(
    onClose: () -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onMenu: (MenuTarget) -> Unit,
) {
    BackHandler(onBack = onClose)
    val pager = rememberPagerState(initialPage = 1, pageCount = { 2 })
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(pager.currentPage) {
        if (pager.currentPage == 0) keyboard?.hide()
    }
    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().background(Palette.bg)) { page ->
        if (page == 0) {
            HiddenPage(onLaunch = onLaunch, onMenu = onMenu)
        } else {
            DrawerPage(onClose = onClose, onLaunch = onLaunch, onMenu = onMenu)
        }
    }
}

@Composable private fun ResultRow(title: String, sub: String, tag: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp).testTag(tag),
    ) {
        Text(title, color = Palette.fg, fontSize = 16.sp)
        if (sub.isNotBlank()) Text(sub, color = Palette.dim, fontSize = 13.sp)
    }
}

@Composable private fun DrawerPage(
    onClose: () -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onMenu: (MenuTarget) -> Unit,
) {
    val app = App.instance
    val ctx = LocalContext.current
    val apps by app.apps.apps.collectAsState()
    val layout by app.layout.data.collectAsState()
    val settingsTick by app.settings.version.collectAsState()
    var query by remember { mutableStateOf("") }
    // Opening the drawer leaves search alone: no focus, no keyboard
    // (owner, 2026-11-12). A tap on the field brings both.
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { keyboard?.hide() }

    val privateUser = app.apps.privateUser
    val shownNames by AppNames.shown.collectAsState()
    val visible = remember(apps, layout.hidden, privateUser) {
        apps.filter { it.key !in layout.hidden && it.user != privateUser }
    }
    val calc = remember(query) { Calc.eval(query) }
    val unit = remember(query) { Units.convert(query) }
    // Search finds an app by its usual name or the name shown today.
    val appHits = remember(visible, query, shownNames) {
        if (query.isBlank()) {
            emptyList()
        } else {
            val q = normalized(query)
            visible.mapNotNull { e ->
                val r = listOf(
                    matchRank(e.label, q),
                    matchRank(shownNames[e.key] ?: e.label, q),
                ).filter { it >= 0 }.minOrNull()
                r?.let { it to e }
            }.sortedWith(compareBy({ it.first }, { normalized(it.second.label) })).map { it.second }
        }
    }
    val settingsHits = remember(query) { settingsMatches(query) }
    val wantContacts = remember(settingsTick) { app.settings.bool("search.contacts") }
    val contacts by produceState<List<ContactHit>>(emptyList(), query, wantContacts) {
        value = if (wantContacts) contactMatches(ctx, query) else emptyList()
    }
    // Stage 3: a word in a learning language shows its card here too.
    val wordCard = rememberWordCard(query)

    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().testTag("drawer")) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("search"),
            singleLine = true,
            placeholder = { Text("Search", color = Palette.faint) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                appHits.firstOrNull()?.let { first ->
                    onLaunch(first)
                    onClose()
                }
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Palette.fg,
                unfocusedTextColor = Palette.fg,
                focusedBorderColor = Palette.dim,
                unfocusedBorderColor = Palette.line,
                cursorColor = Palette.accent,
            ),
        )
        if (query.isBlank()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(76.dp),
                modifier = Modifier.fillMaxSize().testTag("drawer-grid"),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                items(visible, key = { it.key }) { e ->
                    Box(contentAlignment = Alignment.Center) {
                        AppCell(
                            e,
                            onClick = { onLaunch(e); onClose() },
                            onLongClick = { onMenu(MenuTarget.DrawerApp(e)) },
                            labelOverride = shownNames[e.key],
                        )
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().testTag("search-results")) {
                if (calc != null) {
                    item {
                        ResultRow(calc, query.trim(), "result-calc") {
                            clipboard.setText(AnnotatedString(calc))
                            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                            app.launcherLog.log("info", "search-calc")
                        }
                    }
                }
                if (unit != null) {
                    item {
                        ResultRow(unit.text, "", "result-unit") {
                            clipboard.setText(AnnotatedString(unit.value + " " + unit.unit))
                            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                            app.launcherLog.log("info", "search-unit")
                        }
                    }
                }
                items(appHits, key = { it.key }) { e ->
                    ResultRow(shownNames[e.key] ?: e.label, if (e.otherProfile) "Work" else "", "result-app") {
                        onLaunch(e)
                        onClose()
                    }
                }
                // Stage 3 word card slots in here (WordCardUi.kt).
                wordCard?.let { wc ->
                    item { WordCardRow(wc) }
                }
                items(contacts, key = { it.lookupUri.toString() }) { c ->
                    ResultRow(c.name, "Contact", "result-contact") {
                        openContact(ctx, c)
                        onClose()
                    }
                }
                items(settingsHits, key = { it.action }) { p ->
                    ResultRow(p.name, "Settings", "result-settings") {
                        openSettingsPage(ctx, p)
                        onClose()
                    }
                }
            }
        }
    }
}

@Composable private fun HiddenPage(
    onLaunch: (AppEntry) -> Unit,
    onMenu: (MenuTarget) -> Unit,
) {
    val app = App.instance
    val ctx = LocalContext.current
    val apps by app.apps.apps.collectAsState()
    val layout by app.layout.data.collectAsState()
    val settingsTick by app.settings.version.collectAsState()
    val gateOn = remember(settingsTick) { app.settings.bool("hidden.biometric") }
    var unlocked by remember { mutableStateOf(false) }
    val canBiometric = remember {
        BiometricManager.from(ctx).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }
    val locked = gateOn && canBiometric && !unlocked

    fun askFingerprint() {
        val activity = ctx as? FragmentActivity ?: return
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(ctx),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                    app.launcherLog.log("info", "hidden-unlocked")
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    app.launcherLog.log("info", "hidden-unlock-failed", "code" to code)
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Hidden apps")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        prompt.authenticate(info)
    }

    Column(Modifier.fillMaxSize().padding(top = 18.dp).testTag("hidden")) {
        Text("Hidden", color = Palette.fg, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 16.dp))
        if (locked) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(
                    onClick = { askFingerprint() },
                    modifier = Modifier.testTag("hidden-unlock"),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.cell, contentColor = Palette.fg),
                ) { Text("Unlock") }
            }
            return@Column
        }
        val hiddenApps = apps.filter { it.key in layout.hidden }
        val privateUser = app.apps.privateUser
        val privateApps = if (privateUser != null) apps.filter { it.user == privateUser } else emptyList()
        LazyVerticalGrid(
            columns = GridCells.Adaptive(76.dp),
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("hidden-grid"),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            items(hiddenApps, key = { it.key }) { e ->
                Box(contentAlignment = Alignment.Center) {
                    AppCell(
                        e,
                        onClick = { onLaunch(e) },
                        onLongClick = { onMenu(MenuTarget.HiddenApp(e)) },
                    )
                }
            }
            items(privateApps, key = { it.key }) { e ->
                Box(contentAlignment = Alignment.Center) {
                    AppCell(
                        e,
                        onClick = { onLaunch(e) },
                        onLongClick = { onMenu(MenuTarget.HiddenApp(e)) },
                    )
                }
            }
        }
        if (privateUser != null && app.apps.isQuiet(privateUser)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Private space", color = Palette.dim, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Button(
                    onClick = { app.apps.requestProfileUnlock(privateUser) },
                    modifier = Modifier.testTag("private-unlock"),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.cell, contentColor = Palette.fg),
                ) { Text("Unlock") }
            }
        } else if (privateUser != null) {
            Text(
                "Private space",
                color = Palette.faint,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}
