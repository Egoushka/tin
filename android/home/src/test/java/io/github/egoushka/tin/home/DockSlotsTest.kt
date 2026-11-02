package io.github.egoushka.tin.home

import io.github.egoushka.tin.home.apps.AppEntry
import io.github.egoushka.tin.home.apps.DOCK_SIZE
import io.github.egoushka.tin.home.apps.DockDefaults
import io.github.egoushka.tin.home.apps.DockSlots
import org.junit.Assert.assertEquals
import org.junit.Test

/** A2: the dock store. */
class DockSlotsTest {
    private val defaults = DockSlots.of(listOf("dial", "sms", "cam", "web", null))

    @Test
    fun nothingStoredGivesTheDefaults() {
        assertEquals(defaults, DockSlots.decode(null, defaults))
    }

    @Test
    fun replaceFillsOneSlot() {
        val next = defaults.put(4, "maps")
        assertEquals(listOf("dial", "sms", "cam", "web", "maps"), next.keys)
        assertEquals(listOf("dial", "sms", "cam", "web", null), defaults.keys)
    }

    @Test
    fun movingADockedAppSwapsWithTheSlot() {
        assertEquals(listOf("web", "sms", "cam", "dial", null), defaults.put(3, "dial").keys)
        assertEquals(listOf("dial", "sms", "cam", null, "web"), defaults.put(4, "web").keys)
    }

    @Test
    fun puttingAnAppWhereItIsChangesNothing() {
        assertEquals(defaults, defaults.put(0, "dial"))
    }

    @Test
    fun aBadSlotChangesNothing() {
        assertEquals(defaults, defaults.put(-1, "x"))
        assertEquals(defaults, defaults.put(DOCK_SIZE, "x"))
    }

    @Test
    fun encodeAndDecodeRoundTripWithEmptySlots() {
        listOf(
            defaults,
            DockSlots.of(listOf("a", null, "c", null, "e")),
            DockSlots.of(listOf("a", null, null, null, null)),
            DockSlots.of(listOf(null, null, null, null, null)),
        ).forEach { slots ->
            assertEquals(slots, DockSlots.decode(slots.encode(), DockSlots.of(List(DOCK_SIZE) { "d$it" })))
        }
    }

    @Test
    fun shortOrCorruptDataIsPaddedWithTheDefaults() {
        assertEquals(listOf("x", "sms", "cam", "web", null), DockSlots.decode("x", defaults).keys)
        assertEquals(listOf(null, "sms", "cam", "web", null), DockSlots.decode("", defaults).keys)
        assertEquals(listOf("a", "b", "c", "d", "e"), DockSlots.decode("a\nb\nc\nd\ne\nf\ng", defaults).keys)
    }

    @Test
    fun ofAlwaysHasFiveSlots() {
        assertEquals(DOCK_SIZE, DockSlots.of(emptyList()).keys.size)
        assertEquals(DOCK_SIZE, DockSlots.of(List(9) { "k$it" }).keys.size)
    }

    private val dial = AppEntry("Phone", "com.dialer", "com.dialer.Main", 0)
    private val dialAlt = AppEntry("Phone (video)", "com.dialer", "com.dialer.Video", 0)
    private val work = AppEntry("Phone", "com.dialer", "com.dialer.Main", 10)
    private val web = AppEntry("Browser", "com.web", "com.web.Main", 0)

    @Test
    fun defaultsAreTheHandlersResolvedToLauncherApps() {
        val slots = DockDefaults.keys(
            listOf("com.dialer" to "com.dialer.Video", "com.web" to null, null, "com.gone" to "com.gone.Main", null),
            listOf(dial, dialAlt, work, web),
            ownUser = 0,
        )
        assertEquals(listOf(dialAlt.key, web.key, null, null, null), slots.keys)
    }

    @Test
    fun aHandlerWithoutAMatchingActivityFallsBackToTheFirstOfItsPackage() {
        val slots = DockDefaults.keys(listOf("com.dialer" to "com.dialer.Gone"), listOf(dial, dialAlt), ownUser = 0)
        assertEquals(dial.key, slots.keys.first())
    }

    @Test
    fun theWorkProfileNeverFillsADefault() {
        val slots = DockDefaults.keys(listOf("com.dialer" to "com.dialer.Main"), listOf(work), ownUser = 0)
        assertEquals(null, slots.keys.first())
    }
}
