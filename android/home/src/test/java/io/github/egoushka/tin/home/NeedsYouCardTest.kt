package io.github.egoushka.tin.home

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.egoushka.tin.home.signals.NeedsYouSource
import io.github.egoushka.tin.home.ui.NeedsYouCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** B7: Approvals refuses a caller whose certificate it does not list; Home then has nothing to show, so no card (F4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h914dp-420dpi")
class NeedsYouCardTest {
    @get:Rule val rule = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val title get() = context.getString(R.string.needs_you)

    class Approvals : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor = when (uri.lastPathSegment) {
            "counts" -> MatrixCursor(arrayOf("open", "held")).apply { addRow(arrayOf(0, 0)) }
            else -> MatrixCursor(arrayOf("id", "title", "source", "created_at", "held", "deep_link"))
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<String>?) = 0
    }

    @After fun stop() = scope.cancel()

    private fun show(authority: String): NeedsYouSource {
        val source = NeedsYouSource(context, authority)
        rule.setContent { NeedsYouCard(source, Actions(context)) }
        source.start(scope)
        return source
    }

    @Test fun `no card while the provider is not there to read`() {
        val source = show("approvals.refused")
        rule.waitUntil(5_000) { source.state.value.status == NeedsYouSource.Status.Unavailable }
        rule.waitForIdle()
        assertEquals(0, cards())
    }

    @Test fun `the card is back once the provider is readable`() {
        Robolectric.buildContentProvider(Approvals::class.java).create("approvals.readable")
        val source = show("approvals.readable")
        rule.waitUntil(5_000) { source.state.value.status == NeedsYouSource.Status.Ok }
        assertEquals(1, cards())
    }

    private fun cards() = rule.onAllNodes(hasText(title, substring = true, ignoreCase = true)).fetchSemanticsNodes().size
}
