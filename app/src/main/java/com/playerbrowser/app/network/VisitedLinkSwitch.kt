package com.playerbrowser.app.network

/**
 * Hot-readable switch for "방문한 링크 표시 (도메인 변경 대응)", mirrored from
 * [NetworkSettings.visitedLinkMarkEnabled] by the application observer. Read in
 * [android.webkit.WebViewClient.onPageFinished] for every page, so it lives
 * outside DataStore to avoid suspending on the main thread.
 */
object VisitedLinkSwitch {
    @Volatile
    var enabled: Boolean = true
}
