package io.github.egoushka.tin.home

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.tune.Tune
import io.github.egoushka.tin.home.ui.TorchSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.ShadowCameraManager

/** The controls on Home that have state or a result worth proving: the torch switch and the torch behind it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h914dp-420dpi")
class HomeControlsTest {
    @get:Rule val rule = createComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()
    private var on by mutableStateOf(false)
    private var asked = emptyList<Boolean>()

    private fun showSwitch() = rule.setContent { TorchSwitch(on, { asked = asked + it }, Tune()) }
    private fun torch() = rule.onNode(hasContentDescription(app.getString(R.string.torch)))

    private fun flash(id: String, has: Boolean): ShadowCameraManager {
        val manager = Shadow.extract<ShadowCameraManager>(app.getSystemService(CameraManager::class.java))
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        Shadow.extract<ShadowCameraCharacteristics>(characteristics).set(CameraCharacteristics.FLASH_INFO_AVAILABLE, has)
        manager.addCamera(id, characteristics)
        return manager
    }

    @Test fun `the switch is a 48 dp labelled switch that says off at rest`() {
        showSwitch()
        torch().assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertWidthIsEqualTo(Dimen.target).assertHeightIsEqualTo(Dimen.target)
            .assertIsOff()
    }

    @Test fun `a tap asks for the opposite state and the switch follows what it is given`() {
        showSwitch()
        torch().performClick()
        assertEquals(listOf(true), asked)
        torch().assertIsOff()
        rule.runOnIdle { on = true }
        torch().assertIsOn().performClick()
        assertEquals(listOf(true, false), asked)
    }

    @Test fun `a phone without a flash unit has no torch`() {
        flash("0", has = false)
        assertFalse(Actions(app).hasTorch)
    }

    @Test fun `toggleTorch switches the flash and the state follows the system`() {
        val manager = flash("0", has = true)
        val actions = Actions(app)
        assertTrue(actions.hasTorch)
        assertFalse(actions.torch.value)
        actions.toggleTorch()
        assertTrue(actions.torch.value)
        assertTrue(manager.getTorchMode("0"))
        actions.toggleTorch()
        assertFalse(actions.torch.value)
    }
}
