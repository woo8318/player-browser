package com.playerbrowser.app.update

import com.playerbrowser.app.network.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object UpdateClient {
    private const val USER_AGENT = "PlayerBrowser-Updater"
    private const val TAG = "Update"

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
                // GitHub API 는 미인증이면 **IP 당 시간당 60회** 제한이고, 모바일
                // 캐리어는 CGNAT 로 IP 를 공유하므로 내가 몇 번 안 눌렀어도 한도에
                // 걸린다 (403 / 429). 그때는 API 를 아예 안 타는 경로로 폴백한다.
                if (code == 403 || code == 429) {
                    val remaining = conn.getHeaderField("X-RateLimit-Remaining")
                    val reset = conn.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
                    DebugLog.w(TAG, "GitHub API 한도 (HTTP $code, remaining=$remaining) → 웹 리다이렉트로 폴백")
                    return@withContext runCatching { fetchViaRedirect(owner, repo) }
                        .getOrElse { e ->
                            DebugLog.w(TAG, "폴백도 실패: ${e.javaClass.simpleName}: ${e.message}")
                            throw IOException(limitMessage(code, reset))
                        }
                }
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

    /**
     * API 한도에 걸렸을 때의 폴백 — `github.com/<owner>/<repo>/releases/latest` 는
     * **일반 웹 페이지**라 API rate limit 과 무관하다. 리다이렉트를 따라가지 않고
     * `Location` 헤더(`.../releases/tag/v1.3.67`)에서 최신 태그만 뽑는다.
     *
     * APK 주소는 CI 가 올리는 이름 규칙(`player-browser-<tag>.apk`,
     * `.github/workflows/android.yml`)으로 조립한다. 릴리스 노트와 파일 크기는
     * 알 수 없지만(다운로드는 `Content-Length` 로 진행률을 잡는다) 업데이트
     * 자체는 그대로 된다.
     */
    private fun fetchViaRedirect(owner: String, repo: String): UpdateInfo {
        val url = URL("https://github.com/$owner/$repo/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html")
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        try {
            val code = conn.responseCode
            val location = conn.getHeaderField("Location").orEmpty()
            val tag = location.substringAfterLast("/tag/", "").trim()
            if (tag.isBlank()) throw IOException("최신 태그를 찾지 못했습니다 (HTTP $code)")
            DebugLog.d(TAG, "폴백으로 최신 태그 확인: $tag")
            return UpdateInfo(
                versionName = Version.normalize(tag),
                tagName = tag,
                releaseName = tag,
                releaseNotes = "(GitHub API 요청 한도로 릴리스 노트를 가져오지 못했습니다.)",
                apkUrl = "https://github.com/$owner/$repo/releases/download/$tag/player-browser-$tag.apk",
                apkSize = 0L,
                htmlUrl = "https://github.com/$owner/$repo/releases/tag/$tag"
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun limitMessage(code: Int, reset: Long?): String {
        val minutes = reset
            ?.let { (it * 1000L - System.currentTimeMillis()) / 60_000L }
            ?.coerceAtLeast(0L)
        val base = "GitHub 요청 한도를 넘었습니다 (HTTP $code)"
        return if (minutes != null) "$base — 약 ${minutes}분 뒤 다시 시도할 수 있습니다."
        else "$base — 잠시 뒤 다시 시도해 주세요."
    }
}
