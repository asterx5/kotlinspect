package io.github.asterx5.kotlinspect.internal

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.asterx5.kotlinspect.internal.db.KotlinspectDatabase
import java.io.File

internal actual object PlatformStorage {
    actual fun databaseBuilder(inMemory: Boolean, config: ResolvedConfig): RoomDatabase.Builder<KotlinspectDatabase> {
        if (inMemory) return Room.inMemoryDatabaseBuilder<KotlinspectDatabase>()
        val dir = File(System.getProperty("user.home"), ".kotlinspect/${config.desktopAppName ?: defaultAppName()}")
        dir.mkdirs()
        return Room.databaseBuilder<KotlinspectDatabase>(File(dir, KotlinspectDatabase.FILE_NAME).absolutePath)
    }

    private fun defaultAppName(): String {
        val command = System.getProperty("sun.java.command")?.substringBefore(' ').orEmpty()
        val name = command.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".jar")
        return name.ifBlank { "default" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    actual fun sqliteDriver(): SQLiteDriver = BundledSQLiteDriver()
}
