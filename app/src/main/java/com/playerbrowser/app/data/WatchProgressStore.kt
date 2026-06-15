package com.playerbrowser.app.data

import android.content.Context
import org.json.JSONObject

/**
 * "이어보기" — per-page video playback position, persisted as a single JSON map
 * in SharedPreferences (same approach as [TabPersistence]: keeps it out of the
 * Room DB so adding it can't trigger a destructive migration of bookmarks /
 * history). Keyed by the page URL (fragment stripped) so revisiting an episode
 * resumes where the user left off.
 *
 * Entries are pruned to [MAX_ENTRIES] newest-by-timestamp. A video that reached
 * (near) the end is dropped instead of stored, so a finished video never resumes
 * from its tail.
 */
data class WatchProgress(
    val url: String,
    val positionSec: Double,
    val durationSec: Double,
    val title: String,
    val updatedAt: Long
)

class WatchProgressStore private constructor(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun save(url: String, positionSec: Double, durationSec: Double, title: String) {
        val key = normalize(url) ?: return
        val root = read()
        val finished = durationSec.isFinite() && durationSec > 0 &&
            positionSec >= durationSec - END_GUARD_SEC
        if (!positionSec.isFinite() || positionSec < MIN_POSITION_SEC || finished) {
            // Too early to be meaningful, or effectively finished → forget it so
            // it doesn't show up as resumable.
            if (root.has(key)) {
                root.remove(key)
                write(root)
            }
            return
        }
        root.put(
            key,
            JSONObject()
                .put("url", key)
                .put("pos", positionSec)
                .put("dur", if (durationSec.isFinite()) durationSec else 0.0)
                .put("title", title)
                .put("at", System.currentTimeMillis())
        )
        prune(root)
        write(root)
    }

    /** Saved position in seconds for [url], or -1 if none. */
    @Synchronized
    fun position(url: String): Double {
        val key = normalize(url) ?: return -1.0
        val obj = read().optJSONObject(key) ?: return -1.0
        return obj.optDouble("pos", -1.0)
    }

    @Synchronized
    fun delete(url: String) {
        val key = normalize(url) ?: return
        val root = read()
        if (root.has(key)) {
            root.remove(key)
            write(root)
        }
    }

    /** All entries, newest first — for a future "이어보기 목록" screen. */
    @Synchronized
    fun all(): List<WatchProgress> {
        val root = read()
        val out = ArrayList<WatchProgress>(root.length())
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val o = root.optJSONObject(k) ?: continue
            out.add(
                WatchProgress(
                    url = o.optString("url", k),
                    positionSec = o.optDouble("pos", 0.0),
                    durationSec = o.optDouble("dur", 0.0),
                    title = o.optString("title", ""),
                    updatedAt = o.optLong("at", 0L)
                )
            )
        }
        return out.sortedByDescending { it.updatedAt }
    }

    private fun read(): JSONObject {
        val raw = prefs.getString(KEY_MAP, null) ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun write(root: JSONObject) {
        prefs.edit().putString(KEY_MAP, root.toString()).apply()
    }

    private fun prune(root: JSONObject) {
        if (root.length() <= MAX_ENTRIES) return
        // Drop oldest by timestamp until back under the cap.
        val entries = ArrayList<Pair<String, Long>>(root.length())
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val at = root.optJSONObject(k)?.optLong("at", 0L) ?: 0L
            entries.add(k to at)
        }
        entries.sortBy { it.second } // oldest first
        var i = 0
        while (root.length() > MAX_ENTRIES && i < entries.size) {
            root.remove(entries[i].first)
            i++
        }
    }

    private fun normalize(url: String): String? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
        return trimmed.substringBefore('#').takeIf { it.isNotBlank() }
    }

    companion object {
        private const val PREFS_NAME = "player_browser_watch"
        private const val KEY_MAP = "progress"
        private const val MIN_POSITION_SEC = 10.0
        private const val END_GUARD_SEC = 20.0
        private const val MAX_ENTRIES = 500

        @Volatile private var instance: WatchProgressStore? = null
        fun get(context: Context): WatchProgressStore =
            instance ?: synchronized(this) {
                instance ?: WatchProgressStore(context.applicationContext)
                    .also { instance = it }
            }
    }
}
