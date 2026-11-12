package uk.ccez.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uk.ccez.keys.core.Card
import uk.ccez.keys.core.WordStatus

/** A typed word found in a learning language, with the core's card for it. */
data class WordHit(val lang: String, val typed: String, val card: Card)

/**
 * The first learning language whose dictionary knows the typed word
 * (Stage 3). Lookups are the core's word_card, off the main thread.
 */
@Composable fun rememberWordCard(query: String): WordHit? {
    val hit by produceState<WordHit?>(null, query) {
        val q = query.trim()
        if (q.length < 2 || q.any { it.isDigit() || it in "+-*/%^=()" }) {
            value = null
            return@produceState
        }
        delay(200)
        value = withContext(Dispatchers.IO) {
            val e = Words.engineOrNull() ?: return@withContext null
            val langs = runCatching { e.settingStrings("languages.learning") }.getOrDefault(emptyList())
            var found: WordHit? = null
            for (lang in langs) {
                val card = runCatching { e.wordCard(lang, q, "") }.getOrNull()
                if (card != null) {
                    found = WordHit(lang, q, card)
                    break
                }
            }
            found
        }
    }
    return hit
}

@Composable private fun StatusChip(text: String, on: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (on) Palette.accent else Palette.cell)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag(tag),
    ) {
        Text(text, color = if (on) Color.Black else Palette.fg, fontSize = 14.sp)
    }
}

/** The word card in search, as the keyboard's card: Learning and Known toggle. */
@Composable fun WordCardRow(hit: WordHit) {
    val app = App.instance
    val ctx = LocalContext.current
    val card = hit.card
    val head = card.lemma.ifEmpty { card.word }
    var status by remember(hit.lang, head) { mutableStateOf(card.status) }
    var more by remember(hit.lang, head) { mutableStateOf(false) }

    fun setStatus(next: WordStatus) {
        status = next
        Words.setStatus(ctx, hit.lang, head, next, app.scope)
        WordSync.soon(ctx)
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp)).background(Color(0xFF141414))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics {
                contentDescription = "$head, ${card.meanings.firstOrNull().orEmpty()}"
            }
            .testTag("word-card"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column {
                if (card.reading.isNotEmpty() && card.reading != head) {
                    Text(card.reading, color = Palette.dim, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    if (card.article.isNotEmpty()) {
                        Text(
                            if (card.article.endsWith("'")) card.article else "${card.article} ",
                            color = Palette.dim, fontSize = 19.sp,
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                    Text(
                        head, color = Palette.fg, fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.alignByBaseline(),
                    )
                    if (card.gender.isNotEmpty() && card.article.isEmpty()) {
                        Text(card.gender, color = Palette.dim, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
                    }
                }
                if (hit.typed != head && hit.typed != card.word) {
                    Text(hit.typed, color = Palette.faint, fontSize = 13.sp)
                } else if (card.word != head) {
                    Text(card.word, color = Palette.faint, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (p in card.pos.take(2)) {
                    Text(
                        p, color = Palette.dim, fontSize = 10.sp,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Palette.cell)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
        }
        card.meanings.firstOrNull()?.let {
            Text(it, color = Palette.fg, fontSize = 16.sp, lineHeight = 20.sp, maxLines = 3)
        }
        val rest = card.meanings.drop(1)
        if (more && rest.isNotEmpty()) {
            Text(rest.joinToString("; "), color = Palette.dim, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 6)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusChip("Learning", status == WordStatus.LEARNING, "word-learning") {
                setStatus(if (status == WordStatus.LEARNING) WordStatus.NEW else WordStatus.LEARNING)
            }
            StatusChip("Known", status == WordStatus.KNOWN, "word-known") {
                setStatus(if (status == WordStatus.KNOWN) WordStatus.NEW else WordStatus.KNOWN)
            }
            if (rest.isNotEmpty()) {
                Text(
                    if (more) "Less" else "More",
                    color = Palette.accent, fontSize = 14.sp,
                    modifier = Modifier.clickable { more = !more }.padding(6.dp).testTag("word-more"),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(hit.lang, color = Palette.faint, fontSize = 12.sp)
        }
    }
}
