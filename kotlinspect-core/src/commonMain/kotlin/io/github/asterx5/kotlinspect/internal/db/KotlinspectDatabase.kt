package io.github.asterx5.kotlinspect.internal.db

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal interface RecordDao {
    @Upsert
    suspend fun upsert(record: RecordEntity)

    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun get(id: String): RecordEntity?

    @Query("SELECT * FROM records WHERE id = :id")
    fun observe(id: String): Flow<RecordEntity?>

    @Query("SELECT * FROM records WHERE sessionId = :sessionId ORDER BY startedAt DESC")
    fun observeSession(sessionId: String): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE sessionId = :sessionId ORDER BY startedAt DESC")
    suspend fun forSession(sessionId: String): List<RecordEntity>

    @Query(
        "SELECT id, sessionId, state, startedAt, durationMs, method, url, host, path, statusCode, " +
            "requestBodySize, responseBodySize, error FROM records WHERE sessionId = :sessionId ORDER BY startedAt DESC",
    )
    fun observeSummaries(sessionId: String): Flow<List<RecordSummary>>

    @Query(
        "SELECT id, sessionId, state, startedAt, durationMs, method, url, host, path, statusCode, " +
            "requestBodySize, responseBodySize, error FROM records WHERE sessionId = :sessionId ORDER BY startedAt DESC LIMIT 1",
    )
    fun observeLatestSummary(sessionId: String): Flow<RecordSummary?>

    @Query(
        "SELECT COUNT(*) AS total, " +
            "COALESCE(SUM(state = 'Pending'), 0) AS inFlight, " +
            "COALESCE(SUM(state = 'Failed' OR COALESCE(statusCode, 0) >= 400), 0) AS errors, " +
            "COALESCE(SUM(responseBodySize), 0) AS bytes, " +
            "CAST(AVG(durationMs) AS INTEGER) AS avgMs " +
            "FROM records WHERE sessionId = :sessionId",
    )
    fun observeCounts(sessionId: String): Flow<CallCounts>

    @Query("SELECT sessionId, COUNT(*) AS calls FROM records GROUP BY sessionId")
    fun observeSessionCounts(): Flow<List<SessionCount>>

    @Query("SELECT COUNT(*) FROM records WHERE sessionId = :sessionId")
    fun observeCount(sessionId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM records WHERE sessionId = :sessionId AND state = 'Pending'")
    fun observeInFlight(sessionId: String): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM records WHERE sessionId = :sessionId " +
            "AND (state = 'Failed' OR statusCode >= 400)",
    )
    fun observeErrorCount(sessionId: String): Flow<Int>

    @Query("SELECT * FROM records WHERE sessionId = :sessionId ORDER BY startedAt DESC LIMIT 1")
    fun observeLatest(sessionId: String): Flow<RecordEntity?>

    @Query("SELECT COUNT(*) FROM records")
    suspend fun countAll(): Int

    @Query("UPDATE records SET state = 'Cancelled', error = :reason WHERE state = 'Pending'")
    suspend fun interruptPending(reason: String)

    @Query("DELETE FROM records WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM records")
    suspend fun deleteAll()

    @Query("DELETE FROM records WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<String>)

    // Retention. "Scoped" queries apply to one session; "global" queries apply to every
    // session except the ones in :excluded (which have their own override). Pending records
    // are never removed.

    @Query(
        "DELETE FROM records WHERE sessionId = :sessionId AND state != 'Pending' AND startedAt < :cutoff",
    )
    suspend fun deleteOlderThanScoped(sessionId: String, cutoff: Long)

    @Query(
        "DELETE FROM records WHERE sessionId NOT IN (:excluded) AND state != 'Pending' " +
            "AND startedAt < :cutoff",
    )
    suspend fun deleteOlderThanGlobal(excluded: List<String>, cutoff: Long)

    @Query(
        "SELECT id, storedBytes FROM records WHERE sessionId = :sessionId AND state != 'Pending' " +
            "ORDER BY startedAt DESC",
    )
    suspend fun sizesScoped(sessionId: String): List<RecordSize>

    @Query(
        "SELECT id, storedBytes FROM records WHERE sessionId NOT IN (:excluded) AND state != 'Pending' " +
            "ORDER BY startedAt DESC",
    )
    suspend fun sizesGlobal(excluded: List<String>): List<RecordSize>
}

@Dao
internal interface SessionDao {
    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observe(id: String): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE name = :name ORDER BY createdAt DESC LIMIT 1")
    suspend fun byName(name: String): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    suspend fun all(): List<SessionEntity>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM sessions WHERE id != :keepId")
    suspend fun deleteAllExcept(keepId: String)

    @Query("DELETE FROM sessions WHERE id != :keepId AND id NOT IN (SELECT DISTINCT sessionId FROM records)")
    suspend fun deleteEmptyExcept(keepId: String)

    @Query("SELECT value FROM kv WHERE `key` = :key")
    suspend fun getValue(key: String): String?

    @Upsert
    suspend fun putValue(entry: KeyValueEntity)
}

@Database(
    entities = [RecordEntity::class, SessionEntity::class, KeyValueEntity::class],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(KotlinspectDatabaseConstructor::class)
internal abstract class KotlinspectDatabase : RoomDatabase() {
    abstract fun records(): RecordDao
    abstract fun sessions(): SessionDao

    companion object {
        const val FILE_NAME: String = "kotlinspect.db"
    }
}

@Suppress("KotlinNoActualForExpect")
internal expect object KotlinspectDatabaseConstructor : RoomDatabaseConstructor<KotlinspectDatabase> {
    override fun initialize(): KotlinspectDatabase
}
