package io.github.egoushka.tin.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import io.github.egoushka.tin.home.apps.AppRepository
import io.github.egoushka.tin.home.layout.HomeState
import io.github.egoushka.tin.home.signals.Signals
import io.github.egoushka.tin.home.theme.Frost
import io.github.egoushka.tin.home.theme.LocalFrost
import io.github.egoushka.tin.home.theme.LocalTune
import io.github.egoushka.tin.home.theme.Ng
import io.github.egoushka.tin.home.tune.Tune
import io.github.egoushka.tin.home.tune.TuneStore
import io.github.egoushka.tin.home.ui.HomeScreen
import io.github.egoushka.tin.home.ui.Layer
import kotlinx.coroutines.launch

class HomeActivity : ComponentActivity() {
    /** The open layer; HOME pressed while Home is in front closes it (stock launcher behaviour). */
    private var layer by mutableStateOf(Layer.Home)

    private val calendarPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Signals.of(applicationContext).calendar.refresh()
        // A second refusal is final on Android; only the app's settings can still grant it.
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)) actions.openAppSettings()
    }
    private lateinit var home: HomeState
    private val actions: Actions by lazy { Actions(this) { calendarPermission.launch(Manifest.permission.READ_CALENDAR) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = SystemBarStyle.dark(Ng.bg.copy(alpha = 0f).toArgb())
        enableEdgeToEdge(statusBarStyle = dark, navigationBarStyle = dark)
        super.onCreate(savedInstanceState)

        val store = TuneStore(applicationContext)
        val apps = AppRepository(applicationContext)
        val signals = Signals.of(applicationContext)
        home = HomeState.of(this)
        askBluetoothOnce()

        setContent {
            val tune by store.tune.collectAsState(initial = Tune())
            val frost = remember { Frost() }
            val scope = rememberCoroutineScope()
            BackHandler(enabled = layer != Layer.Home) { layer = Layer.Home }
            CompositionLocalProvider(LocalTune provides tune, LocalFrost provides frost) {
                HomeScreen(
                    tune = tune,
                    frost = frost,
                    layer = layer,
                    onLayer = { layer = it },
                    onTune = { t -> scope.launch { store.save(t) } },
                    apps = apps,
                    actions = actions,
                    signals = signals,
                    home = home,
                )
            }
        }
    }

    /** Earbud battery needs BLUETOOTH_CONNECT; asked once so a refusal does not nag on every Home press. */
    private fun askBluetoothOnce() {
        val prefs = getSharedPreferences("tin.asked", MODE_PRIVATE)
        if (prefs.getBoolean("bluetooth", false)) return
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) return
        prefs.edit().putBoolean("bluetooth", true).apply()
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
    }

    override fun onStart() {
        super.onStart()
        home.widgets.start()
    }

    override fun onStop() {
        home.widgets.stop()
        super.onStop()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // the bind and configure screens answer only through the legacy result
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        home.widgets.onResult(requestCode, resultCode)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            layer = Layer.Home
            home.edit(false)
        }
    }
}
