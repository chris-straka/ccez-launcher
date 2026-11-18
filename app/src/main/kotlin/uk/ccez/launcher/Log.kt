package uk.ccez.launcher

import java.io.File
import org.json.JSONObject

/**
 * The launcher's log, for agents (`scripts/launcher.ts log`): one JSON
 * line per event — what was tapped, which app, timings. Never search text
 * or word text. User-facing errors stay a few plain words elsewhere.
 */
class LauncherLog(dir: File) {
    private val file = File(dir, "log.jsonl").also { it.parentFile?.mkdirs() }
    private val lock = Any()

    init {
        // Keep it small: over half a megabyte, drop the older half.
        runCatching {
            if (file.length() > 512 * 1024) {
                val lines = file.readLines()
                file.writeText(lines.takeLast(lines.size / 2).joinToString("\n", postfix = "\n"))
            }
        }
    }

    fun log(level: String, event: String, vararg ctx: Pair<String, Any?>) {
        val o = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("level", level)
            .put("event", event)
        ctx.forEach { (k, v) -> o.put(k, v) }
        synchronized(lock) {
            runCatching { file.appendText(o.toString() + "\n") }
        }
        // Errors in logcat too (unit tests have no logcat: never a throw).
        if (level == "error") runCatching { android.util.Log.e("ccez-launcher", "$event ${ctx.joinToString(" ") { "${it.first}=${it.second}" }}".take(1000)) }
    }

    fun tail(lines: Int): String {
        if (!file.exists()) return ""
        return runCatching { file.readLines().takeLast(lines).joinToString("\n") }.getOrDefault("")
    }
}

/**
 * Written whole or not at all (as in the Rust core): to a temporary file,
 * synced to the disk, then renamed over. Without the sync a power cut
 * could leave the renamed file empty (review 2026-11-14).
 */
internal fun writeSafely(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    java.io.FileOutputStream(tmp).use { out ->
        out.write(text.toByteArray())
        out.fd.sync()
    }
    if (!tmp.renameTo(file)) {
        file.writeText(text)
        tmp.delete()
    }
}
