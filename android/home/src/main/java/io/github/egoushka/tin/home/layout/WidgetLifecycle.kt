package io.github.egoushka.tin.home.layout

import android.content.ComponentName

/** What the widget host does for Home; a fake in tests, `AppWidgetHost` and `AppWidgetManager` on the phone. */
interface WidgetBackend {
    /** A fresh id, or null when the host refuses. */
    fun allocate(): Int?
    fun delete(id: Int)
    /** Every id the host still holds for Home. */
    fun liveIds(): Set<Int>
    /** True when the id is bound; false when the user has to allow it first. */
    fun bind(id: Int, provider: ComponentName): Boolean
    fun needsConfigure(id: Int): Boolean
}

/** What the screen has to do next to finish adding a widget. */
sealed interface Step {
    data class AskBind(val id: Int, val provider: ComponentName) : Step
    data class Configure(val id: Int) : Step
    data class Added(val id: Int) : Step
    data object Failed : Step
}

/**
 * Allocate → bind → configure → add. An id exists in the host from allocation on, so every way out of the flow that
 * does not add the widget deletes it; [sweep] catches what a dead process left behind.
 */
class WidgetLifecycle(private val backend: WidgetBackend) {
    var pending: Int? = null
        private set

    fun begin(provider: ComponentName): Step {
        cancel()
        val id = backend.allocate() ?: return Step.Failed
        pending = id
        return if (backend.bind(id, provider)) afterBind(id) else Step.AskBind(id, provider)
    }

    fun bindResult(allowed: Boolean): Step {
        val id = pending ?: return Step.Failed
        return if (allowed) afterBind(id) else fail(id)
    }

    fun configureResult(ok: Boolean): Step {
        val id = pending ?: return Step.Failed
        return if (ok) done(id) else fail(id)
    }

    /** The flow is abandoned (a new one starts, or the screen is going away). */
    fun cancel() {
        pending?.let(::fail)
    }

    fun remove(id: Int) = backend.delete(id)

    /** Deletes host ids Home's layout does not list and no flow is waiting on; returns them. */
    fun sweep(listed: Set<Int>): Set<Int> {
        val strays = backend.liveIds() - listed - setOfNotNull(pending)
        strays.forEach(backend::delete)
        return strays
    }

    private fun afterBind(id: Int): Step = if (backend.needsConfigure(id)) Step.Configure(id) else done(id)

    private fun done(id: Int): Step {
        pending = null
        return Step.Added(id)
    }

    private fun fail(id: Int): Step {
        backend.delete(id)
        pending = null
        return Step.Failed
    }
}
