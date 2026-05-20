package com.playerbrowser.app.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY lastVisitedAt DESC LIMIT 500")
    fun observeAll(): Flow<List<HistoryEntry>>

    @Query("SELECT url FROM history")
    fun observeVisitedUrls(): Flow<List<String>>

    @Query("""
        INSERT INTO history(url, title, lastVisitedAt, visitCount)
        VALUES(:url, :title, :now, 1)
        ON CONFLICT(url) DO UPDATE SET
            title = excluded.title,
            lastVisitedAt = excluded.lastVisitedAt,
            visitCount = history.visitCount + 1
    """)
    suspend fun upsert(url: String, title: String, now: Long)

    @Query("DELETE FROM history WHERE url = :url")
    suspend fun deleteByUrl(url: String)

    @Query("DELETE FROM history")
    suspend fun clear()
}
