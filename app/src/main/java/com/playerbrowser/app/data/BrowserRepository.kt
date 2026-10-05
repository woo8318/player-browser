package com.playerbrowser.app.data

import android.content.Context
import com.playerbrowser.app.web.VisitedLinkKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class BrowserRepository private constructor(
    private val bookmarkDao: BookmarkDao,
    private val historyDao: HistoryDao
) {
    fun bookmarks(): Flow<List<Bookmark>> = bookmarkDao.observeAll()
    fun history(): Flow<List<HistoryEntry>> = historyDao.observeAll()
    fun visitedUrls(): Flow<List<String>> = historyDao.observeVisitedUrls()
    fun visitedUrlsByRecency(): Flow<List<String>> = historyDao.observeUrlsByRecency()
    fun isBookmarked(url: String): Flow<Boolean> = bookmarkDao.observeIsBookmarked(url)

    suspend fun addBookmark(url: String, title: String) =
        bookmarkDao.insert(Bookmark(url = url, title = title, createdAt = System.currentTimeMillis()))

    suspend fun removeBookmark(url: String) = bookmarkDao.deleteByUrl(url)

    suspend fun recordVisit(url: String, title: String) =
        historyDao.upsert(url, title, System.currentTimeMillis())

    suspend fun removeHistory(url: String) = historyDao.deleteByUrl(url)
    suspend fun clearHistory() = historyDao.clear()

    /**
     * Link menu → "방문 표시 지우기" (v1.3.109): forget every visit of the page
     * [url] names. The visited mark is keyed domain-number-agnostically, so the
     * same article under a sibling mirror (newtoki123 / newtoki124, http/https,
     * trailing slash) has to go too or the mark stays. Returns rows removed.
     */
    suspend fun forgetVisited(url: String): Int = withContext(Dispatchers.Default) {
        val key = VisitedLinkKeys.pageKey(url) ?: return@withContext 0
        val matches = historyDao.allUrls().filter { VisitedLinkKeys.pageKey(it) == key }
        matches.chunked(500).forEach { historyDao.deleteByUrls(it) }
        matches.size
    }

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
