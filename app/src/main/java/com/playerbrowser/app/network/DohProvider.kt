package com.playerbrowser.app.network

/**
 * Selectable DNS-over-HTTPS providers for the Private DNS feature. Each preset
 * points at a standard RFC 8484 (wire-format) DoH endpoint, so any of them work
 * with [DohClient]'s POST `application/dns-message` queries. CUSTOM lets the
 * user supply their own endpoint (e.g. NextDNS, a self-hosted resolver).
 */
enum class DohProvider(val key: String, val label: String, val url: String) {
    CLOUDFLARE("cloudflare", "Cloudflare (1.1.1.1)", "https://cloudflare-dns.com/dns-query"),
    GOOGLE("google", "Google (8.8.8.8)", "https://dns.google/dns-query"),
    QUAD9("quad9", "Quad9 (9.9.9.9)", "https://dns.quad9.net/dns-query"),
    ADGUARD("adguard", "AdGuard (광고·추적 차단)", "https://dns.adguard-dns.com/dns-query"),
    CUSTOM("custom", "직접 입력", "");

    companion object {
        fun fromKey(key: String?): DohProvider =
            entries.firstOrNull { it.key == key } ?: CLOUDFLARE

        /**
         * Effective DoH endpoint for a (providerKey, customUrl) pair. Falls back
         * to Cloudflare if a custom URL is blank, so the resolver is never left
         * pointing at nothing.
         */
        fun resolveUrl(key: String?, customUrl: String): String {
            val provider = fromKey(key)
            val url = if (provider == CUSTOM) customUrl.trim() else provider.url
            return url.ifBlank { CLOUDFLARE.url }
        }
    }
}
