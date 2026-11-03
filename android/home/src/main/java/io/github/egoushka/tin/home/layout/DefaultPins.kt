package io.github.egoushka.tin.home.layout

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.Settings
import io.github.egoushka.tin.home.apps.AppEntry

/** What a new Home pins above the dock: the phone's own default app for a few common jobs. */
object DefaultPins {
    private fun category(name: String) = { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, name) }

    private val jobs: List<() -> Intent> = listOf(
        { Intent(Settings.ACTION_SETTINGS) },
        category(Intent.CATEGORY_APP_CONTACTS),
        category(Intent.CATEGORY_APP_EMAIL),
        category(Intent.CATEGORY_APP_CALENDAR),
        category(Intent.CATEGORY_APP_GALLERY),
        category(Intent.CATEGORY_APP_MUSIC),
        category(Intent.CATEGORY_APP_CALCULATOR),
        { Intent(AlarmClock.ACTION_SHOW_ALARMS) },
    )

    /** Keys of [installed] apps in job order; a job nothing handles, or only the system chooser ("android"), adds none. */
    fun keys(context: Context, installed: List<AppEntry>): List<String> = jobs.mapNotNull { job ->
        val pkg = context.packageManager.resolveActivity(job(), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        installed.firstOrNull { it.pkg == pkg && pkg != "android" }?.key
    }.distinct()
}
