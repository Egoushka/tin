package io.github.egoushka.tin.fixture

import android.app.Activity
import android.os.Bundle

/** Exists to have a launcher tile; opening it does nothing. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
