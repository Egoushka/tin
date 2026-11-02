package io.github.egoushka.tin.home

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** L3: the Ukrainian strings cover the English ones key for key, placeholder for placeholder, plural form for plural form. */
class StringsTest {
    private class Table(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    /** Every `strings*.xml` of the folder: a feature keeps its strings in a file of its own (`strings_layout.xml`). */
    private fun load(dir: String): Table {
        val strings = mutableMapOf<String, String>()
        val plurals = mutableMapOf<String, Map<String, String>>()
        val files = File("src/main/res/$dir").listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }!!.sorted()
        for (file in files) {
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                if (e.getAttribute("translatable") == "false") continue
                when (e.tagName) {
                    "string" -> strings[e.getAttribute("name")] = e.textContent
                    "plurals" -> {
                        val items = e.getElementsByTagName("item")
                        plurals[e.getAttribute("name")] = (0 until items.length).associate {
                            (items.item(it) as Element).getAttribute("quantity") to items.item(it).textContent
                        }
                    }
                }
            }
        }
        return Table(strings, plurals)
    }

    private val en = load("values")
    private val uk = load("values-uk")

    private fun formats(s: String) = Regex("""%(\d+\$)?[sd]""").findAll(s).map { it.value }.sorted().toList()

    @Test
    fun ukrainianHasEveryEnglishKeyAndNothingElse() {
        assertEquals(en.strings.keys, uk.strings.keys)
        assertEquals(en.plurals.keys, uk.plurals.keys)
    }

    @Test
    fun noValueIsEmpty() {
        for (t in listOf(en, uk)) {
            t.strings.forEach { (k, v) -> assertTrue("$k is empty", v.isNotBlank()) }
            t.plurals.forEach { (k, items) -> items.forEach { (q, v) -> assertTrue("$k/$q is empty", v.isNotBlank()) } }
        }
    }

    @Test
    fun placeholdersMatchTheEnglishOnes() {
        for ((k, v) in en.strings) assertEquals("string $k", formats(v), formats(uk.strings.getValue(k)))
        for ((k, items) in en.plurals) {
            val want = formats(items.getValue("other"))
            for (table in listOf(en, uk)) for ((q, v) in table.plurals.getValue(k)) assertEquals("plurals $k/$q", want, formats(v))
        }
    }

    /** CLDR: English one, other; Ukrainian one, few, many, other. A missing form falls back to `other` at run time and reads wrong. */
    @Test
    fun pluralsHaveEveryCldrForm() {
        for ((k, items) in en.plurals) assertEquals("en $k", setOf("one", "other"), items.keys)
        for ((k, items) in uk.plurals) assertEquals("uk $k", setOf("one", "few", "many", "other"), items.keys)
    }

    @Test
    fun ukrainianIsNotLeftInEnglish() {
        // Product names stay as they are.
        val kept = setOf("Approvals", "Tin", "Home")
        // The date skeleton is an ICU pattern, not text.
        for ((k, v) in uk.strings - "date_skeleton") {
            val words = Regex("""[A-Za-z]{2,}""").findAll(v.replace(Regex("""%(\d+\$)?[sd]"""), "")).map { it.value }.filterNot { it in kept }.toList()
            assertTrue("uk $k has English words $words", words.isEmpty())
        }
    }

    /** What the screen shows is not what a screen reader should say: "1 d" needs a spoken form with plurals. */
    @Test
    fun spokenAgesExistWithEveryForm() {
        assertTrue(en.strings.containsKey("age_now_spoken"))
        for (unit in listOf("min", "hour", "day")) assertTrue("age_${unit}_spoken", en.plurals.containsKey("age_${unit}_spoken"))
    }

    private fun ukCategory(n: Int): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod10 == 1 && mod100 != 11 -> "one"
            mod10 in 2..4 && mod100 !in 12..14 -> "few"
            else -> "many"
        }
    }

    /** The word under each Ukrainian form is the one grammar asks for: 1 хвилину, 2 хвилини, 5 хвилин, 21 хвилину. */
    @Test
    fun ukrainianPluralWordsAgreeWithTheirNumber() {
        val words = mapOf(
            "age_min_spoken" to Triple("хвилину", "хвилини", "хвилин"),
            "age_hour_spoken" to Triple("годину", "години", "годин"),
            "age_day_spoken" to Triple("день", "дні", "днів"),
            "tune_changed" to Triple("зміна", "зміни", "змін"),
        )
        for ((key, forms) in words) {
            val items = uk.plurals.getValue(key)
            for (n in listOf(1, 2, 3, 4, 5, 11, 12, 14, 20, 21, 22, 25, 101, 111)) {
                val want = when (ukCategory(n)) { "one" -> forms.first; "few" -> forms.second; else -> forms.third }
                val got = items.getValue(ukCategory(n))
                assertTrue("$key for $n: '$got' should contain '$want'", got.contains(want))
            }
        }
    }
}
