package uk.ccez.launcher

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Dictionaries, downloaded the first time a language needs one, from the
 * public release of ccez-lang-core, checked against its SHA256SUMS, then
 * packed by the core (as ccez-keys does; no typing converter here).
 */
object Dictionaries {
    private const val BASE = "https://github.com/chris-straka/ccez-lang-core/releases/download/dictionaries/"
    private val ONE = Mutex()
    private var sums: Map<String, String>? = null
    private val failed = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val RETRY_MS = 15 * 60 * 1000L

    private fun gaveUp(lang: String) = failed[lang]?.let { System.currentTimeMillis() - it < RETRY_MS } == true

    private fun files(lang: String): List<String> = when (lang) {
        "zh" -> listOf("cedict.txt.gz")
        "ja" -> listOf("ja.tsv.gz", "ja-pitch.tsv.gz", "ipadic.zip")
        else -> listOf("$lang.tsv.gz")
    }

    fun ready(ctx: Context, lang: String): Boolean =
        runCatching { Words.engine(ctx).dictionaryReady(lang) }.getOrDefault(false)

    /** Fetches and installs `lang`'s dictionary unless it's there. True when installed just now. */
    suspend fun ensure(ctx: Context, lang: String): Boolean {
        val app = App.instance
        val engine = Words.engine(ctx)
        if (engine.dictionaryReady(lang) || gaveUp(lang)) return false
        ONE.withLock {
            if (engine.dictionaryReady(lang) || gaveUp(lang)) return false
            val started = System.nanoTime()
            try {
                val sums = sums ?: fetchSums().also { sums = it }
                val wanted = files(lang).filter { it in sums }
                if (wanted.isEmpty()) {
                    failed[lang] = Long.MAX_VALUE / 2
                    return false
                }
                val dir = File(app.cacheDir, "dict").apply { mkdirs() }
                for (name in wanted) {
                    val file = File(dir, name)
                    download(BASE + name, file)
                    if (sha256(file) != sums[name]) {
                        file.delete()
                        this.sums = null
                        error("checksum of $name")
                    }
                    when (name) {
                        "ipadic.zip" -> unzip(file, File(engine.dictionaryDir(), "ipadic"))
                        "ja-pitch.tsv.gz" -> engine.installPitch(file.path)
                        else -> if (!engine.dictionaryReady(lang)) engine.installDictionary(lang, file.path)
                    }
                    file.delete()
                }
                app.launcherLog.log("info", "dictionary", "lang" to lang, "ms" to (System.nanoTime() - started) / 1_000_000)
                return true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failed[lang] = System.currentTimeMillis()
                app.launcherLog.log("warn", "dictionary-failed", "lang" to lang, "error" to e.toString())
                return false
            }
        }
    }

    private fun fetchSums(): Map<String, String> {
        val text = (URL(BASE + "SHA256SUMS").openConnection() as HttpURLConnection).run {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            inputStream.bufferedReader().readText()
        }
        return text.lines().mapNotNull { l ->
            l.trim().split(Regex("\\s+"), 2).takeIf { it.size == 2 }?.let { it[1].trimStart('*') to it[0] }
        }.toMap()
    }

    private fun download(url: String, to: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.inputStream.use { input -> to.outputStream().use { input.copyTo(it) } }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val b = ByteArray(1 shl 16)
            while (true) {
                val n = s.read(b)
                if (n < 0) break
                md.update(b, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun unzip(zip: File, into: File) {
        into.mkdirs()
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val out = File(into, e.name.substringAfterLast('/'))
                if (!e.isDirectory && out.canonicalPath.startsWith(into.canonicalPath)) {
                    out.outputStream().use { z.copyTo(it) }
                }
            }
        }
    }
}

/** Word statuses and the review schedule to and from ccez-words (the core syncs both). */
object WordSync {
    private var last = 0L
    private var job: kotlinx.coroutines.Job? = null

    private fun from(ctx: Context): String {
        val p = ctx.getSharedPreferences("sync", Context.MODE_PRIVATE)
        val id = p.getString("id", null) ?: UUID.randomUUID().toString().take(8).also {
            p.edit().putString("id", it).apply()
        }
        return "ccez-launcher:$id"
    }

    fun soon(ctx: Context, delayMs: Long = 4_000) {
        val app = App.instance
        job?.cancel()
        job = app.scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(delayMs)
            val e = runCatching { Words.engine(ctx) }.getOrNull() ?: return@launch
            if (runCatching { e.setting("words.server") }.getOrNull()?.trim('"').isNullOrEmpty()) return@launch
            val r = runCatching { e.syncWords(from(ctx)) }
            last = System.currentTimeMillis()
            r.onSuccess { n -> app.launcherLog.log("info", "words-sync", "came" to n.toLong()) }
                .onFailure { app.launcherLog.log("warn", "words-sync-failed", "error" to it.message) }
            // Dictionaries for the languages with learning words.
            val langs = runCatching { e.learningWords().map { it.lang }.distinct() }.getOrDefault(emptyList())
            for (lang in langs) runCatching { Dictionaries.ensure(ctx, lang) }
        }
    }

    /** At most every five minutes. */
    fun maybe(ctx: Context) {
        if (System.currentTimeMillis() - last > 5 * 60_000) soon(ctx, 0)
    }
}
