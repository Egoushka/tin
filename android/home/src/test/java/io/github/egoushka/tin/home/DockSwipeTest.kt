package io.github.egoushka.tin.home

import android.content.Intent
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import io.github.egoushka.tin.home.apps.AppShortcut
import io.github.egoushka.tin.home.gestures.DockSwipe
import io.github.egoushka.tin.home.layout.HomeFrame
import io.github.egoushka.tin.home.layout.ownsSwipeUp
import io.github.egoushka.tin.home.theme.Dimen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A swipe up on a dock tile belongs to the tile; the frame's swipe up keeps every other start. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h914dp-420dpi")
class DockSwipeTest {
    @get:Rule val rule = createComposeRule()

    private var frameUp = 0
    private var tileSwipes = 0
    private var tileClicks = 0
    private var tileHasSwipe by mutableStateOf(true)

    private fun show() = rule.setContent {
        HomeFrame(
            gap = 12.dp,
            gestures = true,
            onSwipeUp = { frameUp++ },
            onSwipeDown = {},
            onLongPress = {},
            top = { Text("12:00") },
            bottom = {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.testTag("tile").size(Dimen.tile)
                            .ownsSwipeUp(if (tileHasSwipe) ({ tileSwipes++ }) else null)
                            .clickable { tileClicks++ },
                    )
                }
            },
            stack = {},
        )
    }

    private fun swipeTile() = rule.onNodeWithTag("tile").performTouchInput {
        swipe(center, Offset(center.x, center.y - 120.dp.toPx()), durationMillis = 300)
    }

    @Test fun `a swipe up on the tile runs the tile's swipe, not the frame's, and is not a tap`() {
        show()
        swipeTile()
        rule.waitForIdle()
        assertEquals(listOf(1, 0, 0), listOf(tileSwipes, frameUp, tileClicks))
    }

    @Test fun `a tap on the tile still clicks it`() {
        show()
        rule.onNodeWithTag("tile").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(listOf(0, 0, 1), listOf(tileSwipes, frameUp, tileClicks))
    }

    @Test fun `a tile with no swipe leaves the swipe to the frame`() {
        tileHasSwipe = false
        show()
        swipeTile()
        rule.waitForIdle()
        assertEquals(listOf(0, 1), listOf(tileSwipes, frameUp))
    }

    @Test fun `an app's swipe is its first listed shortcut`() {
        val first = AppShortcut("a", "Selfie")
        val swipe = DockSwipe.of(true, DockDefault.Camera, listOf(first, AppShortcut("b", "Video")))
        assertEquals(DockSwipe.Shortcut(first), swipe)
    }

    @Test fun `an app without shortcuts has no swipe, even in the Camera slot`() {
        assertNull(DockSwipe.of(true, DockDefault.Camera, emptyList()))
    }

    @Test fun `the empty Camera slot swipes to video and the other empty slots to nothing`() {
        val swipe = DockSwipe.of(false, DockDefault.Camera, emptyList())
        assertTrue(swipe is DockSwipe.Open)
        assertEquals(MediaStore.INTENT_ACTION_VIDEO_CAMERA, (swipe as DockSwipe.Open).intent.action)
        for (d in DockDefault.entries - DockDefault.Camera) assertNull(d.name, DockSwipe.of(false, d, emptyList()))
    }
}
