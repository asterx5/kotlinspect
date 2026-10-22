package io.github.asterx5.kotlinspect.internal.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.asterx5.kotlinspect.Kotlinspect
import io.github.asterx5.kotlinspect.internal.KotlinspectAndroid
import io.github.asterx5.kotlinspect.internal.KotlinspectHost
import io.github.asterx5.kotlinspect.internal.KotlinspectUiProvider
import io.github.asterx5.kotlinspect.internal.uimodel.ToastController

/** Compose UI for Android, found by kotlinspect-core through ServiceLoader. */
internal class AndroidComposeUi : KotlinspectUiProvider {
    override fun install() {
        // The callbacks only check Kotlinspect.isEnabled; in release builds they do nothing else.
        KotlinspectAndroid.application?.registerActivityLifecycleCallbacks(OverlayAttacher)
    }

    override fun onStarted(host: KotlinspectHost) {
        OverlayAttacher.start(host)
    }

    override fun openInspector(host: KotlinspectHost, sessionId: String?) {
        val context = OverlayAttacher.topActivity ?: KotlinspectAndroid.application ?: return
        val intent = Intent(context, KotlinspectActivity::class.java)
        sessionId?.let { intent.putExtra(KotlinspectActivity.EXTRA_SESSION, it) }
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/** Adds the automatic overlay as a top-most child of each started activity's decor view. */
private object OverlayAttacher : Application.ActivityLifecycleCallbacks {
    private const val TAG_KEY = 0x6b737074 // "kspt"
    private val started = LinkedHashSet<Activity>()

    @Volatile
    private var toasts: ToastController? = null

    var topActivity: Activity? = null
        private set

    fun start(host: KotlinspectHost) {
        if (toasts == null) toasts = host.toasts(null)
        // If capture started from a background thread, the overlay attaches on the next activity start.
        synchronized(started) { started.toList() }.forEach { activity -> activity.runOnUiThread { attach(activity) } }
    }

    private fun attach(activity: Activity) {
        if (activity is KotlinspectActivity || activity !is ComponentActivity) return
        if (activity.isFinishing || activity.isDestroyed) return
        // Starting capture here (rather than in the provider) means Application.onCreate has run,
        // so configure() calls there are honored.
        val host = Kotlinspect.host() ?: return
        val toasts = toasts ?: return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (decor.findViewWithTag<ComposeView>(TAG_KEY) != null) return
        val view = ComposeView(activity).apply {
            tag = TAG_KEY
            setViewTreeLifecycleOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setContent { FullScreenOverlay(host, toasts, onOpenInspector = { Kotlinspect.openInspector() }) }
        }
        decor.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onActivityStarted(activity: Activity) {
        synchronized(started) { started += activity }
        attach(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        topActivity = activity
        // The decor view keeps the overlay on top only if nothing was added after it.
        val decor = activity.window?.decorView as? ViewGroup ?: return
        decor.findViewWithTag<ComposeView>(TAG_KEY)?.bringToFront()
    }

    override fun onActivityPaused(activity: Activity) {
        if (topActivity === activity) topActivity = null
    }

    override fun onActivityStopped(activity: Activity) {
        synchronized(started) { started -= activity }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        synchronized(started) { started -= activity }
    }
}

/** Hosts the inspector screen. */
internal class KotlinspectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val host = Kotlinspect.host()
        if (host == null) {
            finish()
            return
        }
        val session = intent.getStringExtra(EXTRA_SESSION)
        setContent { InspectorApp(host, initialSessionId = session, onClose = ::finish) }
    }

    companion object {
        const val EXTRA_SESSION = "io.github.asterx5.kotlinspect.session"
    }
}
