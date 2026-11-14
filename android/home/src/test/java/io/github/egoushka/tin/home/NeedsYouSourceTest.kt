package io.github.egoushka.tin.home

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import io.github.egoushka.tin.home.signals.NeedsYouSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A provider row with a NULL id used to throw out of the read and crash Home; the row is skipped and the rest shows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NeedsYouSourceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    class NullId : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor = when (uri.lastPathSegment) {
            "counts" -> MatrixCursor(arrayOf("open", "held")).apply { addRow(arrayOf<Any?>(2, 0)) }
            else -> MatrixCursor(arrayOf("id", "title", "source", "created_at", "held", "deep_link")).apply {
                addRow(arrayOf<Any?>(null, "no id", "x", 1L, 0, null))
                addRow(arrayOf<Any?>("a1", "kept", "x", 2L, 0, null))
            }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<String>?) = 0
    }

    @After fun stop() = scope.cancel()

    @Test fun `a row with a null id is skipped and the others are read`() {
        Robolectric.buildContentProvider(NullId::class.java).create("approvals.nullid")
        val source = NeedsYouSource(RuntimeEnvironment.getApplication(), "approvals.nullid")
        source.start(scope)
        val state = runBlocking { withTimeout(5_000) { source.state.first { it.status != NeedsYouSource.Status.Loading } } }
        assertEquals(NeedsYouSource.Status.Ok, state.status)
        assertEquals(listOf("a1"), state.needs.rows.map { it.id })
    }
}
