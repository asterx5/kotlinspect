package io.github.asterx5.kotlinspect

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.asterx5.kotlinspect.internal.db.KotlinspectDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Upgrading from version 2 must keep records and logs, mark Ktor records with source "ktor"
 * (they were "http") and leave the columns added in version 3 empty.
 */
class MigrationV3Test {

    @Test
    fun version2DatabaseKeepsItsDataAfterUpgrade() = runTest {
        val file = File.createTempFile("kotlinspect-v2", ".db").apply { delete(); deleteOnExit() }
        createVersion2(file)

        val db = Room.databaseBuilder<KotlinspectDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            val r = db.records().get("r1")!!
            assertEquals("/kept", r.path)
            assertEquals("ktor", r.source)
            assertNull(r.operationName)
            assertNull(r.serverIpAddress)
            assertNull(r.timingDns)
            assertNull(r.timingReceive)
            assertEquals(1, db.logs().countAll())
            assertEquals(listOf("s1"), db.sessions().all().map { it.id })
        } finally {
            db.close()
        }
    }

    /** Builds a version-2 database exactly as Room would have, from the exported schema. */
    private fun createVersion2(file: File) {
        val schema = File("schemas/io.github.asterx5.kotlinspect.internal.db.KotlinspectDatabase/2.json").readText()
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
            connection.execSQL(
                "INSERT INTO logs (sessionId, timestamp, level, tag, layer, message, throwable) " +
                    "VALUES ('s1', 1, 'Debug', 'Tag', NULL, 'kept', NULL)",
            )
            connection.execSQL("PRAGMA user_version = 2")
        } finally {
            connection.close()
        }
    }
}
