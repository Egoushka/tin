package io.github.egoushka.tin.home.apps

/** A shortcut as the launcher API reports it, reduced to plain data so the choice below is unit-testable. */
data class RawShortcut(
    val id: String,
    val label: String?,
    val enabled: Boolean,
    val activity: String?,
    val manifest: Boolean,
    val dynamic: Boolean,
    val pinned: Boolean,
    val rank: Int,
)

data class AppShortcut(val id: String, val label: String)

object Shortcuts {
    const val MAX = 5

    /**
     * What the long-press sheet lists for the tile of activity [cls]: enabled and labelled shortcuts of that activity or
     * of the whole app, manifest first, then dynamic by rank, then pinned; an id counts once.
     */
    fun pick(raw: List<RawShortcut>, cls: String): List<AppShortcut> =
        raw.filter { it.enabled && !it.label.isNullOrBlank() && (it.activity == null || it.activity == cls) }
            .sortedWith(compareBy<RawShortcut> { if (it.manifest) 0 else if (it.dynamic) 1 else 2 }.thenBy { it.rank })
            .distinctBy { it.id }
            .take(MAX)
            .map { AppShortcut(it.id, it.label!!) }
}
