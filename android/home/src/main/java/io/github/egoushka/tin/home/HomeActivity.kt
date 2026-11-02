package io.github.egoushka.tin.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.lifecycleScope
import io.github.egoushka.tin.home.apps.AppRepository
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

    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = SystemBarStyle.dark(Ng.bg.copy(alpha = 0f).toArgb())
        enableEdgeToEdge(statusBarStyle = dark, navigationBarStyle = dark)
        super.onCreate(savedInstanceState)

        val store = TuneStore(applicationContext)
        val apps = AppRepository(applicationContext)
        val actions = Actions(this)
        val signals = Signals(applicationContext).also { it.start(lifecycleScope) }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) layer = Layer.Home
    }
}
