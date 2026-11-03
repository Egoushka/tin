package io.github.egoushka.tin.home

import io.github.egoushka.tin.home.apps.AppEntry
import io.github.egoushka.tin.home.apps.Apps
import io.github.egoushka.tin.home.search.Index
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A3: hidden apps leave the drawer and search, and come back. */
class HiddenAppsTest {
    private val signal = AppEntry("Signal", "org.signal", "org.signal.Main", 0)
    private val maps = AppEntry("Maps", "com.maps", "com.maps.Main", 0)
    private val work = AppEntry("Signal", "org.signal", "org.signal.Main", 10)
    private val all = listOf(maps, signal, work)

    @Test
    fun hiddenAppsAreNotVisibleAndAreListedAsHidden() {
        val apps = Apps(all, hidden = setOf(maps.key), loaded = true)
        assertEquals(listOf(signal, work), apps.visible)
        assertEquals(listOf(maps), apps.hiddenApps)
    }

    @Test
    fun unhidingBringsTheAppBack() {
        val apps = Apps(all, hidden = setOf(maps.key))
        val back = apps.copy(hidden = apps.hidden - maps.key)
        assertEquals(all, back.visible)
        assertEquals(emptyList<AppEntry>(), back.hiddenApps)
    }

    @Test
    fun theSameAppInAnotherProfileIsAnotherKey() {
        val apps = Apps(all, hidden = setOf(work.key))
        assertEquals(listOf(maps, signal), apps.visible)
    }

    @Test
    fun searchOverTheVisibleListLeavesHiddenAppsOut() {
        val apps = Apps(all, hidden = setOf(maps.key))
        val index = Index(apps.visible) { it.label }
        assertEquals(emptyList<AppEntry>(), index.match("maps").map { it.item })
        assertEquals(listOf(signal, work), index.match("sig").map { it.item })
    }

    @Test
    fun aKeyOfAnAppThatIsGoneIsHarmless() {
        val apps = Apps(all, hidden = setOf("gone.pkg/gone.Main#0"), loaded = true)
        assertEquals(all, apps.visible)
        assertEquals(emptyList<AppEntry>(), apps.hiddenApps)
    }

    @Test
    fun lookupByKey() {
        val apps = Apps(all)
        assertEquals(maps, apps[maps.key])
        assertNull(apps["gone.pkg/gone.Main#0"])
        assertNull(apps[null])
    }
}
