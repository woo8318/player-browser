package com.playerbrowser.app.network

/**
 * Hot-readable switch for "링크를 항상 새 탭에서 열기", mirrored from
 * [NetworkSettings.openLinksInNewTab] by the application observer. Read inside
 * [android.webkit.WebViewClient.shouldOverrideUrlLoading] for every main-frame
 * navigation, so it lives outside DataStore to avoid suspending on the hot path.
 */
object LinkNewTabSwitch {
    @Volatile
    var enabled: Boolean = false
}
