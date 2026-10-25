package io.github.asterx5.kotlinspect.internal

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteDriver
import io.github.asterx5.kotlinspect.RetentionPolicy
import io.github.asterx5.kotlinspect.internal.db.KotlinspectDatabase
import io.github.asterx5.kotlinspect.internal.db.KotlinspectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/** Where each platform keeps the Room database, and which SQLite driver it uses. */
internal expect object PlatformStorage {
    fun databaseBuilder(inMemory: Boolean, config: ResolvedConfig): RoomDatabase.Builder<KotlinspectDatabase>

    fun sqliteDriver(): SQLiteDriver
}

internal actual fun createStore(config: ResolvedConfig): KotlinspectStore =
    PlatformStorage.databaseBuilder(inMemory = config.retention == RetentionPolicy.InMemoryOnly, config = config)
        .setDriver(PlatformStorage.sqliteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
