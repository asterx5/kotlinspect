package io.github.asterx5.kotlinspect.internal

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.AndroidSQLiteDriver
import io.github.asterx5.kotlinspect.internal.db.KotlinspectDatabase

internal actual object PlatformStorage {
    actual fun databaseBuilder(inMemory: Boolean, config: ResolvedConfig): RoomDatabase.Builder<KotlinspectDatabase> {
        val context = requireNotNull(Platform.context)
        return if (inMemory) {
            Room.inMemoryDatabaseBuilder<KotlinspectDatabase>(context)
        } else {
            Room.databaseBuilder<KotlinspectDatabase>(context, context.getDatabasePath(KotlinspectDatabase.FILE_NAME).absolutePath)
        }
    }

    actual fun sqliteDriver(): SQLiteDriver = AndroidSQLiteDriver()
}
