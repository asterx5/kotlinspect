package io.github.asterx5.kotlinspect.internal.db

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [RecordEntity::class, SessionEntity::class, KeyValueEntity::class, LogEntity::class],
    version = 3,
    // Version 2 added the logs table. Version 3 added operation names, server addresses and
    // per-phase timings, and renamed the Ktor source from "http" to "ktor". Both keep existing data.
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3, spec = KotlinspectDatabase.Migration2To3::class),
    ],
    exportSchema = true,
)
@ConstructedBy(KotlinspectDatabaseConstructor::class)
internal abstract class KotlinspectDatabase : RoomDatabase(), KotlinspectStore {
    abstract override fun records(): RecordDao
    abstract override fun sessions(): SessionDao
    abstract override fun logs(): LogDao

    companion object {
        const val FILE_NAME: String = "kotlinspect.db"
    }

    /** Ktor records were stored with source "http" before version 3. */
    class Migration2To3 : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
            connection.execSQL("UPDATE records SET source = 'ktor' WHERE source = 'http'")
        }
    }
}

@Suppress("KotlinNoActualForExpect")
internal expect object KotlinspectDatabaseConstructor : RoomDatabaseConstructor<KotlinspectDatabase> {
    override fun initialize(): KotlinspectDatabase
}
