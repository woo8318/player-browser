package com.playerbrowser.app.network

/**
 * Hot-readable switch for the cookie consent banner auto-rejecter,
 * mirrored from [NetworkSettings.cookieBannerEnabled] by the application
 * observer. Lives outside DataStore because [CookieBannerKiller] is invoked
 * from the WebViewClient's [android.webkit.WebViewClient.onPageFinished] on
 * every page load and must not suspend.
 */
object CookieBannerSwitch {
    @Volatile
    var enabled: Boolean = true
}
