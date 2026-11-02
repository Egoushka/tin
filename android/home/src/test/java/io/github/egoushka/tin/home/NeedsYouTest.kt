package io.github.egoushka.tin.home

import io.github.egoushka.tin.home.signals.NeedsItem
import io.github.egoushka.tin.home.signals.NeedsYou
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeedsYouTest {
    private fun item(id: String, at: Long) = NeedsItem(id, "t$id", "pr", at, false, "approvals://item/$id")

    @Test fun oldestFirstAndTheRestCounted() {
        val n = NeedsYou.of(listOf(item("c", 30), item("a", 10), item("b", 20)), open = 3, held = 0)
        assertEquals(listOf("a", "b"), n.rows.map { it.id })
        assertEquals(1, n.more)
    }

    @Test fun litFollowsTheOpenCountNotTheRows() {
        assertTrue(NeedsYou.of(emptyList(), open = 2, held = 0).lit)
        assertFalse(NeedsYou.of(listOf(item("a", 1)), open = 0, held = 1).lit)
        assertFalse(NeedsYou.Empty.lit)
    }
}
