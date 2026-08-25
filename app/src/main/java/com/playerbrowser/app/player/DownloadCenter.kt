package com.playerbrowser.app.player

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.playerbrowser.app.network.DebugLog
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Offline video downloads — the answer to "playback keeps stalling on a weak
 * connection". A sniffed stream is fetched to app-private storage ahead of time;
 * [VideoPlayerActivity] then reads it back out of the same cache, so a downloaded
 * video plays with the network out of the loop entirely: no rebuffering, no CDN
 * throttling halfway through.
 *
 * Media3's [DownloadManager] does the work rather than a plain file download,
 * because the streams we sniff are frequently **HLS** (`.m3u8`) — a playlist plus
 * hundreds of segments, which "save the file" cannot express. The download store
 * and the playback cache are one [SimpleCache], so a finished download is a cache
 * hit for the exact URIs playback later asks for.
 *
 * Per-download headers: these CDNs check Referer / Cookie, but a [DownloadManager]
 * has a single global [DataSource.Factory] and for HLS it only ever sees *segment*
 * URIs, never the playlist we started from. So every request is rewritten through
 * a [ResolvingDataSource] that re-attaches the page context, looked up **by host**
 * (segments live on the host the playlist named) with the Cookie read live from
 * [CookieManager] so a refreshed session is picked up instead of going stale
 * mid-download.
 */
@UnstableApi
object DownloadCenter {

    private const val TAG = "Download"
    private const val PREFS_NAME = "video_downloads"
    private const val KEY_HEADERS = "headers"
    private const val CACHE_DIR = "video-downloads"
    private const val MAX_PARALLEL = 2
    private const val MAX_PENDING = 16

    /** [DownloadRequest.data] payload, so the list can show something human. */
    private const val META_TITLE = "title"
    private const val META_PAGE = "page"

    /** [Download.stopReason] we set when the user pauses (0 means "not stopped"). */
    const val STOP_REASON_USER = 1

    private var cacheRef: SimpleCache? = null
    private var managerRef: DownloadManager? = null
    private var databaseRef: DatabaseProvider? = null

    // host -> {Referer, User-Agent}. SharedPreferences rather than Room for the
    // same reason as TabPersistence / WatchProgressStore: a Room version bump
    // runs a destructive migration that would wipe bookmarks and history.
    private val headerCache = HashMap<String, Map<String, String>>()
    private var headersLoaded = false

    // Requests handed to the service but not yet visible in its index. Adding a
    // download is an intent round-trip, so "download and watch now" would launch
    // the player a beat before the index knows the request exists - and an HLS
    // stream started without the download's stream keys picks its own rendition,
    // which is exactly the cache miss this whole path exists to avoid.
    private val pendingRequests = HashMap<String, DownloadRequest>()

    // ---- singletons -------------------------------------------------------

    @Synchronized
    private fun database(context: Context): DatabaseProvider = databaseRef ?: run {
        val created = StandaloneDatabaseProvider(context.applicationContext)
        databaseRef = created
        created
    }

