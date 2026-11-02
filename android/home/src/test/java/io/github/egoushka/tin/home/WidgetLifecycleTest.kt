package io.github.egoushka.tin.home

import android.content.ComponentName
import io.github.egoushka.tin.home.layout.Step
import io.github.egoushka.tin.home.layout.WidgetBackend
import io.github.egoushka.tin.home.layout.WidgetLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLifecycleTest {
    private class Fake(
        var bindsAtOnce: Boolean = true,
        var configures: Boolean = false,
        var refuses: Boolean = false,
    ) : WidgetBackend {
        val live = mutableSetOf<Int>()
        val deleted = mutableListOf<Int>()
        private var next = 100

        override fun allocate(): Int? = if (refuses) null else next++.also { live += it }
        override fun delete(id: Int) { live -= id; deleted += id }
        override fun liveIds(): Set<Int> = live.toSet()
        override fun bind(id: Int, provider: ComponentName) = bindsAtOnce
        override fun needsConfigure(id: Int) = configures
    }

    private val clock = ComponentName("com.example", "com.example.Clock")

    // The unit-test ComponentName is a stub whose equals is false, so the provider is compared by identity.
    private fun assertAsksBind(id: Int, step: Step) {
        assertTrue(step is Step.AskBind)
        step as Step.AskBind
        assertEquals(id, step.id)
        assertSame(clock, step.provider)
    }

    @Test fun `bound at once without configure is added`() {
        val host = Fake()
        val life = WidgetLifecycle(host)
        assertEquals(Step.Added(100), life.begin(clock))
        assertNull(life.pending)
        assertEquals(setOf(100), host.live)
    }

    @Test fun `bind refused, then allowed, is added`() {
        val host = Fake(bindsAtOnce = false)
        val life = WidgetLifecycle(host)
        assertAsksBind(100, life.begin(clock))
        assertEquals(100, life.pending)
        assertEquals(Step.Added(100), life.bindResult(true))
        assertEquals(setOf(100), host.live)
    }

    @Test fun `bind permission denied deletes the id`() {
        val host = Fake(bindsAtOnce = false)
        val life = WidgetLifecycle(host)
        life.begin(clock)
        assertEquals(Step.Failed, life.bindResult(false))
        assertTrue(host.live.isEmpty())
        assertNull(life.pending)
    }

    @Test fun `configure needed, then OK, is added`() {
        val host = Fake(configures = true)
        val life = WidgetLifecycle(host)
        assertEquals(Step.Configure(100), life.begin(clock))
        assertEquals(Step.Added(100), life.configureResult(true))
        assertEquals(setOf(100), host.live)
    }

    @Test fun `bind permission then configure runs both steps in order`() {
        val host = Fake(bindsAtOnce = false, configures = true)
        val life = WidgetLifecycle(host)
        assertAsksBind(100, life.begin(clock))
        assertEquals(Step.Configure(100), life.bindResult(true))
        assertEquals(Step.Added(100), life.configureResult(true))
    }

    @Test fun `configure cancelled deletes the id`() {
        val host = Fake(configures = true)
        val life = WidgetLifecycle(host)
        life.begin(clock)
        assertEquals(Step.Failed, life.configureResult(false))
        assertTrue(host.live.isEmpty())
        assertNull(life.pending)
    }

    @Test fun `allocation failure leaves no id`() {
        val host = Fake(refuses = true)
        val life = WidgetLifecycle(host)
        assertEquals(Step.Failed, life.begin(clock))
        assertTrue(host.live.isEmpty())
        assertNull(life.pending)
    }

    @Test fun `a result with nothing pending is a failure and deletes nothing`() {
        val host = Fake()
        val life = WidgetLifecycle(host)
        assertEquals(Step.Failed, life.bindResult(true))
        assertEquals(Step.Failed, life.configureResult(true))
        assertTrue(host.deleted.isEmpty())
    }

    @Test fun `starting a second flow deletes the abandoned id`() {
        val host = Fake(bindsAtOnce = false)
        val life = WidgetLifecycle(host)
        life.begin(clock)
        life.begin(clock)
        assertEquals(listOf(100), host.deleted)
        assertEquals(setOf(101), host.live)
        assertEquals(101, life.pending)
    }

    @Test fun `cancel deletes the pending id`() {
        val host = Fake(bindsAtOnce = false)
        val life = WidgetLifecycle(host)
        life.begin(clock)
        life.cancel()
        assertTrue(host.live.isEmpty())
        life.cancel()
        assertEquals(listOf(100), host.deleted)
    }

    @Test fun `sweep deletes host ids the layout does not list`() {
        val host = Fake().apply { live += setOf(1, 2, 3) }
        val life = WidgetLifecycle(host)
        assertEquals(setOf(1, 3), life.sweep(listed = setOf(2)))
        assertEquals(setOf(2), host.live)
    }

    @Test fun `sweep leaves the pending id alone`() {
        val host = Fake(bindsAtOnce = false).apply { live += 1 }
        val life = WidgetLifecycle(host)
        life.begin(clock)
        assertEquals(setOf(1), life.sweep(listed = emptySet()))
        assertEquals(setOf(100), host.live)
    }

    @Test fun `remove deletes the id and is safe to repeat`() {
        val host = Fake().apply { live += 5 }
        val life = WidgetLifecycle(host)
        life.remove(5)
        life.remove(5)
        assertTrue(host.live.isEmpty())
    }
}
