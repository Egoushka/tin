package io.github.egoushka.tin.home.ui

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.egoushka.tin.home.apps.AppEntry
import io.github.egoushka.tin.home.search.Access
import io.github.egoushka.tin.home.search.Contact
import io.github.egoushka.tin.home.search.ContactRepository
import io.github.egoushka.tin.home.search.Index
import io.github.egoushka.tin.home.search.ShortcutEntry
import io.github.egoushka.tin.home.search.ShortcutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/** What the search bar looks in: the three indexes, the contacts permission state and the tap that asks for it. */
class SearchSources(
    val contacts: ContactRepository,
    val shortcuts: ShortcutRepository,
    val apps: Index<AppEntry>,
    val shortcutIndex: Index<ShortcutEntry>,
    val people: Index<Contact>,
    val access: Access,
    val allow: () -> Unit,
)

private val NoShortcuts = Index<ShortcutEntry>(emptyList()) { it.label }
private val NoPeople = Index<Contact>(emptyList()) { it.name }

/**
 * People and shortcuts load after the drawer first opens, not at startup, and stay current after that. Contacts are
 * asked for only by [SearchSources.allow], which the people row calls on a tap; typing never opens the dialog.
 */
@Composable
fun rememberSearchSources(all: List<AppEntry>, open: Boolean): SearchSources {
    val context = LocalContext.current
    val contacts = remember { ContactRepository(context) }
    val shortcuts = remember { ShortcutRepository(context) }
    var wanted by remember { mutableStateOf(open) }
    LaunchedEffect(open) { if (open) wanted = true }

    var granted by remember { mutableStateOf(contacts.granted()) }
    var refused by remember { mutableStateOf(false) }
    // Back from the app's settings the grant may have changed under us.
    DisposableEffect(context) {
        val lifecycle = (context as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) granted = contacts.granted() }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        refused = !ok
    }

    val appIndex = remember(all) { Index(all) { it.label } }
    val shortcutIndex by produceState(NoShortcuts, wanted) {
        if (!wanted) return@produceState
        shortcuts.changes().conflate().collect { value = withContext(Dispatchers.IO) { Index(shortcuts.load()) { it.label } } }
    }
    val people by produceState(NoPeople, wanted, granted) {
        if (!wanted || !granted) { value = NoPeople; return@produceState }
        contacts.changes().conflate().collect { value = withContext(Dispatchers.IO) { Index(contacts.load()) { it.name } } }
    }
    val access = when {
        granted -> Access.Granted
        refused -> Access.Denied
        else -> Access.Ask
    }
    return SearchSources(contacts, shortcuts, appIndex, shortcutIndex, people, access) {
        request.launch(Manifest.permission.READ_CONTACTS)
    }
}
