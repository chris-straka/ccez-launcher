package uk.ccez.launcher

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uk.ccez.keys.core.Engine
import uk.ccez.keys.core.Grade
import uk.ccez.keys.core.LearningWord
import uk.ccez.keys.core.ScreenWord

/**
 * The one way into the ccez-keys core (Rust, UniFFI): learning words,
 * their cards and the review schedule. No word logic lives in Kotlin.
 * Made on a background thread after home is up, so showing home never
 * waits on it.
 */
object Words {
    @Volatile private var engine: Engine? = null

    fun engineOrNull(): Engine? = engine

    @Synchronized fun engine(ctx: Context): Engine =
        engine ?: Engine(ctx.filesDir.resolve("launcher-core").path).also { engine = it }

    fun warm(ctx: Context, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val e = engine(ctx)
                applyWordSettings(ctx, e)
            }.onFailure { App.instance.launcherLog.log("error", "core-start-failed", "error" to it.toString()) }
        }
    }

    /** The launcher's word settings, kept in the core's own settings too. */
    fun applyWordSettings(ctx: Context, e: Engine) {
        val app = ctx as? App ?: (ctx.applicationContext as? App) ?: return
        runCatching { e.setSetting("learning.screen", app.settings.number("words.count").toString()) }
        runCatching { e.setSetting("learning.screen_langs", app.settings.number("words.langs").toString()) }
        runCatching { e.setSetting("words.server", org.json.JSONObject.quote(app.settings.get("words.server") as String)) }
    }

    /**
     * Words to put on home now (the core's screen_words: due by the core
     * review schedule, languages taking turns, each with its short
     * meaning). Call off the main thread.
     */
    fun screenWords(ctx: Context, up: List<ScreenWord>): List<ScreenWord> {
        val e = engineOrNull() ?: return emptyList()
        return runCatching {
            e.screenWords(up.map { LearningWord(it.lang, it.word) })
        }.getOrDefault(emptyList())
    }

    /** A word's status set on its card (Learning / Known / neither). */
    fun setStatus(ctx: Context, lang: String, word: String, status: uk.ccez.keys.core.WordStatus, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            runCatching { engine(ctx).setWordStatus(lang, word, status) }
        }
    }

    /** Tap Good, long-press Again, double-tap Easy — the core's schedule. */
    fun grade(ctx: Context, word: ScreenWord, grade: Grade, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                engine(ctx).gradeScreenWord(word.lang, word.word, grade)
            }
        }
    }
}
