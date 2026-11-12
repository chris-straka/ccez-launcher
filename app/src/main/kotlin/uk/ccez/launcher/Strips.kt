package uk.ccez.launcher

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.ccez.keys.core.WordStatus

/**
 * The Stage 6 strips: today's quiet numbers, the latest copy as a word,
 * carrying on in ccez-tube, and asking ccez-llm. Each shows only when
 * it has something to say.
 */
@Composable fun TodayStrip() {
    val reviewed by Today.reviewed.collectAsState()
    if (reviewed <= 0) return
    Text(
        if (reviewed == 1) "1 word today" else "$reviewed words today",
        color = Palette.faint, fontSize = Look.smallSize,
        modifier = Modifier.padding(horizontal = Look.margin, vertical = 4.dp).testTag("today"),
    )
}

@Composable fun ClipStrip() {
    val app = App.instance
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    var dismissed by remember { mutableStateOf("") }
    val clip by produceState(LastCopy.text, tick) {
        value = runCatching { LastCopy.read(ctx) }.getOrDefault("")
    }
    val text = clip.trim()
    if (text.isEmpty() || text.length > 48 || '\n' in text || text == dismissed) return
    if (text.split(Regex("\\s+")).size > 3) return
    val hit by produceState<WordHit?>(null, text) {
        value = withContext(Dispatchers.IO) {
            val e = Words.engineOrNull() ?: return@withContext null
            val langs = runCatching { e.settingStrings("languages.learning") }.getOrDefault(emptyList())
            var found: WordHit? = null
            for (lang in langs) {
                val card = runCatching { e.wordCard(lang, text, "") }.getOrNull()
                if (card != null) {
                    found = WordHit(lang, text, card)
                    break
                }
            }
            found
        }
    }
    val word = hit ?: return
    val head = word.card.lemma.ifEmpty { word.card.word }
    var saved by remember(text) { mutableStateOf(word.card.status == WordStatus.LEARNING) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Look.margin, vertical = 4.dp)
            .clip(RoundedCornerShape(Look.radius)).background(Palette.cell)
            .padding(start = Look.inset, end = 8.dp, top = 10.dp, bottom = 10.dp)
            .testTag("clip-strip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                head, color = Palette.fg, fontSize = Look.bodySize, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            word.card.meanings.firstOrNull()?.let {
                Text(it, color = Palette.dim, fontSize = Look.smallSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            if (saved) "Learning" else "Save",
            color = if (saved) Palette.dim else Palette.accent,
            fontSize = 14.sp,
            modifier = Modifier.clickable {
                if (!saved) {
                    Words.setStatus(ctx, word.lang, head, WordStatus.LEARNING, app.scope)
                    WordSync.soon(ctx)
                    saved = true
                }
            }.padding(6.dp).testTag("clip-save"),
        )
        Text(
            "×", color = Palette.faint, fontSize = 18.sp,
            modifier = Modifier.clickable { dismissed = text }.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** Carry on in ccez-tube: it resumes at its own place. Only when installed. */
@Composable fun TubeStrip(onLaunch: (AppEntry) -> Unit) {
    val app = App.instance
    val apps by app.apps.apps.collectAsState()
    val tube = apps.firstOrNull { it.id.pkg == "uk.ccez.tube" && !it.otherProfile } ?: return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Look.margin, vertical = 4.dp)
            .clip(RoundedCornerShape(Look.radius)).background(Palette.cell)
            .clickable { onLaunch(tube) }
            .heightIn(min = Look.field)
            .padding(horizontal = Look.inset, vertical = 10.dp)
            .testTag("tube-strip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIconImage(tube, Modifier.size(26.dp))
        Text(
            AppNames.nameFor(tube), color = Palette.fg, fontSize = 15.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Text("Continue", color = Palette.dim, fontSize = Look.smallSize)
    }
}

/** Ask ccez-llm, through its `ccez-llm://send?text=` deep link. */
@Composable fun LlmAskStrip() {
    val app = App.instance
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    PillField(
        value = text,
        onValueChange = { text = it },
        placeholder = "Ask",
        modifier = Modifier.padding(horizontal = Look.margin, vertical = 4.dp).testTag("llm-ask"),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = {
            val q = text.trim()
            if (q.isNotEmpty()) {
                runCatching {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("ccez-llm://send?text=${Uri.encode(q)}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { app.launcherLog.log("warn", "llm-ask-failed", "error" to it.toString()) }
                text = ""
            }
        }),
    )
}

/**
 * The latest copy, read only when something new was copied: Android says
 * "ccez-launcher pasted from your clipboard" on every read, and home came
 * back many times a minute (2026-11-11). The clip's description (its time,
 * its kind) is read freely; the text only once per copy, and never what
 * isn't text.
 */
private object LastCopy {
    var text = ""
    private var seen = -1L

    fun read(ctx: android.content.Context): String {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return ""
        val desc = cm.primaryClipDescription ?: return "".also { text = ""; seen = -1L }
        val at = desc.timestamp
        if (at == seen) return text
        seen = at
        text = if (desc.hasMimeType("text/*")) {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
        } else {
            ""
        }
        return text
    }
}
