package com.playerbrowser.app.web

/**
 * Page keys and their hashes for [VisitedLinkMarker] — a bit-for-bit mirror of
 * `siteKey` / `pageKey` / `hash` in `visited_links.js`. Keep the two in step;
 * a drift only drops marks (hashes stop matching), it never mis-marks.
 *
 * Pure Kotlin (no `android.*`) so the parity check can run on a plain JVM.
 */
internal object VisitedLinkKeys {

    private val LAST_DIGIT_RUN = Regex("""\d+(?=\D*$)""")
    private val IPV4 = Regex("""^\d+(\.\d+){3}$""")

    /**
     * Host with its last digit run collapsed to `#` — the run `UrlRecovery`
     * bumps (newtoki123 → newtoki#). Null for IP literals, hosts without a
     * digit, and hosts whose numbered label has no letter (`163.com` is a
     * name, not a mirror number — `#.com` would lump unrelated sites).
     */
    fun siteKey(host: String?): String? {
        var h = host?.lowercase() ?: return null
        if (h.startsWith("www.")) h = h.substring(4)
        if (h.isEmpty() || ':' in h || IPV4.matches(h)) return null
        val run = LAST_DIGIT_RUN.find(h) ?: return null
        val labelStart = h.lastIndexOf('.', run.range.first) + 1
        val labelEnd = h.indexOf('.', run.range.last).let { if (it < 0) h.length else it }
        if (h.substring(labelStart, labelEnd).none { it in 'a'..'z' }) return null
        return h.replaceRange(run.range, "#")
    }

    /**
     * Host of an http(s) URL as WebView reports it (already Chromium-normalized,
     * so plain string slicing agrees with the JS `a.hostname`), or null.
     */
    fun hostOf(url: String): String? = split(url)?.host

    /** siteKey + path (trailing `/` dropped) + `?query`; scheme and fragment ignored. */
    fun pageKey(url: String): String? {
        val parts = split(url) ?: return null
        val site = siteKey(parts.host) ?: return null
        var path = parts.path.ifEmpty { "/" }
        if (path.length > 1 && path.endsWith('/')) path = path.dropLast(1)
        return site + path + if (parts.query.isEmpty()) "" else "?" + parts.query
    }

    /** Site part of a [pageKey] — the key's path always starts with `/`. */
    fun siteOfPageKey(key: String): String = key.substringBefore('/')

    /**
     * cyrb53-style 64-bit hash as 16 hex chars. The page receives only these,
     * never the history URLs, so a sibling-numbered site can't read the list.
     */
    fun hash(key: String): String {
        var h1 = 0xdeadbeefL.toInt()
        var h2 = 0x41c6ce57
        for (ch in key) {
            h1 = (h1 xor ch.code) * M1
            h2 = (h2 xor ch.code) * M2
        }
        h1 = (h1 xor (h1 ushr 16)) * M3
        h1 = h1 xor ((h2 xor (h2 ushr 13)) * M4)
        h2 = (h2 xor (h2 ushr 16)) * M3
        h2 = h2 xor ((h1 xor (h1 ushr 13)) * M4)
        return hex8(h2) + hex8(h1)
    }

    private const val M1 = -1640531535 // 2654435761
    private const val M2 = 1597334677
    private const val M3 = -2048144789 // 2246822507
    private const val M4 = -1028477387 // 3266489909

    private fun hex8(v: Int): String = Integer.toHexString(v).padStart(8, '0')

    private class Parts(val host: String, val path: String, val query: String)

    private fun split(url: String): Parts? {
        val sep = url.indexOf("://")
        if (sep < 0) return null
        val scheme = url.substring(0, sep).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = url.substring(sep + 3)
        val authEnd = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        // Userinfo and port are not part of the host; an IPv6 literal collapses
        // to "[" here and siteKey rejects it, as the JS rejects "[::1]".
        val host = rest.substring(0, authEnd).substringAfterLast('@').substringBefore(':')
        val tail = rest.substring(authEnd).substringBefore('#')
        val q = tail.indexOf('?')
        return if (q < 0) Parts(host, tail, "")
        else Parts(host, tail.substring(0, q), tail.substring(q + 1))
    }
}
