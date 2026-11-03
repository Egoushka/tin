package io.github.egoushka.tin.home

import io.github.egoushka.tin.home.apps.AppEntry
import io.github.egoushka.tin.home.layout.PinStore
import org.junit.Assert.assertEquals
import org.junit.Test

class PinResolveTest {
    private fun app(name: String) = AppEntry(name, "p.$name", "p.$name.Main", 0)

    @Test fun `pins resolve to installed apps in pin order`() {
        val installed = listOf(app("a"), app("b"), app("c"))
        val keys = listOf(installed[2].key, installed[0].key)
        assertEquals(listOf(installed[2], installed[0]), PinStore.resolve(keys, installed))
    }

    @Test fun `a pin with no installed app is skipped and the keys are left alone`() {
        val installed = listOf(app("a"))
        val keys = listOf(app("gone").key, installed[0].key)
        assertEquals(installed, PinStore.resolve(keys, installed))
        assertEquals(2, keys.size)
    }
}
