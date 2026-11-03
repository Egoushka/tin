package io.github.egoushka.tin.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import io.github.egoushka.tin.home.layout.GrantRow
import io.github.egoushka.tin.home.layout.StackTags
import io.github.egoushka.tin.home.theme.Dimen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A card that can only ask for a grant shrinks to one row: a single 48 dp target, hideable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h914dp-420dpi")
class GrantRowTest {
    @get:Rule val rule = createComposeRule()

    private val res get() = RuntimeEnvironment.getApplication().resources
    private var granted = 0
    private var hidden = 0

    private fun show(fontScale: Float, hideable: Boolean = true) = rule.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
            GrantRow("cal", R.string.calendar_access, { granted++ }, if (hideable) ({ hidden++ }) else null)
        }
    }

    @Test fun `it is one 48 dp target at font scale 1_0`() {
        show(1f)
        rule.onNodeWithTag(StackTags.Grant).assertIsDisplayed().assertHeightIsEqualTo(Dimen.target)
    }

    @Test fun `it stays 48 dp at font scale 1_3`() {
        show(1.3f)
        rule.onNodeWithTag(StackTags.Grant).assertIsDisplayed().assertHeightIsEqualTo(Dimen.target)
    }

    @Test fun `a tap on the row asks for the grant, the x hides the card`() {
        show(1f)
        rule.onNodeWithTag(StackTags.Grant).performClick()
        assertEquals(1 to 0, granted to hidden)
        val label = res.getString(R.string.edit_hide, res.getString(R.string.calendar_access))
        rule.onNode(hasContentDescription(label)).performClick()
        assertEquals(1 to 1, granted to hidden)
    }

    @Test fun `without a hide control there is no x`() {
        show(1f, hideable = false)
        val label = res.getString(R.string.edit_hide, res.getString(R.string.calendar_access))
        rule.onNode(hasContentDescription(label)).assertDoesNotExist()
    }
}

