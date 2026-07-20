package com.playerbrowser.app.web

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * `window.PBPlayer` — bridge between [com.playerbrowser.app.assets video_gestures.js]
 * and the external Media3 player. When the user long-presses a `<video>`, the JS
 * reads that element's source URL and calls [openVideo]; the browser screen then
 * resolves it to a playable stream (direct URL verbatim, or a fallback to the
 * sniffed network stream for blob:/MSE videos) and launches the player.
 *
 * [openVideo] runs on the JS-bridge (binder) thread, so it hops to the main
 * thread before invoking the UI callback.
 */
class PlayerBridge(private val onOpenVideo: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    /** domSrc is the long-pressed video's currentSrc/<source> ("" if unresolved). */
    @JavascriptInterface
    fun openVideo(domSrc: String?) {
        val src = domSrc.orEmpty()
        main.post { onOpenVideo(src) }
    }
}
