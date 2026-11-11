package uk.ccez.launcher

import androidx.compose.runtime.Composable
import uk.ccez.keys.core.ScreenWord

/** A word before a time-sink app (Stage 4). Stage 4 fills this in. */
data class GateWord(val entry: AppEntry, val word: ScreenWord)

/** True when a gate word took the launch over. Stage 4. */
fun MainActivity.tryGateWord(entry: AppEntry): Boolean = false

@Composable fun GateWordDialog(gate: GateWord) {
    // Stage 4.
}
