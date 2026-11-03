package io.github.egoushka.tin.home.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import io.github.egoushka.tin.home.R

/** What the clock's screen-reader actions do: the same steps as the gestures, for a person who cannot make them. */
class GlanceRoutes(
    val apps: () -> Unit,
    val notifications: () -> Unit,
    val quickSettings: () -> Unit,
    val today: () -> Unit,
    val tune: () -> Unit,
)

/** The swipes, the two-finger drags, the pinch and the long press have no screen-reader equivalent; the clock is the first stop. */
@Composable
fun Modifier.glanceActions(routes: GlanceRoutes): Modifier {
    val apps = stringResource(R.string.action_apps)
    val notifications = stringResource(R.string.action_notifications)
    val quickSettings = stringResource(R.string.action_quick_settings)
    val today = stringResource(R.string.action_today)
    val tune = stringResource(R.string.tune)
    return semantics(mergeDescendants = true) {
        customActions = listOf(
            CustomAccessibilityAction(apps) { routes.apps(); true },
            CustomAccessibilityAction(notifications) { routes.notifications(); true },
            CustomAccessibilityAction(quickSettings) { routes.quickSettings(); true },
            CustomAccessibilityAction(today) { routes.today(); true },
            CustomAccessibilityAction(tune) { routes.tune(); true },
        )
    }
}
