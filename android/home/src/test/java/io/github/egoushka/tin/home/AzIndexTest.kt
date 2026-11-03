package io.github.egoushka.tin.home

import io.github.egoushka.tin.home.apps.AzIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B18 and B20: the pure part of the drawer's A to Z strip. */
class AzIndexTest {
    private fun pairs(index: AzIndex) = index.letters.map { it.char to it.position }

    @Test fun `each first letter appears once with the position of its first app`() {
        val index = AzIndex.of(listOf("Alarm", "Amazon", "Banking", "Camera", "Chrome", "Clock"))
        assertEquals(listOf('A' to 0, 'B' to 2, 'C' to 3), pairs(index))
    }

    @Test fun `a label that starts with a digit or a symbol files under hash`() {
        val index = AzIndex.of(listOf("2FA", "Alarm", "_x"))
        assertEquals(listOf('#' to 0, 'A' to 1), pairs(index))
    }

    @Test fun `an accented letter files under its base letter`() {
        val index = AzIndex.of(listOf("Alarm", "Éclair", "Echo"))
        assertEquals(listOf('A' to 0, 'E' to 1), pairs(index))
    }

    @Test fun `Cyrillic letters keep their own letters`() {
        val index = AzIndex.of(listOf("Alarm", "Власний", "Годинник"))
        assertEquals(listOf('A' to 0, 'В' to 1, 'Г' to 2), pairs(index))
    }

    @Test fun `an empty list has no letters`() {
        assertEquals(emptyList<Pair<Char, Int>>(), pairs(AzIndex.of(emptyList())))
        assertNull(AzIndex.of(emptyList()).at(10f, 100f))
    }

    @Test fun `a dragged y maps to the letter under the finger and clamps at both ends`() {
        val index = AzIndex.of(listOf("Alarm", "Banking", "Camera", "Dialer"))
        assertEquals('A', index.at(10f, 400f)!!.char)
        assertEquals('B', index.at(150f, 400f)!!.char)
        assertEquals('C', index.at(250f, 400f)!!.char)
        assertEquals('D', index.at(399f, 400f)!!.char)
        assertEquals('A', index.at(-50f, 400f)!!.char)
        assertEquals('D', index.at(900f, 400f)!!.char)
    }
}
