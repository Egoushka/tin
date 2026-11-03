package io.github.egoushka.tin.home

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.egoushka.tin.home.layout.CardId
import io.github.egoushka.tin.home.layout.HomeLayout
import io.github.egoushka.tin.home.layout.LayoutStore
import io.github.egoushka.tin.home.layout.PinStore
import io.github.egoushka.tin.home.layout.Slot
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutStoreTest {
    private val dir = File.createTempFile("tin-layout", "").apply { delete(); mkdirs() }
    private val file = File(dir, "tin.tune.preferences_pb")
    private val scopes = mutableListOf<CoroutineScope>()

    /** A new DataStore over the same file stands in for the process starting again. */
    private fun open(): Pair<LayoutStore, PinStore> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        val ds = PreferenceDataStoreFactory.create(scope = scope) { file }
        return LayoutStore(ds) to PinStore(ds)
    }

    /** Waits for the first store to release the file: a new DataStore on a file still held by a cancelled one throws. */
    private suspend fun closeFirst() = scopes.removeAt(0).coroutineContext[Job]!!.cancelAndJoin()

    @After fun close() {
        scopes.forEach { it.cancel() }
        dir.deleteRecursively()
    }

    @Test fun `an empty file reads as the default layout`() = runBlocking {
        assertEquals(HomeLayout(), open().first.layout.first())
    }

    @Test fun `a change is read by a new store over the same file`() = runBlocking {
        val first = open().first
        first.update { it.hide(Slot.Card(CardId.Today)).move(Slot.Card(CardId.Homelab), -1).addWidget(11, 3) }
        val expected = first.layout.first()
        closeFirst()
        assertEquals(expected, open().first.layout.first())
        assertEquals(setOf(11), expected.widgetIds)
    }

    @Test fun `concurrent updates do not lose a change`() = runBlocking {
        val store = open().first
        (1..20).map { id -> async(Dispatchers.IO) { store.update { it.addWidget(id, 1) } } }.awaitAll()
        assertEquals((1..20).toSet(), store.layout.first().widgetIds)
    }

    @Test fun `pin appends, a repeat is ignored, unpin removes`() = runBlocking {
        val pins = open().second
        pins.pin("a"); pins.pin("b"); pins.pin("a")
        assertEquals(listOf("a", "b"), pins.keys.first())
        pins.unpin("a")
        assertEquals(listOf("b"), pins.keys.first())
        pins.unpin("zzz")
        assertEquals(listOf("b"), pins.keys.first())
    }

    @Test fun `the cap holds`() = runBlocking {
        val pins = open().second
        (1..PinStore.MAX + 3).forEach { pins.pin("app$it") }
        assertEquals((1..PinStore.MAX).map { "app$it" }, pins.keys.first())
    }

    @Test fun `pins keep their order across a new store`() = runBlocking {
        val pins = open().second
        pins.pin("c"); pins.pin("a"); pins.pin("b")
        closeFirst()
        assertEquals(listOf("c", "a", "b"), open().second.keys.first())
    }

    @Test fun `pins and layout share a file without touching each other`() = runBlocking {
        val (layout, pins) = open()
        layout.update { it.hide(Slot.Card(CardId.Music)) }
        pins.pin("a")
        assertEquals(setOf<Slot>(Slot.Card(CardId.Music)), layout.layout.first().hidden)
        assertEquals(listOf("a"), pins.keys.first())
    }

    @Test fun `seed fills a store that never held pins, up to the cap`() = runBlocking {
        val pins = open().second
        pins.seed((1..PinStore.MAX + 3).map { "app$it" })
        assertEquals((1..PinStore.MAX).map { "app$it" }, pins.keys.first())
    }

    @Test fun `seed does nothing once any pin list was written, even an empty one`() = runBlocking {
        val pins = open().second
        pins.pin("a"); pins.unpin("a")
        pins.seed(listOf("x", "y"))
        assertEquals(emptyList<String>(), pins.keys.first())
        closeFirst()
        val again = open().second
        again.seed(listOf("x"))
        assertEquals(emptyList<String>(), again.keys.first())
    }

    @Test fun `seed with nothing to store leaves the store open for a later seed`() = runBlocking {
        val pins = open().second
        pins.seed(emptyList())
        pins.seed(listOf("x"))
        assertEquals(listOf("x"), pins.keys.first())
    }
}
