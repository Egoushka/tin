package io.github.egoushka.tin.home

import io.github.egoushka.tin.home.apps.AppShortcut
import io.github.egoushka.tin.home.apps.RawShortcut
import io.github.egoushka.tin.home.apps.Shortcuts
import org.junit.Assert.assertEquals
import org.junit.Test

/** A5: which shortcuts the long-press sheet lists. */
class ShortcutsTest {
    private fun raw(
        id: String,
        label: String? = id,
        enabled: Boolean = true,
        activity: String? = "Main",
        manifest: Boolean = false,
        dynamic: Boolean = true,
        pinned: Boolean = false,
        rank: Int = 0,
    ) = RawShortcut(id, label, enabled, activity, manifest, dynamic, pinned, rank)

    private fun ids(raw: List<RawShortcut>, cls: String = "Main") = Shortcuts.pick(raw, cls).map { it.id }

    @Test
    fun disabledBlankAndUnlabelledShortcutsAreDropped() {
        assertEquals(listOf("ok"), ids(listOf(raw("off", enabled = false), raw("blank", label = " "), raw("none", label = null), raw("ok"))))
    }

    @Test
    fun anIdCountsOnce() {
        assertEquals(listOf("a"), ids(listOf(raw("a", dynamic = true, rank = 1), raw("a", manifest = true, dynamic = false))))
    }

    @Test
    fun manifestThenDynamicByRankThenPinned() {
        val order = ids(listOf(
            raw("pin", dynamic = false, pinned = true),
            raw("d2", rank = 2),
            raw("man", manifest = true, dynamic = false),
            raw("d1", rank = 1),
        ))
        assertEquals(listOf("man", "d1", "d2", "pin"), order)
    }

    @Test
    fun atMostFive() {
        val many = (1..9).map { raw("s$it", rank = it) }
        assertEquals((1..Shortcuts.MAX).map { "s$it" }, ids(many))
    }

    @Test
    fun shortcutsOfAnotherActivityAreDroppedAndAppWideOnesKept() {
        val list = listOf(raw("mine", activity = "Main"), raw("other", activity = "Other"), raw("wide", activity = null))
        assertEquals(listOf("mine", "wide"), ids(list, "Main").sorted())
        assertEquals(listOf("other", "wide"), ids(list, "Other").sorted())
    }

    @Test
    fun theRowCarriesTheLabel() {
        assertEquals(listOf(AppShortcut("new", "New message")), Shortcuts.pick(listOf(raw("new", label = "New message")), "Main"))
    }
}
