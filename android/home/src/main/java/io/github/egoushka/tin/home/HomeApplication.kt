package io.github.egoushka.tin.home

import android.app.Application
import de.mm20.launcher2.applications.applicationsModule
import de.mm20.launcher2.baseModule
import de.mm20.launcher2.data.customattrs.customAttrsModule
import de.mm20.launcher2.data.i18nDataModule
import de.mm20.launcher2.database.databaseModule
import de.mm20.launcher2.icons.iconsModule
import de.mm20.launcher2.permissions.permissionsModule
import de.mm20.launcher2.preferences.preferencesModule
import de.mm20.launcher2.profiles.profilesModule
import de.mm20.launcher2.searchable.searchableModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/** Starts Kvaesitso's engine: its parts find each other through Koin, and these are the modules apps and icons reach. */
class HomeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Robolectric makes a new Application for every test in one JVM; on a phone there is nothing to stop.
        stopKoin()
        startKoin {
            androidContext(this@HomeApplication)
            modules(
                applicationsModule, iconsModule,
                baseModule, customAttrsModule, databaseModule, i18nDataModule, permissionsModule, preferencesModule,
                profilesModule, searchableModule,
            )
        }
    }
}
