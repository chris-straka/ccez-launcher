package uk.ccez.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Updates from the GitHub releases (scripts/release.sh), as ccez-keys'
 * keyboard does: checked when home comes up, at most every six hours and
 * only when `updates.self` is on; a newer build is downloaded, its
 * SHA-256 and signing key checked against this one, and installed.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/chris-straka/ccez-launcher/releases/latest"
    private const val EVERY = 6 * 60 * 60 * 1000L
    @Volatile private var busy = false

    fun maybe(ctx: Context) {
        if (!App.instance.settings.bool("updates.self")) return
        val prefs = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (busy || System.currentTimeMillis() - prefs.getLong("checked", 0) < EVERY) return
        busy = true
        App.instance.scope.launch {
            try {
                prefs.edit().putLong("checked", System.currentTimeMillis()).apply()
                check(ctx)
            } catch (e: Exception) {
                App.instance.launcherLog.log("warn", "update-failed", "error" to e.toString())
            } finally {
                busy = false
            }
        }
    }

    fun force(ctx: Context) {
        ctx.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putLong("checked", 0).apply()
        // `launcher update` means it: run even with updates.self off.
        val prefs = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (busy) return
        busy = true
        App.instance.scope.launch {
            try {
                prefs.edit().putLong("checked", System.currentTimeMillis()).apply()
                check(ctx)
            } catch (e: Exception) {
                App.instance.launcherLog.log("warn", "update-failed", "error" to e.toString())
            } finally {
                busy = false
            }
        }
    }

    private fun get(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = 15_000
        readTimeout = 60_000
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    private fun check(ctx: Context) {
        val release = JSONObject(get(LATEST).inputStream.bufferedReader().readText())
        val code = release.getString("tag_name").substringAfterLast('.').toLongOrNull() ?: return
        if (code <= BuildConfig.VERSION_CODE) return
        if (ctx.getSharedPreferences("updates", Context.MODE_PRIVATE).getLong("declined", 0) >= code) return
        val assets = release.getJSONArray("assets")
        fun url(suffix: String) = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(suffix) }?.getString("browser_download_url")
        val apkUrl = url(".apk") ?: return
        val sum = get(url(".apk.sha256") ?: return).inputStream.bufferedReader().readText().trim().split(Regex("\\s+")).first()
        val apk = File(ctx.cacheDir, "update.apk")
        get(apkUrl).inputStream.use { i -> apk.outputStream().use { i.copyTo(it) } }
        val md = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (got != sum) { apk.delete(); error("checksum") }
        if (!sameSigner(ctx, apk)) { apk.delete(); error("signed by another key") }
        App.instance.launcherLog.log("info", "update", "to" to code)
        ctx.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putLong("offered", code).apply()
        install(ctx, apk)
    }

    private fun sameSigner(ctx: Context, apk: File): Boolean {
        val pm = ctx.packageManager
        val flag = PackageManager.GET_SIGNING_CERTIFICATES
        val theirs = pm.getPackageArchiveInfo(apk.path, flag)?.signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() } ?: return false
        val ours = pm.getPackageInfo(ctx.packageName, flag).signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() } ?: return false
        return theirs.toSet() == ours.toSet()
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { s ->
                s.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                val intent = Intent(ctx, Done::class.java)
                val pi = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                s.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    class Done : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { ctx.startActivity(it) } }
            } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED || status == PackageInstaller.STATUS_FAILURE_BLOCKED) {
                val latest = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)
                latest.edit().putLong("declined", latest.getLong("offered", 0)).apply()
                App.instance.launcherLog.log("info", "update-declined", "status" to status)
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                App.instance.launcherLog.log("warn", "update-install", "status" to status, "message" to intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
            }
        }
    }
}
