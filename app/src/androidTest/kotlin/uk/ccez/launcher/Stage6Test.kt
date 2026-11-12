package uk.ccez.launcher

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 6 pieces that don't need the screen: widgets and Today. */
class Stage6Test {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun widgetProvidersExist() {
        assertTrue("the system has widgets to host", Widgets.providers(ctx).isNotEmpty())
    }

    @Test fun widgetsSurviveLayoutRoundTrip() {
        val d = LayoutData(widgets = mutableListOf(WidgetEntry(42, "com.example", ".Widget")))
        val back = LayoutData.parse(d.toJson())
        assertEquals(d.widgets, back.widgets)
    }

    @Test fun todayCountsReviews() {
        Today.load(ctx)
        val before = Today.reviewed.value
        Today.bump(ctx)
        assertEquals(before + 1, Today.reviewed.value)
    }
}
