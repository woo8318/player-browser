package com.playerbrowser.app.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class BrowserRepository private constructor(
    private val bookmarkDao: BookmarkDao,
    private val historyDao: HistoryDao
) {
    fun bookmarks(): Flow<List<Bookmark>> = bookmarkDao.observeAll()
    fun history(): Flow<List<HistoryEntry>> = historyDao.observeAll()
    fun visitedUrls(): Flow<List<String>> = historyDao.observeVisitedUrls()
    fun isBookmarked(url: String): Flow<Boolean> = bookmarkDao.observeIsBookmarked(url)

    suspend fun addBookmark(url: String, title: String) =
        bookmarkDao.insert(Bookmark(url = url, title = title, createdAt = System.currentTimeMillis()))

    suspend fun removeBookmark(url: String) = bookmarkDao.deleteByUrl(url)

    suspend fun recordVisit(url: String, title: String) =
        historyDao.upsert(url, title, System.currentTimeMillis())

    suspend fun removeHistory(url: String) = historyDao.deleteByUrl(url)
    suspend fun clearHistory() = historyDao.clear()

    companion object {
        @Volatile private var instance: BrowserRepository? = null
        fun get(context: Context): BrowserRepository =
            instance ?: synchronized(this) {
                instance ?: run {
                    val db = AppDatabase.get(context)
                    BrowserRepository(db.bookmarkDao(), db.historyDao()).also { instance = it }
                }
            }
    }
}
