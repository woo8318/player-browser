package com.playerbrowser.app.web

import android.content.Context
import android.webkit.JavascriptInterface
import com.playerbrowser.app.data.WatchProgressStore
import com.playerbrowser.app.network.ResumeSwitch

/**
 * `window.PBResume` — bridge between [com.playerbrowser.app.assets video_gestures.js]
 * and [WatchProgressStore]. One instance per WebView; the owning [android.webkit.WebViewClient]
 * keeps [currentUrl] in sync so positions are keyed by the page the user is on
 * (not by an iframe / blob src that changes every load).
 *
 * All methods run on the JS-bridge (binder) thread, so the store is synchronized
 * and [currentUrl] is volatile.
 */
class ResumeBridge(context: Context) {
    private val store = WatchProgressStore.get(context)

    @Volatile
    var currentUrl: String? = null

    @JavascriptInterface
    fun save(positionSec: Double, durationSec: Double, title: String) {
        if (!ResumeSwitch.enabled) return
        val url = currentUrl ?: return
        store.save(url, positionSec, durationSec, title)
    }

    /** Saved position in seconds for the current page, or -1 if none / disabled. */
    @JavascriptInterface
    fun load(): Double {
        if (!ResumeSwitch.enabled) return -1.0
        val url = currentUrl ?: return -1.0
        return store.position(url)
    }

    @JavascriptInterface
    fun clear() {
        val url = currentUrl ?: return
        store.delete(url)
    }
}
