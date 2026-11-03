package io.github.egoushka.tin.home.apps

const val DOCK_SIZE = 5

/**
 * The dock: [DOCK_SIZE] slots, each an app key or empty. An empty slot is the one with no default handler (GrapheneOS
 * ships no maps app), and the UI keeps that default's glyph there until the user fills it.
 */
class DockSlots private constructor(val keys: List<String?>) {
    /** Puts [key] in [slot]; an app that is already docked elsewhere swaps places with the slot's old content. */
    fun put(slot: Int, key: String): DockSlots {
        if (slot !in 0 until DOCK_SIZE) return this
        val next = keys.toMutableList()
        val from = next.indexOf(key)
        if (from >= 0) next[from] = next[slot]
        next[slot] = key
        return DockSlots(next)
    }

    fun encode(): String = keys.joinToString(SEPARATOR) { it.orEmpty() }

    override fun equals(other: Any?) = other is DockSlots && other.keys == keys
    override fun hashCode() = keys.hashCode()

    companion object {
        private const val SEPARATOR = "\n"

        fun of(keys: List<String?>): DockSlots = DockSlots(List(DOCK_SIZE) { keys.getOrNull(it)?.takeIf(String::isNotEmpty) })

        /** Nothing stored, or a stored value that is short or corrupt, is completed from [defaults]. */
        fun decode(raw: String?, defaults: DockSlots): DockSlots {
            if (raw == null) return defaults
            val parts = raw.split(SEPARATOR)
            return DockSlots(List(DOCK_SIZE) { i -> if (i < parts.size) parts[i].takeIf(String::isNotEmpty) else defaults.keys[i] })
        }
    }
}

object DockDefaults {
    /**
     * The app each default job resolves to today, as keys of the own-profile list. A job whose handler has no launcher
     * entry (or no handler at all) stays empty.
     */
    fun keys(handlers: List<Pair<String, String?>?>, apps: List<AppEntry>, ownUser: Long): DockSlots =
        DockSlots.of(handlers.map { handler ->
            handler?.let { (pkg, cls) ->
                val own = apps.filter { it.pkg == pkg && it.user == ownUser }
                (own.firstOrNull { it.cls == cls } ?: own.firstOrNull())?.key
            }
        })
}
