package uk.ccez.launcher

/**
 * The layout commands agents run (`scripts/launcher.ts pin|move|hide|…`,
 * through Control): name matching and the changes themselves, on a
 * LayoutData copy inside LayoutStore.update. Pure Kotlin (no Android, no
 * JSON), so unit tests cover them on the Mac.
 */

/** A bad command, said plainly back to the agent; nothing was changed. */
class AgentError(message: String) : IllegalArgumentException(message)

/** What a typed name matched: one thing, nothing, or several (never guessed between). */
sealed class Found<out T> {
    data class One<T>(val item: T) : Found<T>()
    data object None : Found<Nothing>()
    data class Many<T>(val items: List<T>) : Found<T>()

    /** The one match, or an AgentError naming the candidates. */
    fun one(what: String, query: String, describe: (T) -> String): T = when (this) {
        is One -> item
        None -> throw AgentError("no $what matches \"$query\"")
        is Many -> throw AgentError(
            "\"$query\" matches ${items.size} ${what}s; say which:\n" +
                items.joinToString("\n") { "  " + describe(it) },
        )
    }
}

object Match {
    /**
     * A name an agent typed, in three steps, the first that finds anything
     * wins: an exact key (app key, package, folder id, shortcut id), the
     * exact label (case and accents ignored), a label prefix. Two at one
     * step are ambiguous, never a guess.
     */
    fun <T> find(query: String, items: List<T>, label: (T) -> String, keys: (T) -> List<String> = { emptyList() }): Found<T> {
        val q = query.trim()
        if (q.isEmpty()) return Found.None
        val nq = normalized(q)
        val steps: List<(T) -> Boolean> = listOf(
            { t -> keys(t).any { it.equals(q, ignoreCase = true) } },
            { t -> normalized(label(t)) == nq },
            { t -> normalized(label(t)).startsWith(nq) },
        )
        for (step in steps) {
            val hits = items.filter(step).distinct()
            when (hits.size) {
                0 -> continue
                1 -> return Found.One(hits[0])
                else -> return Found.Many(hits)
            }
        }
        return Found.None
    }
}

/** An installed app as matching sees it. */
data class AppRef(val id: AppId, val label: String, val profile: String = "main") {
    val key: String get() = id.key()
    fun describe(): String = if (profile == "main") "$label  $key" else "$label ($profile)  $key"
}

/** Where a pin sits: in the row, or inside a folder. */
sealed class Spot {
    data class Row(val index: Int) : Spot()
    data class InFolder(val folderId: String, val index: Int) : Spot()
}

object LayoutOps {
    fun findApp(apps: List<AppRef>, query: String): AppRef =
        Match.find(query, apps, { it.label }, { listOf(it.key, it.id.pkg) }).one("app", query) { it.describe() }

    /** A pin's name as an agent sees it: the app's label, the shortcut's, the folder's. */
    fun pinLabel(d: LayoutData, pin: Pin, labelOf: (AppId) -> String?): String = when (pin) {
        is Pin.App -> labelOf(pin.id) ?: pin.id.pkg
        is Pin.Shortcut -> pin.label.ifBlank { pin.shortcutId }
        is Pin.FolderRef -> d.folders[pin.folderId]?.name?.ifBlank { null } ?: "Folder"
    }

    private fun pinKeys(pin: Pin): List<String> = when (pin) {
        is Pin.App -> listOf(pin.id.key(), pin.id.pkg)
        is Pin.Shortcut -> listOf("${pin.pkg}/${pin.shortcutId}", pin.shortcutId)
        is Pin.FolderRef -> listOf(pin.folderId)
    }

