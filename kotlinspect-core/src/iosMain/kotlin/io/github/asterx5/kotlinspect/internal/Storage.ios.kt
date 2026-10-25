@file:OptIn(ExperimentalForeignApi::class)

package io.github.asterx5.kotlinspect.internal

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.asterx5.kotlinspect.internal.db.KotlinspectDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

internal actual object PlatformStorage {
    actual fun databaseBuilder(inMemory: Boolean, config: ResolvedConfig): RoomDatabase.Builder<KotlinspectDatabase> =
        if (inMemory) {
            Room.inMemoryDatabaseBuilder<KotlinspectDatabase>()
        } else {
            Room.databaseBuilder<KotlinspectDatabase>(name = databasePath())
        }

    private fun databasePath(): String {
        val manager = NSFileManager.defaultManager
        val base = manager.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)?.path
            ?: error("Application Support directory unavailable")
        val dir = "$base/Kotlinspect"
        manager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        return "$dir/${KotlinspectDatabase.FILE_NAME}"
    }

    actual fun sqliteDriver(): SQLiteDriver = BundledSQLiteDriver()
}
