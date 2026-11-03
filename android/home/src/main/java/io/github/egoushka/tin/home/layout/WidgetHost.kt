package io.github.egoushka.tin.home.layout

import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs

/** One provider the picker offers: its own name and the name of the app it comes from. */
data class WidgetProvider(val component: ComponentName, val label: String, val app: String)

/**
 * A widget inside the scrolling stack. A view that handles a touch hides it from the scroll, so a vertical drag over a
 * widget that does not scroll itself is taken from the widget and goes to the stack; one that scrolls keeps its drags.
 * With [sideways] set (Home has more than one page) a sideways drag is handled the same way and goes to the pager.
 */
internal class StackWidgetView(context: Context) : AppWidgetHostView(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var scrollsUp = false
    private var scrollsAcross = false
    private var taken = false

    var sideways = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                scrollsUp = scrolls(this, vertical = true)
                scrollsAcross = scrolls(this, vertical = false)
                taken = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                if (!taken) {
                    taken = if (dy > dx) !scrollsUp && dy > slop else sideways && !scrollsAcross && dx > slop
                }
            }
        }
        return taken
    }

    // Declining the touch lets the stack's scroll see the rest of the drag; it never consumes one, so there is no click to perform.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent) = false

    private fun scrolls(v: View, vertical: Boolean): Boolean =
        (if (vertical) v.canScrollVertically(1) || v.canScrollVertically(-1) else v.canScrollHorizontally(1) || v.canScrollHorizontally(-1)) ||
            (v is ViewGroup && (0 until v.childCount).any { scrolls(v.getChildAt(it), vertical) })
}

/**
 * Home's `AppWidgetHost`. Android's bind and configure screens run outside Home and answer through [onResult],
 * which the activity forwards from `onActivityResult`.
 */
class WidgetHost(private val activity: Activity) : WidgetBackend {
    private val manager = AppWidgetManager.getInstance(activity)
    private val host = object : AppWidgetHost(activity.applicationContext, HOST_ID) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?) =
            StackWidgetView(context)
    }
    private val life = WidgetLifecycle(this)

    /** Called with the new id and its starting row count once a widget is bound and configured. */
    var onAdded: (id: Int, rows: Int) -> Unit = { _, _ -> }

    /** A host that listens gets its widgets' updates; one that does not just shows the last frame. */
    fun start() { runCatching { host.startListening() } }
    fun stop() { runCatching { host.stopListening() } }

    fun providers(): List<WidgetProvider> {
        val pm = activity.packageManager
        return manager.installedProviders
            .filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }
            .mapNotNull { info ->
                runCatching {
                    val app = pm.getApplicationInfo(info.provider.packageName, PackageManager.ApplicationInfoFlags.of(0))
                    WidgetProvider(info.provider, info.loadLabel(pm), app.loadLabel(pm).toString())
                }.getOrNull()
            }
            .sortedWith(compareBy({ it.app.lowercase() }, { it.label.lowercase() }))
    }

    /** Null when the provider's app is gone. */
    fun label(id: Int): String? = manager.getAppWidgetInfo(id)?.loadLabel(activity.packageManager)

    fun rowRange(id: Int): IntRange? {
        val info = manager.getAppWidgetInfo(id) ?: return null
        val density = activity.resources.displayMetrics.density
        return HomeLayout.rowRange(
            (info.minHeight / density).toInt(),
            (info.minResizeHeight / density).toInt(),
            info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0,
        )
    }

    fun createView(context: Context, id: Int): AppWidgetHostView? =
        manager.getAppWidgetInfo(id)?.let { host.createView(context, id, it) }

    fun add(provider: ComponentName) = go(life.begin(provider))

    fun remove(id: Int) = life.remove(id)

    /** Deletes host ids the layout does not list; the flow in progress keeps its own. */
    fun sweep(listed: Set<Int>) = life.sweep(listed)

    /** True when [requestCode] was one of Home's. */
    fun onResult(requestCode: Int, resultCode: Int): Boolean = when (requestCode) {
        REQ_BIND -> { go(life.bindResult(resultCode == Activity.RESULT_OK)); true }
        REQ_CONFIGURE -> { go(life.configureResult(resultCode == Activity.RESULT_OK)); true }
        else -> false
    }

    private fun go(step: Step) {
        runCatching {
            when (step) {
                is Step.AskBind -> activity.startActivityForResult(
                    Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, step.id)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, step.provider),
                    REQ_BIND,
                )
                is Step.Configure -> host.startAppWidgetConfigureActivityForResult(activity, step.id, 0, REQ_CONFIGURE, null)
                is Step.Added -> onAdded(step.id, rowRange(step.id)?.first ?: 2)
                Step.Failed -> Unit
            }
        }.onFailure { life.cancel() }
    }

    override fun allocate(): Int? = runCatching { host.allocateAppWidgetId() }.getOrNull()

    override fun delete(id: Int) { runCatching { host.deleteAppWidgetId(id) } }

    override fun liveIds(): Set<Int> = runCatching { host.appWidgetIds.toSet() }.getOrDefault(emptySet())

    override fun bind(id: Int, provider: ComponentName): Boolean =
        runCatching { manager.bindAppWidgetIdIfAllowed(id, provider) }.getOrDefault(false)

    override fun needsConfigure(id: Int): Boolean {
        val info = manager.getAppWidgetInfo(id) ?: return false
        return info.configure != null && info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL == 0
    }

    companion object {
        /** Legacy request codes must fit in 16 bits; the activity-result registry hands out the ones above. */
        const val REQ_BIND = 0x7101
        const val REQ_CONFIGURE = 0x7102
        private const val HOST_ID = 0x7100
    }
}
