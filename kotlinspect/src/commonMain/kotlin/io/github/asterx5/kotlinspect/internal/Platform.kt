package io.github.asterx5.kotlinspect.internal

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteDriver
import io.github.asterx5.kotlinspect.RetentionPolicy
import io.github.asterx5.kotlinspect.internal.db.KotlinspectDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/** Per-platform integration: build type, storage location and UI hosting. */
internal expect object Platform {
    /** False until the platform has what it needs, such as the Android application context. */
    val isReady: Boolean

    /** Debuggable APK on Android, debug framework on iOS, not a packaged app on desktop. */
    val isDebugBuild: Boolean

    fun databaseBuilder(inMemory: Boolean, config: ResolvedConfig): RoomDatabase.Builder<KotlinspectDatabase>

    fun sqliteDriver(): SQLiteDriver

    /** Shows the bubble and toast. Called once, when capture starts. */
    fun onRuntimeStarted(runtime: KotlinspectRuntime)

    fun openInspector(runtime: KotlinspectRuntime)
}

internal fun Platform.createDatabase(config: ResolvedConfig): KotlinspectDatabase =
    databaseBuilder(inMemory = config.retention == RetentionPolicy.InMemoryOnly, config = config)
        .setDriver(sqliteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
