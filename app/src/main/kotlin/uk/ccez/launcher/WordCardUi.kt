package uk.ccez.launcher

import androidx.compose.runtime.Composable
import uk.ccez.keys.core.Card

/**
 * A word's card in search (Stage 3). The lookup comes from the core's
 * word_card; Stage 3 fills this in.
 */
data class WordHit(val lang: String, val card: Card)

@Composable fun rememberWordCard(query: String): WordHit? = null

@Composable fun WordCardRow(hit: WordHit) {
    // Stage 3.
}
