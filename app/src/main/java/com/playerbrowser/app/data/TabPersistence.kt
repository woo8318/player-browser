package com.playerbrowser.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PersistedTab(
    val id: String,
    val url: String,
    val title: String
)

data class PersistedSession(
    val tabs: List<PersistedTab>,
    val activeTabId: String?
)

class TabPersistence(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): PersistedSession? {
        val raw = prefs.getString(KEY_SESSION, null) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("tabs") ?: JSONArray()
            val tabs = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                PersistedTab(
                    id = o.optString("id"),
                    url = o.optString("url"),
                    title = o.optString("title")
                ).takeIf { it.id.isNotBlank() }
            }
            val active = root.optString("activeTabId").ifBlank { null }
            if (tabs.isEmpty()) null else PersistedSession(tabs, active)
        }.getOrNull()
    }

    fun save(session: PersistedSession) {
        val arr = JSONArray()
        session.tabs.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("url", t.url)
                    .put("title", t.title)
            )
        }
        val root = JSONObject()
            .put("tabs", arr)
            .put("activeTabId", session.activeTabId ?: "")
        prefs.edit().putString(KEY_SESSION, root.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "player_browser_tabs"
        private const val KEY_SESSION = "session"
    }
}
