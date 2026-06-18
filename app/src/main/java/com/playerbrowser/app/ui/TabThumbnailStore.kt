package com.playerbrowser.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.webkit.WebView
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * In-memory cache of tab thumbnails keyed by tab id, backing the Opera-style
 * tab gallery. Captures are best-effort downscaled snapshots of the *live*
 * WebView (taken while it's attached and laid out, i.e. for the active tab),
 * not persisted across process death — a tab that was never captured this
 * session simply renders its title/url placeholder card.
 *
 * Held alongside [BrowserWebViewState]s in RootNavigation so it survives
 * navigation, and pruned in lock-step with the WebView GC.
 */
class TabThumbnailStore {
    // SnapshotState map so gallery cards recompose the moment a fresh capture lands.
    private val thumbs = mutableStateMapOf<String, ImageBitmap>()

    fun get(tabId: String): ImageBitmap? = thumbs[tabId]

    /**
     * Captures [webView]'s current frame into a downscaled thumbnail for [tabId].
     * No-op when the view isn't laid out yet (width/height 0) — e.g. a tab that
     * was never displayed. RGB_565 halves the memory vs ARGB_8888; the white
     * fill keeps transparent pages from rendering as black in the gallery.
     */
    fun capture(tabId: String, webView: WebView, targetWidthPx: Int = 600) {
        val w = webView.width
        val h = webView.height
        if (w <= 0 || h <= 0) return
        val bmp = runCatching {
            val scale = targetWidthPx.toFloat() / w
            val outH = (h * scale).toInt().coerceIn(1, targetWidthPx * 4)
            Bitmap.createBitmap(targetWidthPx, outH, Bitmap.Config.RGB_565).also { out ->
                val canvas = Canvas(out)
                canvas.drawColor(AndroidColor.WHITE)
                canvas.scale(scale, scale)
                webView.draw(canvas)
            }
        }.getOrNull() ?: return
        thumbs[tabId] = bmp.asImageBitmap()
    }

    fun forget(tabId: String) { thumbs.remove(tabId) }

    /** Drops thumbnails for tabs that no longer exist, mirroring the WebView GC. */
    fun retain(liveIds: Set<String>) {
        val stale = thumbs.keys.filterNot { it in liveIds }
        stale.forEach { thumbs.remove(it) }
    }
}
