package io.github.egoushka.tin.home

import io.github.egoushka.tin.home.apps.AppEntry
import io.github.egoushka.tin.home.apps.AppList
import io.github.egoushka.tin.home.apps.AppSource
import io.github.egoushka.tin.home.apps.Apps
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A4: the model follows the source, one reload per burst. */
class AppListTest {
    private class FakeSource(var apps: List<AppEntry>) : AppSource {
        val events = Channel<Set<String>>(Channel.UNLIMITED)
        val loads = AtomicInteger()
        val registered = AtomicBoolean(false)

        override fun load(): List<AppEntry> {
            loads.incrementAndGet()
            return apps
        }

        override fun changes(): Flow<Set<String>> = events.receiveAsFlow()
            .onStart { registered.set(true); emit(emptySet()) }
            .onCompletion { registered.set(false) }

        fun send(vararg pkgs: String) {
            events.trySend(pkgs.toSet())
        }
    }

    private fun app(label: String, pkg: String, user: Long = 0) = AppEntry(label, pkg, "$pkg.Main", user)

    private val signal = app("Signal", "org.signal")
    private val maps = app("Maps", "com.maps")
    private val camera = app("Camera", "com.camera")

    private fun run(
        source: FakeSource,
        hidden: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
        block: suspend CoroutineScope.(AppList, Job) -> Unit,
    ) = runBlocking {
        val list = AppList(source, hidden, Dispatchers.Unconfined)
        val keeping = launch { list.keep() }
        try {
            withTimeout(5_000) { block(list, keeping) }
        } finally {
            keeping.cancelAndJoin()
        }
    }

    private suspend fun AppList.await(what: (Apps) -> Boolean) = withTimeout(5_000) { state.first(what) }

    @Test
    fun firstLoadFillsTheListAToZ() = run(FakeSource(listOf(signal, maps, camera))) { list, _ ->
        val state = list.await { it.loaded }
        assertEquals(listOf("Camera", "Maps", "Signal"), state.all.map { it.label })
        assertEquals(state.all, list.visible.value)
    }

    @Test
    fun anInstallARemovalAndAChangeEachNeedOneEvent() {
        val source = FakeSource(listOf(signal, maps))
        run(source) { list, _ ->
            list.await { it.loaded }
            val first = source.loads.get()

            source.apps = listOf(signal, maps, camera)
            source.send(camera.pkg)
            assertEquals(3, list.await { it.all.size == 3 }.all.size)

            source.apps = listOf(signal, camera)
            source.send(maps.pkg)
            assertEquals(listOf("Camera", "Signal"), list.await { it.all.size == 2 }.all.map { it.label })

            source.apps = listOf(signal.copy(label = "Signal Beta"), camera)
            source.send(signal.pkg)
            assertEquals(listOf("Camera", "Signal Beta"), list.await { "Signal Beta" in it.all.map { a -> a.label } }.all.map { it.label })

            assertEquals(first + 3, source.loads.get())
        }
    }

    @Test
    fun aBurstEndsInTheLastStateWithFewerLoadsThanEvents() {
        val source = FakeSource(listOf(signal))
        run(source) { list, _ ->
            list.await { it.loaded }
            val first = source.loads.get()

            source.apps = listOf(signal, maps, camera)
            repeat(10) { source.send(camera.pkg) }
            list.await { it.all.size == 3 }

            assertTrue("loads=${source.loads.get() - first}", source.loads.get() - first < 10)
        }
    }

    @Test
    fun aChangedPackageGetsANewRevAndTheOthersKeepTheirs() {
        val source = FakeSource(listOf(signal, maps))
        run(source) { list, _ ->
            val before = list.await { it.loaded }
            assertEquals(listOf(0, 0), before.all.map { it.rev })

            source.send(signal.pkg)
            val after = list.await { s -> s.all.any { it.rev > 0 } }
            assertEquals(1, after.all.single { it.pkg == signal.pkg }.rev)
            assertEquals(0, after.all.single { it.pkg == maps.pkg }.rev)

            source.send(signal.pkg)
            assertEquals(2, list.await { s -> s.all.any { it.rev > 1 } }.all.single { it.pkg == signal.pkg }.rev)
        }
    }

    @Test
    fun hidingChangesTheListWithoutASourceEvent() {
        val source = FakeSource(listOf(signal, maps))
        val hidden = MutableStateFlow(emptySet<String>())
        run(source, hidden) { list, _ ->
            list.await { it.loaded }
            val loads = source.loads.get()

            hidden.value = setOf(maps.key)
            list.await { it.hidden.isNotEmpty() }
            assertEquals(listOf(signal), list.visible.value)
            assertEquals(listOf(maps), list.state.value.hiddenApps)

            hidden.value = emptySet()
            list.await { it.hidden.isEmpty() }
            assertEquals(listOf(maps, signal), list.visible.value)
            assertEquals(loads, source.loads.get())
        }
    }

    @Test
    fun anAppHiddenBeforeTheFirstLoadIsNeverVisible() {
        val source = FakeSource(listOf(signal, maps))
        run(source, MutableStateFlow(setOf(maps.key))) { list, _ ->
            val state = list.await { it.loaded }
            assertEquals(listOf(signal), state.visible)
            assertEquals(listOf(signal), list.visible.value)
        }
    }

    @Test
    fun cancellingUnregistersTheSource() {
        val source = FakeSource(listOf(signal))
        run(source) { list, keeping ->
            list.await { it.loaded }
            assertTrue(source.registered.get())
            keeping.cancelAndJoin()
            assertFalse(source.registered.get())
        }
    }
}
