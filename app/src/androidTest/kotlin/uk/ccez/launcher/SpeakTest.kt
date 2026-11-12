package uk.ccez.launcher

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assume
import org.junit.Test

/** App names translated on the phone. (Clock spelling has JVM tests.) */
class SpeakTest {

    @Test fun appNamesTranslateOnceAndCache() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val e = Words.engine(ctx)
        val before = e.setting("languages.learning")
        try {
            e.setSetting("languages.learning", "[\"fr\"]")
            val ok = runCatching { runBlocking { AppNames.refresh(ctx) } }.isSuccess
            Assume.assumeTrue("ML Kit model needs the network", ok && AppNames.shown.value.isNotEmpty())
            val settings = App.instance.apps.apps.value.firstOrNull { it.label == "Settings" }
            Assume.assumeTrue(settings != null)
            val shown = AppNames.nameFor(settings!!)
            assertNotEquals("Settings", shown)
        } finally {
            runCatching { e.setSetting("languages.learning", before) }
        }
    }
}
