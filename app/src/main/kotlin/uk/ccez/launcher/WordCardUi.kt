package uk.ccez.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

/** Learning / Known: an outlined pill, filled softly when it holds. */
@Composable private fun StatusChip(text: String, on: Boolean, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.clip(shape)
            .then(if (on) Modifier.background(Palette.accent.copy(alpha = 0.16f)) else Modifier.border(1.dp, Palette.line, shape))
            .clickable(onClick = onClick)
            .semantics { selected = on }
            .heightIn(min = 36.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (on) Palette.accent else Palette.dim, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** The word card in search, as the keyboard's card: Learning and Known toggle. */
@OptIn(ExperimentalLayoutApi::class)
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
        Modifier.fillMaxWidth().padding(horizontal = Look.margin, vertical = 6.dp)
            .clip(RoundedCornerShape(Look.radius + 4.dp)).background(Palette.cell)
            .padding(start = Look.inset + 2.dp, end = Look.inset, top = 16.dp, bottom = 14.dp)
            .semantics {
                contentDescription = "$head, ${card.meanings.firstOrNull().orEmpty()}"
            }
            .testTag("word-card"),
    ) {
        // The reading over the word, the parts of speech level with it on the right.
        if (card.reading.isNotEmpty() && card.reading != head) {
            Text(card.reading, color = Palette.dim, fontSize = Look.smallSize, lineHeight = 16.sp)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Row(Modifier.weight(1f).alignByBaseline(), verticalAlignment = Alignment.Bottom) {
                if (card.article.isNotEmpty()) {
                    Text(
                        if (card.article.endsWith("'")) card.article else "${card.article} ",
                        color = Palette.dim, fontSize = 22.sp, fontWeight = FontWeight.Light,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                Text(
                    head, color = Palette.fg, fontSize = 30.sp, lineHeight = 36.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alignByBaseline(),
                )
                if (card.gender.isNotEmpty() && card.article.isEmpty()) {
                    Text(
                        card.gender, color = Palette.dim, fontSize = Look.smallSize,
                        modifier = Modifier.alignByBaseline().padding(start = 8.dp),
                    )
                }
            }
            run {
                Text(
                    (card.pos.take(2) + hit.lang).joinToString(" · "),
                    color = Palette.faint, fontSize = 12.sp, letterSpacing = 0.3.sp,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline().padding(start = 12.dp),
                )
            }
        }
        val form = when {
            hit.typed != head && hit.typed != card.word -> hit.typed
            card.word != head -> card.word
            else -> null
        }
        if (form != null) Text(form, color = Palette.faint, fontSize = Look.smallSize, lineHeight = 18.sp)
        card.meanings.firstOrNull()?.let {
            Text(
                it, color = Palette.fg, fontSize = 17.sp, lineHeight = 24.sp, maxLines = 3,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
            )
        }
        val rest = card.meanings.drop(1)
        if (more && rest.isNotEmpty()) {
            Text(
                rest.joinToString("; "), color = Palette.dim, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 6,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
            )
        }
        FlowRow(
            Modifier.padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusChip("Learning", status == WordStatus.LEARNING, "word-learning") {
                setStatus(if (status == WordStatus.LEARNING) WordStatus.NEW else WordStatus.LEARNING)
            }
            StatusChip("Known", status == WordStatus.KNOWN, "word-known") {
                setStatus(if (status == WordStatus.KNOWN) WordStatus.NEW else WordStatus.KNOWN)
            }
            if (rest.isNotEmpty()) {
                Text(
                    if (more) "Less" else "More",
                    color = Palette.dim, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { more = !more }
                        .padding(horizontal = 10.dp, vertical = 8.dp).testTag("word-more"),
                )
            }
        }
    }
}
