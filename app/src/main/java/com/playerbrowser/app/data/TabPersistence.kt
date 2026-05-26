package com.playerbrowser.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PersistedTab(
    val id: String,
    val url: String,
    val title: String,
    val parentTabId: String? = null,
    val groupId: String? = null
)

data class PersistedGroup(
    val id: String,
    val name: String,
    val color: Int
)

data class PersistedSession(
    val tabs: List<PersistedTab>,
    val activeTabId: String?,
    val groups: List<PersistedGroup> = emptyList()
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
                    title = o.optString("title"),
                    parentTabId = o.optString("parentTabId").ifBlank { null },
                    groupId = o.optString("groupId").ifBlank { null }
                ).takeIf { it.id.isNotBlank() }
            }
            val groupsArr = root.optJSONArray("groups") ?: JSONArray()
            val groups = (0 until groupsArr.length()).mapNotNull { i ->
                val o = groupsArr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isBlank()) return@mapNotNull null
                PersistedGroup(
                    id = id,
                    name = o.optString("name").ifBlank { "그룹" },
                    color = o.optInt("color", DEFAULT_GROUP_COLOR)
                )
            }
            val active = root.optString("activeTabId").ifBlank { null }
            if (tabs.isEmpty()) null else PersistedSession(tabs, active, groups)
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
                    .put("parentTabId", t.parentTabId ?: "")
                    .put("groupId", t.groupId ?: "")
            )
        }
        val groupsArr = JSONArray()
        session.groups.forEach { g ->
            groupsArr.put(
                JSONObject()
                    .put("id", g.id)
                    .put("name", g.name)
                    .put("color", g.color)
            )
        }
        val root = JSONObject()
            .put("tabs", arr)
            .put("activeTabId", session.activeTabId ?: "")
            .put("groups", groupsArr)
        prefs.edit().putString(KEY_SESSION, root.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "player_browser_tabs"
        private const val KEY_SESSION = "session"
        private const val DEFAULT_GROUP_COLOR = 0xFF607D8B.toInt()
    }
}
