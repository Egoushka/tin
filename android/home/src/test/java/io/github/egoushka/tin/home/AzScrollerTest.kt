package io.github.egoushka.tin.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.egoushka.tin.home.apps.AppEntry
import io.github.egoushka.tin.home.apps.AppPrefs
import io.github.egoushka.tin.home.apps.AppRepository
import io.github.egoushka.tin.home.apps.AzIndex
import io.github.egoushka.tin.home.ui.AppTile
import io.github.egoushka.tin.home.ui.AzScroller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** B19 and B21: the strip reports where to scroll, and a long label in a drawer tile is shown whole. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h914dp-420dpi")
class AzScrollerTest {
    @get:Rule val rule = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()
    private val index = AzIndex.of(listOf("Alarm", "Banking", "Camera", "Dialer", "Files"))
    private val jumps = mutableListOf<Int>()

    private fun strip() = rule.setContent {
        Box(Modifier.height(400.dp)) { AzScroller(index, onJump = { jumps += it }) }
    }

    @Test fun `every letter has a jump description and activating it reports the position`() {
        strip()
        index.letters.forEach { letter ->
            val description = context.getString(R.string.az_jump, letter.char.toString())
            rule.onNode(hasContentDescription(description)).performClick()
        }
        assertEquals(index.letters.map { it.position }, jumps)
    }

    @Test fun `a touch tap on the strip reports the letter under it`() {
        strip()
        rule.onNode(hasContentDescription(context.getString(R.string.az_jump, "C"))).performTouchInput { click(center) }
        assertTrue(jumps.isNotEmpty() && jumps.last() == 2)
    }

    @Test fun `a strip with one letter is not shown`() {
        rule.setContent { AzScroller(AzIndex.of(listOf("Alarm", "Amazon")), onJump = { jumps += it }) }
        assertEquals(0, rule.onAllNodes(hasContentDescription(context.getString(R.string.az_jump, "A"))).fetchSemanticsNodes().size)
    }

    @Test fun `a two word label that needs two lines is shown whole in a drawer tile`() {
        val app = AppEntry("Privat24 business", "ua.privat24", "ua.privat24.Main", 0)
        rule.setContent {
            Box(Modifier.width(80.dp)) { AppTile(app, AppRepository(context, AppPrefs(context))) {} }
        }
        val results = mutableListOf<TextLayoutResult>()
        val node = rule.onNode(hasText("Privat24 business", substring = true)).fetchSemanticsNode()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!.invoke(results)
        val layout = results.single()
        assertFalse("label is cut off: ${layout.lineCount} lines", layout.hasVisualOverflow)
        assertTrue(layout.lineCount in 1..2)
    }
}
