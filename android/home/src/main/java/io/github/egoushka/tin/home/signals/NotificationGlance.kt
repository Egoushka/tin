package io.github.egoushka.tin.home.signals

enum class NoteKind { Message, Mail, Other }

/** One user-visible notification, reduced to what the glance needs. */
data class Note(
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val whenMs: Long,
    val kind: NoteKind,
)

data class AppCount(val pkg: String, val app: String, val kind: NoteKind, val count: Int)

/** Unread counts per app (most first, [MAX_CHIPS] shown) and the latest message. */
data class Glance(val apps: List<AppCount>, val latest: Note?, val total: Int) {
    companion object {
        const val MAX_CHIPS = 3
        val Empty = Glance(emptyList(), null, 0)

        fun of(notes: List<Note>): Glance {
            if (notes.isEmpty()) return Empty
            val apps = notes.groupBy { it.pkg }.map { (pkg, list) ->
                val newest = list.maxBy { it.whenMs }
                AppCount(pkg, newest.app, newest.kind, list.size) to newest.whenMs
            }.sortedWith(compareByDescending<Pair<AppCount, Long>> { it.first.count }.thenByDescending { it.second })
                .map { it.first }
            return Glance(apps.take(MAX_CHIPS), notes.maxBy { it.whenMs }, notes.size)
        }
    }
}
