package io.github.egoushka.tin.home.apps

import io.github.egoushka.tin.home.search.Fold

/** The drawer's A to Z strip: each first letter once, in list order, with the position of its first app. */
class AzIndex private constructor(val letters: List<Letter>) {
    data class Letter(val char: Char, val position: Int)

    /** The letter under [y] on a strip [height] tall, clamped to the first and last letter. */
    fun at(y: Float, height: Float): Letter? {
        if (letters.isEmpty() || height <= 0f) return null
        return letters[(y / height * letters.size).toInt().coerceIn(0, letters.lastIndex)]
    }

    companion object {
        const val OTHER = '#'

        fun of(labels: List<String>): AzIndex {
            val seen = LinkedHashMap<Char, Int>()
            labels.forEachIndexed { i, label -> seen.putIfAbsent(letterOf(label), i) }
            return AzIndex(seen.map { Letter(it.key, it.value) })
        }

        private fun letterOf(label: String): Char {
            val first = Fold.of(label.trim()).firstOrNull() ?: return OTHER
            return if (first.isLetter()) first.uppercaseChar() else OTHER
        }
    }
}
