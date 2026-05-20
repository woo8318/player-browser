package com.playerbrowser.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object UpdateClient {
    private const val USER_AGENT = "PlayerBrowser-Updater"

    suspend fun fetchLatestRelease(owner: String, repo: String): UpdateInfo =
        withContext(Dispatchers.IO) {
            val url = URL("https://api.github.com/repos/$owner/$repo/releases/latest")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", USER_AGENT)
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tag = json.getString("tag_name")
                val assets = json.getJSONArray("assets")
                var apkUrl: String? = null
                var apkSize = 0L
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.getString("browser_download_url")
                        apkSize = a.optLong("size", 0L)
                        break
                    }
                }
                if (apkUrl == null) throw IOException("Release에 APK 자산이 없습니다.")
                UpdateInfo(
                    versionName = Version.normalize(tag),
                    tagName = tag,
                    releaseName = json.optString("name").ifBlank { tag },
                    releaseNotes = json.optString("body").orEmpty(),
                    apkUrl = apkUrl,
                    apkSize = apkSize,
                    htmlUrl = json.optString("html_url")
                )
            } finally {
                conn.disconnect()
            }
        }
}
