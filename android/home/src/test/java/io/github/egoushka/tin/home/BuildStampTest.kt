package io.github.egoushka.tin.home

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

/** The build stamp reads as the release does: version, then short SHA, in both languages. */
class BuildStampTest {
    private fun stamp(dir: String): String =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$dir/strings_build.xml"))
            .getElementsByTagName("string").let { nodes ->
                (0 until nodes.length).map { nodes.item(it) }.single { it.attributes.getNamedItem("name").nodeValue == "build_stamp" }.textContent
            }

    @Test
    fun stampShowsVersionThenShortSha() {
        assertEquals("Build 0.1.26 · e9d6b0b", stamp("values").format("0.1.26", "e9d6b0b"))
        assertEquals("Збірка 0.1.26 · e9d6b0b-dirty", stamp("values-uk").format("0.1.26", "e9d6b0b-dirty"))
    }
}