    @Synchronized
    fun cache(context: Context): Cache = cacheRef ?: run {
        val ctx = context.applicationContext
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, CACHE_DIR)
        // NoOpCacheEvictor is mandatory here: an evicting cache would silently
        // delete finished downloads to make room, which is the one thing a
        // download must never do.
        val created = SimpleCache(dir, NoOpCacheEvictor(), database(ctx))
        cacheRef = created
        created
    }

    @Synchronized
    fun manager(context: Context): DownloadManager = managerRef ?: run {
        val ctx = context.applicationContext
        val created = DownloadManager(
            ctx,
            database(ctx),
            cache(ctx),
            downloadDataSourceFactory(ctx),
            Executors.newFixedThreadPool(3)
        )
        created.maxParallelDownloads = MAX_PARALLEL
        managerRef = created
        created
    }

    // ---- data sources -----------------------------------------------------

    /** Network factory for the downloader, with page headers re-attached per request. */
    private fun downloadDataSourceFactory(context: Context): DataSource.Factory {
        val ctx = context.applicationContext
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        val resolver = ResolvingDataSource.Resolver { dataSpec ->
            val extra = headersFor(ctx, dataSpec.uri)
            if (extra.isEmpty()) dataSpec else dataSpec.withAdditionalHeaders(extra)
        }
        return ResolvingDataSource.Factory(http, resolver)
    }

    /**
     * Read-only view of the download cache, for playback. Write-back is off on
     * purpose: this cache has no evictor, so letting ordinary streaming fill it
     * would grow it without bound and blur the line between "downloaded" and
     * "happened to be cached".
     */
    fun playbackDataSourceFactory(
        context: Context,
        upstream: DataSource.Factory
    ): DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache(context))
        .setUpstreamDataSourceFactory(upstream)
        .setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    // ---- headers ----------------------------------------------------------

    @Synchronized
    private fun rememberHeaders(context: Context, url: String, referer: String?, ua: String?) {
        val host = hostOf(url) ?: return
        loadHeaders(context)
        val entry = HashMap<String, String>()
        referer?.takeIf { it.isNotBlank() }?.let { entry["Referer"] = it }
        ua?.takeIf { it.isNotBlank() }?.let { entry["User-Agent"] = it }
        if (entry.isEmpty()) return
        headerCache[host] = entry
        persistHeaders(context)
    }

    /**
     * Referer / UA for this host, falling back to the most recent entry we know
     * (a playlist can name segments on a sibling CDN host), plus the live cookie
     * for this exact URL. Never throws: a header lookup must not kill a download.
     */
    @Synchronized
    private fun headersFor(context: Context, uri: Uri): Map<String, String> {
        loadHeaders(context)
        val base = headerCache[uri.host] ?: headerCache.values.lastOrNull() ?: emptyMap()
        val cookie = runCatching {
            CookieManager.getInstance().getCookie(uri.toString())
        }.getOrNull()
        return if (cookie.isNullOrBlank()) base else base + ("Cookie" to cookie)
    }

    private fun loadHeaders(context: Context) {
        if (headersLoaded) return
        headersLoaded = true
        val raw = runCatching { prefs(context).getString(KEY_HEADERS, null) }.getOrNull() ?: return
        runCatching {
            val root = JSONObject(raw)
            val hosts = root.keys()
            while (hosts.hasNext()) {
                val host = hosts.next()
                val obj = root.optJSONObject(host) ?: continue
                val entry = HashMap<String, String>()
                val names = obj.keys()
                while (names.hasNext()) {
                    val name = names.next()
                    entry[name] = obj.optString(name)
                }
                if (entry.isNotEmpty()) headerCache[host] = entry
            }
        }.onFailure { DebugLog.e(TAG, "헤더 로드 실패: ${it.message}") }
    }

    private fun persistHeaders(context: Context) {
        runCatching {
            val root = JSONObject()
            headerCache.forEach { (host, entry) ->
                val obj = JSONObject()
                entry.forEach { (name, value) -> obj.put(name, value) }
                root.put(host, obj)
            }
            prefs(context).edit().putString(KEY_HEADERS, root.toString()).apply()
        }.onFailure { DebugLog.e(TAG, "헤더 저장 실패: ${it.message}") }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- enqueue ----------------------------------------------------------

    /**
     * Queue [url] for offline download. HLS goes through [DownloadHelper] so one
     * device-appropriate rendition is picked instead of every bitrate in the
     * master playlist; a progressive file is enqueued directly, since there is
     * nothing to select and preparing it would re-read the container for nothing.
     *
     * [onResult] reports the user-facing outcome on the caller's looper.
     */
    fun enqueue(
        context: Context,
        url: String,
        mime: String?,
        title: String?,
        pageUrl: String?,
        userAgent: String?,
        onResult: (ok: Boolean, message: String) -> Unit
    ) {
        val ctx = context.applicationContext
        rememberHeaders(ctx, url, pageUrl, userAgent)

        val existing = runCatching { manager(ctx).downloadIndex.getDownload(url) }.getOrNull()
        if (existing != null && existing.state != Download.STATE_FAILED) {
            onResult(false, "이미 다운로드 목록에 있어요")
            return
        }

        val data = JSONObject().apply {
            put(META_TITLE, title.orEmpty())
            put(META_PAGE, pageUrl.orEmpty())
        }.toString().toByteArray()

        val resolvedMime = mimeTypeFor(mime, url)
        if (resolvedMime == MimeTypes.APPLICATION_M3U8) {
            val item = MediaItem.Builder()
                .setUri(url)
                .setMimeType(resolvedMime)
                .build()
            enqueueViaHelper(ctx, item, url, data, onResult)
        } else {
            val builder = DownloadRequest.Builder(url, Uri.parse(url)).setData(data)
            if (resolvedMime != null) builder.setMimeType(resolvedMime)
            send(ctx, builder.build(), onResult)
        }
    }

    private fun enqueueViaHelper(
        context: Context,
        item: MediaItem,
        url: String,
        data: ByteArray,
        onResult: (Boolean, String) -> Unit
    ) {
        val helper = runCatching {
            DownloadHelper.forMediaItem(
                context,
                item,
                DefaultRenderersFactory(context),
                downloadDataSourceFactory(context)
            )
        }.getOrElse {
            DebugLog.e(TAG, "HLS 준비 실패: ${it.message}")
            onResult(false, "다운로드를 시작하지 못했어요")
            return
        }
        helper.prepare(object : DownloadHelper.Callback {
            override fun onPrepared(h: DownloadHelper) {
                val request = runCatching { h.getDownloadRequest(url, data) }.getOrNull()
                h.release()
                if (request == null) {
                    onResult(false, "다운로드할 화질을 고르지 못했어요")
                } else {
                    send(context, request, onResult)
                }
            }

            override fun onPrepareError(h: DownloadHelper, e: IOException) {
                h.release()
                DebugLog.e(TAG, "HLS 재생목록 읽기 실패: ${e.message}")
                // A playlist we cannot read offline is usually token-protected.
                // Say so rather than failing silently.
                onResult(false, "재생목록을 읽지 못했어요 (보호된 스트림일 수 있어요)")
            }
        })
    }

    private fun send(
        context: Context,
        request: DownloadRequest,
        onResult: (Boolean, String) -> Unit
    ) {
        runCatching {
            DownloadService.sendAddDownload(
                context, VideoDownloadService::class.java, request, /* foreground = */ true
            )
        }.onSuccess {
            rememberPending(request)
            DebugLog.d(TAG, "다운로드 시작: ${request.uri}")
            onResult(true, "다운로드를 시작했어요")
        }.onFailure {
            DebugLog.e(TAG, "다운로드 시작 실패: ${it.message}")
            onResult(false, "다운로드를 시작하지 못했어요")
        }
    }

    // ---- queries / controls -----------------------------------------------

    /** Every known download, newest first. An unreadable index yields an empty list, never a crash. */
    fun downloads(context: Context): List<DownloadEntry> = runCatching {
        val out = ArrayList<DownloadEntry>()
        manager(context).downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) out.add(toEntry(cursor.download))
        }
        out.sortedByDescending { it.updatedAt }
    }.getOrElse {
        DebugLog.e(TAG, "다운로드 목록 조회 실패: ${it.message}")
        emptyList()
    }

    /** True when this URL is fully downloaded — playback can run with the radio off. */
    fun isDownloaded(context: Context, url: String): Boolean = runCatching {
        manager(context).downloadIndex.getDownload(url)?.state == Download.STATE_COMPLETED
    }.getOrDefault(false)

    /**
     * The [MediaItem] to play [url] with, when a download exists for it.
     *
     * This matters most while a download is still running. The request carries the
     * stream keys [DownloadHelper] chose, so an HLS stream plays back the exact
     * rendition being downloaded; without them adaptive selection wanders onto a
     * different bitrate whose segments were never cached, and every byte already
     * on disk is ignored while the same video is pulled twice over the network.
     * Null when nothing was ever queued for this URL — the caller then builds its
     * own item as before.
     */
    fun mediaItemFor(context: Context, url: String): MediaItem? {
        val indexed = runCatching {
            manager(context).downloadIndex.getDownload(url)?.request
        }.getOrNull()
        if (indexed != null) {
            takePending(url)
            return runCatching { indexed.toMediaItem() }.getOrNull()
        }
        return runCatching { peekPending(url)?.toMediaItem() }.getOrNull()
    }

    @Synchronized
    private fun rememberPending(request: DownloadRequest) {
        // Bounded so a long session cannot accumulate requests the index never
        // caught up with; entries are superseded the moment the index has them.
        if (pendingRequests.size >= MAX_PENDING) pendingRequests.clear()
        pendingRequests[request.id] = request
    }

    @Synchronized
    private fun peekPending(url: String): DownloadRequest? = pendingRequests[url]

    @Synchronized
    private fun takePending(url: String) {
        pendingRequests.remove(url)
    }

    /**
     * Referer / User-Agent remembered when this URL was queued. The downloads list
     * plays entries long after the originating tab is gone, and these CDNs still
     * check both for whatever part isn't on disk yet. Cookie is deliberately not
     * included — the player reads that live, so a refreshed session still works.
     */
    @Synchronized
    fun rememberedHeaders(context: Context, url: String): Map<String, String> {
        loadHeaders(context)
        return headerCache[hostOf(url)] ?: headerCache.values.lastOrNull() ?: emptyMap()
    }

    fun remove(context: Context, id: String) {
        runCatching {
            DownloadService.sendRemoveDownload(
                context, VideoDownloadService::class.java, id, /* foreground = */ true
            )
        }.onFailure { DebugLog.e(TAG, "다운로드 삭제 실패: ${it.message}") }
    }

    fun setPaused(context: Context, id: String, paused: Boolean) {
        runCatching {
            DownloadService.sendSetStopReason(
                context,
                VideoDownloadService::class.java,
                id,
                if (paused) STOP_REASON_USER else Download.STOP_REASON_NONE,
                /* foreground = */ true
            )
        }.onFailure { DebugLog.e(TAG, "일시정지 전환 실패: ${it.message}") }
    }

    /** Retry a failed download by re-sending its original request. */
    fun retry(context: Context, id: String) {
        val request = runCatching {
            manager(context).downloadIndex.getDownload(id)?.request
        }.getOrNull() ?: return
        send(context, request) { _, _ -> }
    }

    /** Bytes this cache currently occupies on disk. */
    fun usedBytes(context: Context): Long =
        runCatching { cache(context).cacheSpace }.getOrDefault(0L)

    fun mimeTypeFor(mime: String?, url: String): String? {
        val m = mime?.lowercase().orEmpty()
        val u = url.lowercase().substringBefore('?').substringBefore('#')
        return when {
            m.contains("mpegurl") || u.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            m.contains("mp4") || u.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
            m.contains("webm") || u.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
            else -> null
        }
    }

    private fun hostOf(url: String): String? =
        runCatching { Uri.parse(url).host }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Flatten Media3's [Download] so no unstable type leaks into the UI layer. */
    private fun toEntry(d: Download): DownloadEntry {
        val meta = runCatching { JSONObject(String(d.request.data)) }.getOrNull()
        val url = d.request.uri.toString()
        val status = when (d.state) {
            Download.STATE_COMPLETED -> DownloadStatus.COMPLETED
            Download.STATE_FAILED -> DownloadStatus.FAILED
            Download.STATE_STOPPED -> DownloadStatus.PAUSED
            Download.STATE_DOWNLOADING -> DownloadStatus.DOWNLOADING
            Download.STATE_REMOVING -> DownloadStatus.REMOVING
            else -> DownloadStatus.QUEUED
        }
        return DownloadEntry(
            id = d.request.id,
            url = url,
            title = meta?.optString(META_TITLE).orEmpty().ifBlank { fallbackTitle(url) },
            pageUrl = meta?.optString(META_PAGE).orEmpty(),
            status = status,
            // percentDownloaded is NaN until the total length is known.
            percent = if (d.percentDownloaded.isFinite()) d.percentDownloaded else 0f,
            bytesDownloaded = d.bytesDownloaded,
            contentLength = d.contentLength,
            updatedAt = d.updateTimeMs
        )
    }

    private fun fallbackTitle(url: String): String =
        runCatching { Uri.parse(url).lastPathSegment }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: url.substringBefore('?').substringAfterLast('/').ifBlank { "영상" }
}

enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, REMOVING }

/** UI-facing snapshot of one download. Deliberately free of Media3 types. */
data class DownloadEntry(
    val id: String,
    val url: String,
    val title: String,
    val pageUrl: String,
    val status: DownloadStatus,
    val percent: Float,
    val bytesDownloaded: Long,
    val contentLength: Long,
    val updatedAt: Long
)
