package uk.ccez.launcher.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cold start and return-to-home (PLAN quality gates). Run with ANDROID_SERIAL set. */
@RunWith(AndroidJUnit4::class)
class StartupBench {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun coldStart() = rule.measureRepeated(
        packageName = "uk.ccez.launcher",
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Partial(),
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.pkg("uk.ccez.launcher")), 5_000)
    }

    @Test fun returnHome() = rule.measureRepeated(
        packageName = "uk.ccez.launcher",
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.Partial(),
    ) {
        startActivityAndWait()
        pressHome()
        device.wait(Until.hasObject(By.pkg("uk.ccez.launcher")), 5_000)
    }
}