    /**
     * `<app|N>`: a row position (0 first), or a name matched against the
     * row (apps, shortcuts, folders); with [inFolders], an app inside a
     * folder too when the row has no match.
     */
    fun locate(d: LayoutData, query: String, labelOf: (AppId) -> String?, inFolders: Boolean = false): Spot {
        query.trim().toIntOrNull()?.let { n ->
            if (n !in d.pins.indices) throw AgentError(rangeError("position", n, d.pins.size))
            return Spot.Row(n)
        }
        val row = d.pins.indices.toList()
        val describe = { i: Int -> "$i  ${pinLabel(d, d.pins[i], labelOf)}  ${pinKeys(d.pins[i]).first()}" }
        val hit = Match.find(query, row, { pinLabel(d, d.pins[it], labelOf) }, { pinKeys(d.pins[it]) })
        if (hit !is Found.None || !inFolders) return Spot.Row(hit.one("pin", query, describe))
        val inside = d.folders.values.flatMap { f -> f.items.indices.map { Spot.InFolder(f.id, it) } }
        val pinAt = { s: Spot.InFolder -> d.folders.getValue(s.folderId).items[s.index] }
        return Match.find(query, inside, { pinLabel(d, pinAt(it), labelOf) }, { pinKeys(pinAt(it)) })
            .one("pin", query) { s -> "${pinLabel(d, pinAt(s), labelOf)} in ${d.folders[s.folderId]?.name?.ifBlank { null } ?: s.folderId}" }
    }

    fun findFolder(d: LayoutData, query: String): Folder =
        Match.find(query, d.folders.values.toList(), { it.name.ifBlank { "Folder" } }, { listOf(it.id) })
            .one("folder", query) { "${it.name.ifBlank { "Folder" }}  ${it.id}" }

    fun rowIndexOf(d: LayoutData, id: AppId): Int = d.pins.indexOfFirst { (it as? Pin.App)?.id == id }

    fun folderOf(d: LayoutData, id: AppId): Folder? =
        d.folders.values.firstOrNull { f -> f.items.any { (it as? Pin.App)?.id == id } }

    /** Pins [app] into the row at [at] (end when null); returns where it went. */
    fun pin(d: LayoutData, app: AppRef, at: Int? = null): Int {
        if (app.key in d.hidden) throw AgentError("${app.label} is hidden; unhide it first")
        val already = rowIndexOf(d, app.id)
        if (already >= 0) throw AgentError("${app.label} is already pinned at $already (move moves it)")
        return insert(d, Pin.App(app.id), at)
    }

    fun pinShortcut(d: LayoutData, s: Pin.Shortcut, at: Int? = null): Int {
        val already = d.pins.indexOfFirst { it is Pin.Shortcut && it.pkg == s.pkg && it.shortcutId == s.shortcutId && it.user == s.user }
        if (already >= 0) throw AgentError("${s.label} is already pinned at $already")
        return insert(d, s, at)
    }

    private fun insert(d: LayoutData, pin: Pin, at: Int?): Int {
        val i = at ?: d.pins.size
        if (i !in 0..d.pins.size) throw AgentError(rangeError("--at", i, d.pins.size + 1))
        d.pins.add(i, pin)
        return i
    }

    /** Takes a pin off home: from the row, or out of its folder (an emptied folder goes). */
    fun unpin(d: LayoutData, spot: Spot): Pin = when (spot) {
        is Spot.Row -> {
            val p = d.pins.removeAt(spot.index)
            // A folder unpinned whole goes with its items (unfolder keeps them).
            if (p is Pin.FolderRef) d.folders.remove(p.folderId)
            p
        }
        is Spot.InFolder -> {
            val f = d.folders.getValue(spot.folderId)
            val p = f.items.removeAt(spot.index)
            dropEmptyFolders(d)
            p
        }
    }

    fun move(d: LayoutData, from: Int, to: Int) {
        if (from !in d.pins.indices) throw AgentError(rangeError("position", from, d.pins.size))
        if (to !in d.pins.indices) throw AgentError(rangeError("to", to, d.pins.size))
        val p = d.pins.removeAt(from)
        d.pins.add(to, p)
    }

    /** Hidden apps leave home: the row and every folder (as the menu's Hide). */
    fun hide(d: LayoutData, app: AppRef) {
        if (app.key in d.hidden) throw AgentError("${app.label} is already hidden")
        d.hidden.add(app.key)
        d.pins.removeAll { (it as? Pin.App)?.id == app.id }
        d.folders.values.forEach { f -> f.items.removeAll { (it as? Pin.App)?.id == app.id } }
        dropEmptyFolders(d)
    }

