package uk.ccez.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uk.ccez.keys.core.Grade
import uk.ccez.keys.core.ScreenWord

/** One due word standing in front of a gated app (Stage 4). */
data class GateWord(val entry: AppEntry, val word: ScreenWord)

/**
 * A gated app tapped: fetch one due word (briefly — a slow fetch never
 * holds the launch up) and put it up. True when the launch waits on the
 * word. A second tap on the same app goes straight through.
 */
fun MainActivity.tryGateWord(entry: AppEntry): Boolean {
    val app = App.instance
    if (entry.key !in app.layout.data.value.gates) return false
    if (app.settings.number("words.count") <= 0) return false
    val showing = gateWord
    if (showing != null && showing.entry.key == entry.key) {
        gateWord = null
        return false
    }
    app.scope.launch {
        val word = withTimeoutOrNull(800) {
            withContext(Dispatchers.IO) { Words.screenWords(app, emptyList()).firstOrNull() }
        }
        withContext(Dispatchers.Main) {
            if (word != null) {
                gateWord = GateWord(entry, word)
                app.launcherLog.log("info", "gate", "pkg" to entry.id.pkg)
            } else {
                app.apps.launch(entry)
            }
        }
    }
    return true
}

@Composable private fun GradeButton(text: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.cell)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag(tag),
    ) {
        Text(text, color = Palette.fg, fontSize = 16.sp)
    }
}

/** The word before the app: grade it (or open anyway), then the app opens. */
@Composable fun MainActivity.GateDialog(gate: GateWord) {
    val app = App.instance
    var show by remember(gate) { mutableStateOf(false) }

    fun open(grade: Grade?) {
        if (grade != null) {
            Words.grade(app, gate.word, grade, app.scope)
            WordSync.soon(app)
        }
        gateWord = null
        app.apps.launch(gate.entry)
    }

    BackHandler { gateWord = null }
    Box(
        Modifier.fillMaxSize().background(Palette.bg).testTag("gate"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                gate.entry.label,
                color = Palette.accent, fontSize = 15.sp,
                modifier = Modifier.clickable { open(null) }
                    .semantics { contentDescription = "Open ${gate.entry.label}" }
                    .testTag("gate-open")
                    .padding(8.dp),
            )
            Spacer(Modifier.height(30.dp))
            Column(
                Modifier.pointerInput(gate) {
                    detectTapGestures(onTap = { show = !show })
                }
                    .semantics {
                        contentDescription = "${gate.word.shown}, ${gate.word.meaning}"
                    }
                    .testTag("gate-word"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(gate.word.shown, color = Palette.fg, fontSize = 38.sp, textAlign = TextAlign.Center)
                Box(Modifier.height(28.dp)) {
                    if (show && gate.word.meaning.isNotBlank()) {
                        Text(gate.word.meaning, color = Palette.dim, fontSize = 17.sp, textAlign = TextAlign.Center)
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GradeButton("Again", "gate-again") { open(Grade.AGAIN) }
                GradeButton("Good", "gate-good") { open(Grade.GOOD) }
                GradeButton("Easy", "gate-easy") { open(Grade.EASY) }
            }
        }
    }
}
