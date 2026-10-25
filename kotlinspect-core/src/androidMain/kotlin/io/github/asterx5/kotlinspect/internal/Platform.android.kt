package io.github.asterx5.kotlinspect.internal

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import io.github.asterx5.kotlinspect.InternalKotlinspectApi
import java.util.ServiceLoader

internal actual object Platform {
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var appContext: Context? = null

    /** UI modules on the classpath, such as the Compose artifact. Empty for core alone. */
    private val providers: List<KotlinspectUiProvider> by lazy {
        runCatching {
            ServiceLoader.load(KotlinspectUiProvider::class.java, KotlinspectUiProvider::class.java.classLoader).toList()
        }.getOrDefault(emptyList())
    }

    actual val isReady: Boolean
        get() = appContext != null

    /** The application context, once captured at startup. */
    val context: Context?
        get() = appContext

    actual val isDebugBuild: Boolean
        get() = appContext?.let { (it.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 } ?: false

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        KotlinspectAndroid.application = context.applicationContext as? Application
        providers.forEach { runCatching { it.install() } }
    }

    actual fun onRuntimeStarted(host: KotlinspectHost) {
        providers.forEach { runCatching { it.onStarted(host) } }
    }

    // The Compose overlay reads the config live; nothing to install or remove.
    actual fun onConfigChanged(host: KotlinspectHost) = Unit

    actual fun openInspector(host: KotlinspectHost, sessionId: String?) {
        providers.firstOrNull()?.let { runCatching { it.openInspector(host, sessionId) } }
    }

    actual fun shareFile(path: String) {
        appContext?.let { runCatching { KotlinspectFileProvider.share(it, java.io.File(path)) } }
    }
}

/** The application, for UI modules that attach to activities. */
@InternalKotlinspectApi
public object KotlinspectAndroid {
    @Volatile
    public var application: Application? = null
        internal set
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