    fun unhide(d: LayoutData, key: String, label: String) {
        if (!d.hidden.remove(key)) throw AgentError("$label isn't hidden")
    }

    /**
     * Makes folder [name] from [apps], or adds them to the one already
     * called that. Apps come out of the row and other folders; a new
     * folder takes the row place of the first of them that was pinned
     * (the end when none was). Returns the folder and whether it is new.
     */
    fun folder(d: LayoutData, name: String, apps: List<AppRef>): Pair<Folder, Boolean> {
        val n = name.trim()
        if (n.isEmpty()) throw AgentError("a folder needs a name")
        if (apps.isEmpty()) throw AgentError("a folder needs at least one app")
        apps.firstOrNull { it.key in d.hidden }?.let { throw AgentError("${it.label} is hidden; unhide it first") }
        val existing = d.folders.values.firstOrNull { normalized(it.name) == normalized(n) }
        val wanted = apps.distinctBy { it.id }
        val ids = wanted.mapTo(HashSet()) { it.id }
        val firstAt = d.pins.indexOfFirst { (it as? Pin.App)?.id in ids }
        // Out of other folders; out of the row.
        d.folders.values.filter { it !== existing }.forEach { f -> f.items.removeAll { (it as? Pin.App)?.id in ids } }
        val f = existing ?: Folder("f${d.nextFolder}", n, mutableListOf()).also {
            d.nextFolder += 1
            d.folders[it.id] = it
        }
        if (existing == null) {
            // Nothing before the first of them goes, so its index still holds.
            d.pins.removeAll { (it as? Pin.App)?.id in ids }
            d.pins.add(if (firstAt >= 0) firstAt else d.pins.size, Pin.FolderRef(f.id))
        } else {
            d.pins.removeAll { (it as? Pin.App)?.id in ids }
            if (d.pins.none { (it as? Pin.FolderRef)?.folderId == f.id }) d.pins.add(Pin.FolderRef(f.id))
        }
        for (a in wanted) if (f.items.none { (it as? Pin.App)?.id == a.id }) f.items.add(Pin.App(a.id))
        dropEmptyFolders(d)
        return f to (existing == null)
    }

    /** The folder goes; its items take its place in the row, in order. */
    fun unfolder(d: LayoutData, folder: Folder): List<Pin> {
        val items = d.folders.remove(folder.id)?.items.orEmpty()
        val at = d.pins.indexOfFirst { (it as? Pin.FolderRef)?.folderId == folder.id }
        d.pins.removeAll { (it as? Pin.FolderRef)?.folderId == folder.id }
        d.pins.addAll(if (at >= 0) at.coerceAtMost(d.pins.size) else d.pins.size, items)
        return items
    }

    fun renameFolder(d: LayoutData, folder: Folder, name: String) {
        val n = name.trim()
        if (n.isEmpty()) throw AgentError("a folder needs a name")
        d.folders.values.firstOrNull { it.id != folder.id && normalized(it.name) == normalized(n) }
            ?.let { throw AgentError("there's already a folder called ${it.name}") }
        d.folders[folder.id]?.name = n
    }

    /** "Word first" (Stage 4); returns whether anything changed. */
    fun gate(d: LayoutData, key: String, on: Boolean): Boolean =
        if (on) d.gates.add(key) else d.gates.remove(key)

    /** A folder with nothing left goes, and its place in the row. */
    fun dropEmptyFolders(d: LayoutData) {
        val empty = d.folders.values.filter { it.items.isEmpty() }.map { it.id }.toSet()
        if (empty.isEmpty()) return
        empty.forEach { d.folders.remove(it) }
        d.pins.removeAll { (it as? Pin.FolderRef)?.folderId in empty }
    }

    private fun rangeError(what: String, n: Int, size: Int): String =
        if (size == 0) "$what $n: nothing there" else "$what $n is out of range (0–${size - 1})"
}
