package com.playerbrowser.app.network

/**
 * Volatile mirror of the Private DNS settings, read on the WebView intercept
 * hot path ([SniBypassClient.intercept]) and inside [DohClient]. Mirrored from
 * DataStore by PlayerBrowserApp so we avoid a suspend read per request.
 *
 *  - [enabled]: route ALL https main-frame requests through our OkHttp path so
 *    the configured DoH resolver applies to every page, independently of SNI
 *    bypass.
 *  - [dohUrl]: the effective DoH endpoint used wherever DoH runs (Private DNS
 *    path AND the SNI-bypass path).
 */
object PrivateDnsSwitch {
    @Volatile
    var enabled: Boolean = false

    @Volatile
    var dohUrl: String = DohProvider.CLOUDFLARE.url
}
