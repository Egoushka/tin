package io.github.egoushka.tin.home.search

import java.text.Normalizer
import java.util.Locale

/** Lower case, accents and apostrophes gone: "й" is "и", "Zoë" is "zoe", "Мар'яна" is "маряна". */
object Fold {
    fun of(s: String): String {
        val decomposed = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        for (c in decomposed) {
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt() || c == '\'' || c == '’' || c == 'ʼ') continue
            out.append(c)
        }
        return out.toString()
    }
}

class Match<T>(val item: T, val tier: Int)

/**
 * Labels folded and word starts found once, rows kept A–Z, so a lookup is one scan with no per-label work and no sort:
 * rows land in the bucket of their tier in A–Z order. Tier 0 is a prefix, 1 a word start, 2 anywhere; a blank query
 * matches everything at [BLANK] in A–Z.
 */
class Index<T>(items: List<T>, label: (T) -> String) {
    private class Row<T>(val item: T, val text: String, val starts: IntArray)

    private val rows: List<Row<T>> = items
        .map { item ->
            val text = Fold.of(label(item))
            Row(item, text, wordStarts(text))
        }
        .sortedBy { it.text }

    val size: Int get() = rows.size

    fun match(query: String): List<Match<T>> {
        val q = Fold.of(query.trim())
        if (q.isEmpty()) return rows.map { Match(it.item, BLANK) }
        val buckets = List(BLANK) { ArrayList<Match<T>>() }
        for (row in rows) {
            val tier = tier(row, q)
            if (tier >= 0) buckets[tier].add(Match(row.item, tier))
        }
        return buckets.flatten()
    }

    private fun tier(row: Row<T>, q: String): Int {
        if (row.text.startsWith(q)) return 0
        for (start in row.starts) if (row.text.startsWith(q, start)) return 1
        return if (row.text.contains(q)) 2 else -1
    }

    companion object {
        const val BLANK = 3

        /** Offsets after a space, hyphen, underscore, dot or colon; 0 is the prefix tier's. */
        private fun wordStarts(text: String): IntArray {
            val starts = ArrayList<Int>()
            for (i in 1 until text.length) {
                if (!isSeparator(text[i]) && isSeparator(text[i - 1])) starts.add(i)
            }
            return starts.toIntArray()
        }

        private fun isSeparator(c: Char) = c.isWhitespace() || c == '-' || c == '_' || c == '.' || c == ':'
    }
}
