package com.androidy.voicereader.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "reading_history")
data class ReadingHistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val sourceApp: String = "",
    val rawTextPreview: String = "",
    val segmentsJson: String = "", // JSON-encoded list of segments
    val segmentCount: Int = 0,
    val commandType: String = "READ"
)

@Dao
interface ReadingHistoryDao {
    @Query("SELECT * FROM reading_history ORDER BY timestamp DESC")
    fun getAll(): Flow<List<ReadingHistoryEntry>>

    @Query("SELECT * FROM reading_history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int): Flow<List<ReadingHistoryEntry>>

    @Query("SELECT * FROM reading_history WHERE id = :id")
    suspend fun getById(id: Long): ReadingHistoryEntry?

    @Insert
    suspend fun insert(entry: ReadingHistoryEntry): Long

    @Delete
    suspend fun delete(entry: ReadingHistoryEntry)

    @Query("DELETE FROM reading_history")
    suspend fun deleteAll()
}

@Database(entities = [ReadingHistoryEntry::class], version = 1, exportSchema = false)
abstract class ReadingHistoryDatabase : RoomDatabase() {
    abstract fun readingHistoryDao(): ReadingHistoryDao
}
