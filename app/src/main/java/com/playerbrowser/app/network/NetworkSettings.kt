package com.playerbrowser.app.network

data class NetworkSettings(
    val proxyEnabled: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val sniBypassEnabled: Boolean = true
) {
    fun isValid(): Boolean =
        proxyHost.isNotBlank() && proxyPort in 1..65535

    fun hostPort(): String = "$proxyHost:$proxyPort"

    fun hasAuth(): Boolean = proxyUsername.isNotBlank()

    companion object {
        val DEFAULT = NetworkSettings()
    }
}
