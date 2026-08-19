package com.playerbrowser.app.network

data class NetworkSettings(
    val proxyEnabled: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val sniBypassEnabled: Boolean = true,
    val adBlockEnabled: Boolean = true,
    val cookieBannerEnabled: Boolean = true,
    val resumePlaybackEnabled: Boolean = true,
    val openLinksInNewTab: Boolean = false,
    val privateDnsEnabled: Boolean = false,
    val jsEnvSpoofEnabled: Boolean = true,
    val dohProvider: String = DohProvider.CLOUDFLARE.key,
    val dohCustomUrl: String = ""
) {
    fun isValid(): Boolean =
        proxyHost.isNotBlank() && proxyPort in 1..65535

    fun hostPort(): String = "$proxyHost:$proxyPort"

    fun hasAuth(): Boolean = proxyUsername.isNotBlank()

    companion object {
        val DEFAULT = NetworkSettings()
    }
}
