package io.github.asterx5.kotlinspect.internal

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.AndroidSQLiteDriver
import io.github.asterx5.kotlinspect.Kotlinspect
import io.github.asterx5.kotlinspect.internal.db.KotlinspectDatabase
import io.github.asterx5.kotlinspect.internal.ui.FullScreenOverlay
import io.github.asterx5.kotlinspect.internal.ui.InspectorApp
import io.github.asterx5.kotlinspect.internal.ui.ToastController

internal actual object Platform {
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var toasts: ToastController? = null

    actual val isReady: Boolean
        get() = appContext != null

    actual val isDebugBuild: Boolean
        get() = appContext?.let { (it.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 } ?: false

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        // The callbacks only check Kotlinspect.isEnabled; in release builds they do nothing else.
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(OverlayAttacher)
    }

    actual fun databaseBuilder(inMemory: Boolean, config: ResolvedConfig): RoomDatabase.Builder<KotlinspectDatabase> {
        val context = requireNotNull(appContext)
        return if (inMemory) {
            Room.inMemoryDatabaseBuilder<KotlinspectDatabase>(context)
        } else {
            Room.databaseBuilder<KotlinspectDatabase>(context, context.getDatabasePath(KotlinspectDatabase.FILE_NAME).absolutePath)
        }
    }

    actual fun sqliteDriver(): SQLiteDriver = AndroidSQLiteDriver()

    actual fun onRuntimeStarted(runtime: KotlinspectRuntime) {
        toasts = ToastController(runtime)
        // If capture started from a background thread, the overlay attaches on the next activity start.
        OverlayAttacher.attachToStarted()
    }

    actual fun openInspector(runtime: KotlinspectRuntime) {
        val context = OverlayAttacher.topActivity ?: appContext ?: return
        val intent = Intent(context, KotlinspectActivity::class.java)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    internal fun toastController(): ToastController? = toasts
}

/** Adds the overlay as a top-most child of each started activity's decor view. */
private object OverlayAttacher : Application.ActivityLifecycleCallbacks {
    private const val TAG_KEY = 0x6b737074 // "kspt"
    private val started = LinkedHashSet<Activity>()
    var topActivity: Activity? = null
        private set

    fun attachToStarted() {
        synchronized(started) { started.toList() }.forEach { activity -> activity.runOnUiThread { attach(activity) } }
    }

    private fun attach(activity: Activity) {
        if (activity is KotlinspectActivity || activity !is ComponentActivity) return
        if (activity.isFinishing || activity.isDestroyed) return
        // Starting capture here (rather than in the provider) means Application.onCreate has run,
        // so configure() calls there are honored.
        val runtime = Kotlinspect.runtimeOrNull() ?: return
        val toasts = Platform.toastController() ?: return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (decor.findViewWithTag<ComposeView>(TAG_KEY) != null) return
        val view = ComposeView(activity).apply {
            tag = TAG_KEY
            setViewTreeLifecycleOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setContent { FullScreenOverlay(runtime, toasts, onOpenInspector = { Platform.openInspector(runtime) }) }
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

/** Captures the application context at startup. Merged into the host app's manifest. */
internal class KotlinspectInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let(Platform::init)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/** Hosts the inspector screen. */
internal class KotlinspectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val runtime = Kotlinspect.runtimeOrNull()
        if (runtime == null) {
            finish()
            return
        }
        setContent { InspectorApp(runtime, onClose = ::finish) }
    }
}
