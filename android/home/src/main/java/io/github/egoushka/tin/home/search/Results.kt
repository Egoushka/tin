package io.github.egoushka.tin.home.search

import io.github.egoushka.tin.home.apps.AppEntry

/** Whether Home may read contacts: [Ask] until the person taps the allow row, [Denied] once the system said no. */
enum class Access { Granted, Ask, Denied }

sealed interface Hit {
    val key: String

    /** The row Enter may open. The permission rows and the web row never are, except through their own tap. */
    val opens: Boolean get() = true

    data class App(val app: AppEntry) : Hit { override val key get() = "app:${app.key}" }
    data class Shortcut(val shortcut: ShortcutEntry) : Hit { override val key get() = "shortcut:${shortcut.key}" }
    data class Person(val contact: Contact) : Hit { override val key get() = "person:${contact.key}" }
    data object Allow : Hit { override val key = "allow"; override val opens = false }
    data object Off : Hit { override val key = "off"; override val opens = false }
    data class Ask(val query: String) : Hit { override val key get() = "ask" }
    data class Web(val query: String) : Hit { override val key get() = "web"; override val opens = false }
}

enum class Section { Apps, People, AskOrSearch }

class Group(val section: Section, val hits: List<Hit>)

/** The panel's groups in the order drawn; [top] is the row Enter opens. */
class Results(val groups: List<Group>) {
    val top: Hit? = groups.firstNotNullOfOrNull { group -> group.hits.firstOrNull { it.opens } }

    companion object {
        val None = Results(emptyList())
        const val APPS_CAP = 6
        const val PEOPLE_CAP = 4

        /**
         * Apps and people are ordered by their best tier, a tie going to apps; a shortcut follows an app of the same
         * tier; Ask Tin and the web row are always last. A blank query has no results.
         */
        fun of(query: String, apps: Index<AppEntry>, shortcuts: Index<ShortcutEntry>, people: Index<Contact>, access: Access): Results {
            val q = query.trim()
            if (q.isEmpty()) return None

            val appHits = merge(
                apps.match(q).map { Match<Hit>(Hit.App(it.item), it.tier) },
                shortcuts.match(q).map { Match<Hit>(Hit.Shortcut(it.item), it.tier) },
                APPS_CAP,
            )
            val personHits = if (access == Access.Granted) people.match(q).take(PEOPLE_CAP).map { Match<Hit>(Hit.Person(it.item), it.tier) } else emptyList()
            val permissionRow = when (access) {
                Access.Ask -> Hit.Allow
                Access.Denied -> Hit.Off
                Access.Granted -> null
            }

            val appsGroup = appHits.takeIf { it.isNotEmpty() }?.let { Group(Section.Apps, it.map(Match<Hit>::item)) }
            val peopleGroup = when {
                personHits.isNotEmpty() -> Group(Section.People, personHits.map(Match<Hit>::item))
                permissionRow != null -> Group(Section.People, listOf(permissionRow))
                else -> null
            }
            val peopleFirst = personHits.isNotEmpty() && (appHits.isEmpty() || personHits.first().tier < appHits.first().tier)

            return Results(
                (if (peopleFirst) listOfNotNull(peopleGroup, appsGroup) else listOfNotNull(appsGroup, peopleGroup)) +
                    Group(Section.AskOrSearch, listOf(Hit.Ask(q), Hit.Web(q))),
            )
        }

        /** Both lists are in tier order; at equal tiers [first] goes first. */
        private fun <T> merge(first: List<Match<T>>, second: List<Match<T>>, cap: Int): List<Match<T>> {
            val out = ArrayList<Match<T>>(minOf(cap, first.size + second.size))
            var i = 0
            var j = 0
            while (out.size < cap && (i < first.size || j < second.size)) {
                val takeFirst = j >= second.size || (i < first.size && first[i].tier <= second[j].tier)
                out.add(if (takeFirst) first[i++] else second[j++])
            }
            return out
        }
    }
}
