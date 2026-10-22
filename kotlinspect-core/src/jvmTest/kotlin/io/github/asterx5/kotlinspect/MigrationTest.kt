package io.github.asterx5.kotlinspect

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.asterx5.kotlinspect.internal.db.KotlinspectDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Upgrading from a version without logs must keep existing calls and sessions. */
class MigrationTest {

    @Test
    fun version1DatabaseKeepsItsDataAfterUpgrade() = runTest {
        val file = File.createTempFile("kotlinspect-v1", ".db").apply { delete(); deleteOnExit() }
        createVersion1(file)

        val db = Room.databaseBuilder<KotlinspectDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            assertEquals(listOf("s1"), db.sessions().all().map { it.id })
            assertEquals("/kept", db.records().get("r1")!!.path)
            assertEquals(0, db.logs().countAll(), "the new logs table exists and is empty")
            assertEquals(0, db.logs().observeCount("s1").first())
        } finally {
            db.close()
        }
    }

    /** Builds a version-1 database exactly as Room would have, from the exported schema. */
    private fun createVersion1(file: File) {
        val schema = File("schemas/io.github.asterx5.kotlinspect.internal.db.KotlinspectDatabase/1.json").readText()
        val database = Json.parseToJsonElement(schema).jsonObject["database"]!!.jsonObject
        val identityHash = database["identityHash"]!!.jsonPrimitive.content
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        try {
            database["entities"]!!.jsonArray.forEach { entity ->
                val e = entity.jsonObject
                val name = e["tableName"]!!.jsonPrimitive.content
                connection.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                e["indices"]?.jsonArray?.forEach { index ->
                    connection.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                }
            }
            database["setupQueries"]!!.jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
            connection.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$identityHash')")
            connection.execSQL("INSERT INTO sessions (id, name, createdAt, tags, metadata, retention) VALUES ('s1', 'Old', 1, '[]', '{}', NULL)")
            connection.execSQL(
                "INSERT INTO records (id, sessionId, source, kind, state, startedAt, method, url, scheme, host, path, " +
                    "requestHeaders, requestBodyTruncated, responseHeaders, responseBodyTruncated, storedBytes) " +
                    "VALUES ('r1', 's1', 'http', 'http.call', 'Complete', 1, 'GET', 'https://a.com/kept', 'https', 'a.com', '/kept', " +
                    "'[]', 0, '[]', 0, 0)",
            )
            connection.execSQL("PRAGMA user_version = 1")
        } finally {
            connection.close()
        }
    }
}
