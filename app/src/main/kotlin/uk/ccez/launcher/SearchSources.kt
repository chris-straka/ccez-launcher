package uk.ccez.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ContactHit(val name: String, val lookupUri: Uri)

data class SettingsPage(val name: String, val action: String)

/** Android's settings screens, found by name. */
val SETTINGS_PAGES: List<SettingsPage> = listOf(
    SettingsPage("Settings", Settings.ACTION_SETTINGS),
    SettingsPage("Wi-Fi", Settings.ACTION_WIFI_SETTINGS),
    SettingsPage("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS),
    SettingsPage("Mobile network", Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
    SettingsPage("Data usage", Settings.ACTION_DATA_USAGE_SETTINGS),
    SettingsPage("Airplane mode", Settings.ACTION_AIRPLANE_MODE_SETTINGS),
    SettingsPage("Display", Settings.ACTION_DISPLAY_SETTINGS),
    SettingsPage("Sound", Settings.ACTION_SOUND_SETTINGS),
    SettingsPage("Notifications", Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS),
    SettingsPage("Apps", Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS),
    SettingsPage("Storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
    SettingsPage("Battery", Settings.ACTION_BATTERY_SAVER_SETTINGS),
    SettingsPage("Location", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
    SettingsPage("Security", Settings.ACTION_SECURITY_SETTINGS),
    SettingsPage("Privacy", Settings.ACTION_PRIVACY_SETTINGS),
    SettingsPage("Accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS),
    SettingsPage("Keyboard", Settings.ACTION_INPUT_METHOD_SETTINGS),
    SettingsPage("Language", Settings.ACTION_LOCALE_SETTINGS),
    SettingsPage("Date and time", Settings.ACTION_DATE_SETTINGS),
    SettingsPage("About phone", Settings.ACTION_DEVICE_INFO_SETTINGS),
    SettingsPage("Home app", Settings.ACTION_HOME_SETTINGS),
    SettingsPage("VPN", Settings.ACTION_VPN_SETTINGS),
    SettingsPage("Hotspot", "android.settings.TETHER_SETTINGS"),
    SettingsPage("Developer options", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
)

fun settingsMatches(query: String): List<SettingsPage> {
    if (query.isBlank()) return emptyList()
    val q = normalized(query)
    return SETTINGS_PAGES.mapNotNull { p -> matchRank(p.name, q).takeIf { it >= 0 }?.let { it to p } }
        .sortedWith(compareBy({ it.first }, { normalized(it.second.name) }))
        .map { it.second }
}

fun appMatches(apps: List<AppEntry>, query: String): List<AppEntry> {
    if (query.isBlank()) return emptyList()
    val q = normalized(query)
    return apps.mapNotNull { a -> matchRank(a.label, q).takeIf { it >= 0 }?.let { it to a } }
        .sortedWith(compareBy({ it.first }, { normalized(it.second.label) }))
        .map { it.second }
}

/** People by name, when the contacts permission is there. Empty without it. */
suspend fun contactMatches(ctx: Context, query: String): List<ContactHit> = withContext(Dispatchers.IO) {
    if (query.isBlank()) return@withContext emptyList()
    if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        return@withContext emptyList()
    }
    runCatching {
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query))
        val out = ArrayList<ContactHit>()
        ctx.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts._ID,
            ),
            null, null, null,
        )?.use { c ->
            val nameI = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            val lookI = c.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
            val idI = c.getColumnIndex(ContactsContract.Contacts._ID)
            while (c.moveToNext() && out.size < 8) {
                val name = if (nameI >= 0) c.getString(nameI).orEmpty() else ""
                val lookup = if (lookI >= 0) c.getString(lookI).orEmpty() else ""
                val id = if (idI >= 0) c.getLong(idI) else 0L
                if (name.isNotBlank() && lookup.isNotBlank()) {
                    out.add(ContactHit(name, ContactsContract.Contacts.getLookupUri(id, lookup)))
                }
            }
        }
        out
    }.getOrDefault(emptyList())
}

fun openContact(ctx: Context, hit: ContactHit) {
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, hit.lookupUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

fun openSettingsPage(ctx: Context, page: SettingsPage) {
    runCatching {
        ctx.startActivity(Intent(page.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
