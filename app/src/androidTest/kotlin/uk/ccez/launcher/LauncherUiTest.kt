package uk.ccez.launcher

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Home, drawer, search and settings, through the real screens. */
@RunWith(AndroidJUnit4::class)
class LauncherUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        // Like check.sh sets up: the home role held and contacts granted,
        // so no first-run dialog sits over the screens under test.
        @JvmStatic @org.junit.BeforeClass fun deviceReady() {
            val ui = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            ui.executeShellCommand("cmd role add-role-holder android.app.role.HOME uk.ccez.launcher").close()
            ui.executeShellCommand("pm grant uk.ccez.launcher android.permission.READ_CONTACTS").close()
            // Plain names and clock while screens are under test.
            App.instance.settings.set("words.app_name_langs", "false")
            App.instance.settings.set("words.clock_lang", "false")
        }
    }

    @Test fun homeShowsClockAndPins() {
        rule.onNodeWithTag("home").assertIsDisplayed()
        rule.onNodeWithTag("clock").assertIsDisplayed()
        rule.waitForIdle()
    }

    @Test fun swipeUpOpensDrawerSearchAnswersSums() {
        rule.onNodeWithTag("home-empty").performTouchInput { swipeUp() }
        rule.onNodeWithTag("drawer").assertIsDisplayed()
        rule.onNodeWithTag("search").assertIsDisplayed()
        rule.onNodeWithTag("search").performTextInput("2+3*4")
        rule.onNodeWithTag("result-calc").assertIsDisplayed()
        rule.onNodeWithText("14").assertIsDisplayed()
        rule.onNodeWithTag("search").performTextClearance()
        rule.onNodeWithTag("search").performTextInput("5 km to mi")
        rule.onNodeWithTag("result-unit").assertIsDisplayed()
    }

    @Test fun searchFindsAppsAndSettingsPages() {
        rule.onNodeWithTag("home-empty").performTouchInput { swipeUp() }
        rule.onNodeWithTag("search").performTextInput("bluetooth")
        rule.onNodeWithTag("result-settings").assertIsDisplayed()
        rule.onNodeWithTag("search").performTextClearance()
        rule.onNodeWithTag("search").performTextInput("sett")
        rule.onAllNodesWithText("Settings")[0].assertIsDisplayed()
    }

    @Test fun hideMovesAnAppToTheHiddenSpaceOnly() {
        rule.onNodeWithTag("home-empty").performTouchInput { swipeUp() }
        rule.onNodeWithTag("drawer-grid").assertIsDisplayed()
        // Long-press the first app cell: its menu offers Hide.
        val cells = rule.onAllNodesWithText("Camera")
        cells[0].performTouchInput { longClick() }
        rule.onNodeWithTag("menu-hide").performClick()
        // Gone from the drawer, present in the hidden space.
        rule.onNodeWithTag("drawer").assertIsDisplayed()
        rule.waitForIdle()
        // Restore through the store (the menu path is the same update):
        // unhide, and put the pin back Hide took off home.
        val camera = App.instance.apps.apps.value.firstOrNull { it.label == "Camera" }
        App.instance.layout.update { d ->
            d.hidden.removeAll { it.contains("camera") }
            if (camera != null && d.pins.none { (it as? Pin.App)?.id == camera.id }) {
                d.pins.add(Pin.App(camera.id))
            }
        }
    }

    @Test fun holdingOnePinOnAnotherMakesAFolder() {
        // Two app pins on home (seeded on first run).
        val before = App.instance.layout.data.value.pins.filterIsInstance<Pin.App>()
        org.junit.Assume.assumeTrue("needs two seeded pins", before.size >= 2)
        val origin = rule.onNodeWithTag("pins").fetchSemanticsNode().boundsInRoot.topLeft
        val first = rule.onNodeWithTag("app:${before[0].id.key()}").fetchSemanticsNode().boundsInRoot.center - origin
        val second = rule.onNodeWithTag("app:${before[1].id.key()}").fetchSemanticsNode().boundsInRoot.center - origin
        rule.onNodeWithTag("pins").performTouchInput {
            down(first)
            advanceEventTime(800)
            moveTo(second)
            advanceEventTime(200)
            up()
        }
        rule.waitForIdle()
        val after = App.instance.layout.data.value
        org.junit.Assert.assertTrue(
            "a folder pin appears",
            after.pins.any { it is Pin.FolderRef },
        )
        // Put it back: dissolve every folder.
        App.instance.layout.update { d ->
            val out = mutableListOf<Pin>()
            for (p in d.pins) {
                if (p is Pin.FolderRef) out.addAll(d.folders[p.folderId]?.items.orEmpty()) else out.add(p)
            }
            d.pins.clear()
            d.pins.addAll(out)
            d.folders.clear()
        }
    }

    @Test fun aLearningWordShowsOnHomeAndTapGradesItAway() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val e = Words.engine(ctx)
        // A real word (the core saves nothing it can't find in the
        // dictionary), a different one most runs: a graded word keeps its
        // FSRS card and isn't due again for a while.
        val word = freshWord()
        e.setWordStatus("fr", word, uk.ccez.keys.core.WordStatus.LEARNING)
        // Room for every due word, this one included.
        App.instance.settings.set("words.count", "12")
        App.instance.settingsChanged()
        try {
            rule.waitUntil(timeoutMillis = 20_000) {
                rule.onAllNodesWithText(word).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText(word).performClick()
            rule.waitUntil(timeoutMillis = 10_000) {
                rule.onAllNodesWithText(word).fetchSemanticsNodes().isEmpty()
            }
            // Graded Good: the core no longer offers it right away.
            val due = Words.screenWords(ctx, emptyList()).map { it.word }
            org.junit.Assert.assertFalse("graded word not due again", word in due)
        } finally {
            e.setWordStatus("fr", word, uk.ccez.keys.core.WordStatus.NEW)
            App.instance.settings.set("words.count", "4")
            App.instance.settingsChanged()
        }
    }

    @Test fun searchShowsAWordCardWithLearningAndKnown() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val ready = kotlinx.coroutines.runBlocking { Dictionaries.ensure(ctx, "fr") || Dictionaries.ready(ctx, "fr") }
        org.junit.Assume.assumeTrue("French dictionary needs the network", ready)
        try {
            rule.onNodeWithTag("home-empty").performTouchInput { swipeUp() }
            rule.onNodeWithTag("search").performTextInput("libre")
            rule.waitUntil(timeoutMillis = 15_000) {
                rule.onAllNodesWithTag("word-card").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("word-known").performClick()
            rule.waitUntil(timeoutMillis = 10_000) {
                Words.engine(ctx).wordStatus("fr", "libre") == uk.ccez.keys.core.WordStatus.KNOWN
            }
        } finally {
            Words.engine(ctx).setWordStatus("fr", "libre", uk.ccez.keys.core.WordStatus.NEW)
        }
    }

    @Test fun aGatedAppAsksOneWordFirst() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val e = Words.engine(ctx)
        val gateWord = freshWord()
        e.setWordStatus("fr", gateWord, uk.ccez.keys.core.WordStatus.LEARNING)
        App.instance.settings.set("words.count", "12")
        App.instance.settingsChanged()
        val camera = App.instance.apps.apps.value.firstOrNull { it.label == "Camera" }
        org.junit.Assume.assumeTrue("needs Camera", camera != null)
        App.instance.layout.update { it.gates.add(camera!!.key) }
        try {
            rule.onNodeWithTag("app:${camera!!.key}").performClick()
            rule.waitUntil(timeoutMillis = 15_000) {
                rule.onAllNodesWithTag("gate").fetchSemanticsNodes().isNotEmpty()
            }
            val dueBefore = Words.screenWords(ctx, emptyList()).map { it.word }
            rule.onNodeWithTag("gate-good").performClick()
            // The app opens over us; the core tells whether the grade landed.
            val deadline = System.currentTimeMillis() + 10_000
            var graded = false
            while (System.currentTimeMillis() < deadline) {
                val dueAfter = Words.screenWords(ctx, emptyList()).map { it.word }
                if (dueAfter.size < dueBefore.size) {
                    graded = true
                    break
                }
                Thread.sleep(250)
            }
            org.junit.Assert.assertTrue("one fewer word due after Good", graded)
        } finally {
            App.instance.layout.update { it.gates.remove(camera!!.key) }
            e.setWordStatus("fr", gateWord, uk.ccez.keys.core.WordStatus.NEW)
            App.instance.settings.set("words.count", "4")
            App.instance.settingsChanged()
        }
    }

    @Test fun theLatestCopyShowsAsAWordToSave() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val ready = kotlinx.coroutines.runBlocking { Dictionaries.ensure(ctx, "fr") || Dictionaries.ready(ctx, "fr") }
        org.junit.Assume.assumeTrue("French dictionary needs the network", ready)
        val e = Words.engine(ctx)
        e.setWordStatus("fr", "libre", uk.ccez.keys.core.WordStatus.NEW)
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("", "libre"))
        // Home reads the copy when it comes back to the front.
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        try {
            rule.waitUntil(timeoutMillis = 15_000) {
                rule.onAllNodesWithTag("clip-strip").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("clip-save").performClick()
            rule.waitUntil(timeoutMillis = 10_000) {
                e.wordStatus("fr", "libre") == uk.ccez.keys.core.WordStatus.LEARNING
            }
        } finally {
            e.setWordStatus("fr", "libre", uk.ccez.keys.core.WordStatus.NEW)
        }
    }

    @Test fun longPressOnEmptyHomeOpensSettings() {
        rule.onNodeWithTag("home-empty").performTouchInput { longClick() }
        rule.onNodeWithTag("settings").assertIsDisplayed()
        rule.onNodeWithTag("setting:words.count").assertIsDisplayed()
    }

    /** A common French word picked at random: the core keeps only words its
     * dictionary knows, and one graded in an earlier run may not be due. */
    private fun freshWord(): String = listOf(
        "maison", "fenêtre", "cuisine", "voiture", "chemin", "montagne", "rivière", "forêt", "nuage", "orage",
        "jardin", "village", "marché", "boulanger", "fromage", "poisson", "cerise", "citron", "farine", "beurre",
        "bouteille", "chaise", "armoire", "couteau", "fourchette", "assiette", "serviette", "oreiller", "couverture", "lampe",
        "écharpe", "manteau", "chaussure", "chapeau", "ceinture", "parapluie", "valise", "billet", "gare", "avion",
        "bateau", "vélo", "camion", "moteur", "roue", "pont", "tunnel", "colline", "plage", "sable",
        "falaise", "étoile", "lune", "soleil", "printemps", "automne", "hiver", "glace", "neige", "vent",
    ).random()
}
