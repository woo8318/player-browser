package com.playerbrowser.app.web

object UrlUtils {
    private val URL_REGEX = Regex("^(https?://)?([\\w-]+\\.)+[\\w-]+(:\\d+)?(/.*)?$", RegexOption.IGNORE_CASE)

    fun normalize(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return "about:blank"
        if (trimmed.startsWith("about:") || trimmed.startsWith("javascript:")) return trimmed
        return if (URL_REGEX.matches(trimmed)) {
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
            else "https://$trimmed"
        } else {
            "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
        }
    }
}
