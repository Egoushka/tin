package io.github.egoushka.tin.home

import io.github.egoushka.tin.home.theme.Lucide
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A missing glyph used to throw inside the long-press menu and take Home down with it (F1). */
class LucideTest {
    private val used = Regex("""(?:SheetRow|IconButton|Lucide\.icon)\("([a-z0-9-]+)"""")

    @Test fun `every icon name written in the sources has a body`() {
        val names = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> used.findAll(f.readText()).map { it.groupValues[1] } }.toSet()
        assertTrue("found only $names", names.size > 10)
        assertEquals(emptySet<String>(), names - Lucide.bodies.keys)
    }

    @Test fun `every dock default has a body`() {
        assertEquals(emptySet<String>(), DockDefault.entries.map { it.icon }.toSet() - Lucide.bodies.keys)
    }

    @Test fun `a name with no body draws the fallback instead of throwing`() {
        val missing = Lucide.icon("no-such-icon", 24f, 1.5f)
        assertEquals(Lucide.icon("info", 24f, 1.5f).root.size, missing.root.size)
    }
}
