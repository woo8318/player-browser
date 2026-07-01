package com.playerbrowser.app.data

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.webkit.WebView
import java.io.File

/**
 * Persists each tab's WebView back/forward navigation history across process
 * death so that reopening the app keeps the in-tab history — pressing the
 * Android back button steps back through the pages the user visited instead of
 * immediately closing a freshly-reloaded tab.
 *
 * [TabPersistence] only records the final URL per tab, which is enough to
 * re-show the page but drops the `WebBackForwardList`. Here we serialize the
 * full list via [WebView.saveState] into a per-tab blob file under
 * `filesDir/tabstate/<id>.bin` (marshalled Bundle, same "keep it out of Room"
 * spirit as [TabPersistence] / [WatchProgressStore]).
 *
 * Caveats handled defensively:
 * - Marshalled Parcel bytes are not guaranteed stable across OS/WebView
 *   upgrades, so every restore is wrapped in try/catch and a failure simply
 *   deletes the stale blob and falls back to loading the tab's current URL
 *   (today's behavior). No crash, no data loss beyond the history list.
 * - Oversized blobs (runaway history) are skipped on write to avoid disk bloat.
 */
class TabWebStateStore(context: Context) {
    private val dir: File =
        File(context.applicationContext.filesDir, DIR_NAME).apply { mkdirs() }

    private fun fileFor(tabId: String): File = File(dir, "$tabId.bin")

    /** Serializes [webView]'s navigation history for [tabId]. Safe to call on
     *  detached WebViews. No-op (and removes any stale blob) when the WebView
     *  has no restorable state. */
    fun save(tabId: String, webView: WebView) {
        if (tabId.isBlank()) return
        runCatching {
            val bundle = Bundle()
            val list = webView.saveState(bundle)
            if (list == null || list.size == 0) {
                fileFor(tabId).delete()
                return
            }
            val parcel = Parcel.obtain()
            try {
                bundle.writeToParcel(parcel, 0)
                val bytes = parcel.marshall()
                if (bytes.size > MAX_BLOB_BYTES) {
                    fileFor(tabId).delete()
                    return
                }
                fileFor(tabId).writeBytes(bytes)
            } finally {
                parcel.recycle()
            }
        }
    }

    /** Saves every open tab's history in one pass (map key = tab id). */
    fun saveAll(webViews: Map<String, WebView>) {
        webViews.forEach { (id, wv) -> save(id, wv) }
    }

    /**
     * Restores [tabId]'s history into [webView]. Returns true when a non-empty
     * history was restored (caller should then NOT call loadUrl — restoreState
     * reloads the current entry itself). Returns false when there is no blob or
     * the blob could not be restored (caller should loadUrl the current URL).
     */
    fun restore(tabId: String, webView: WebView): Boolean {
        if (tabId.isBlank()) return false
        val file = fileFor(tabId)
        if (!file.exists()) return false
        return runCatching {
            val bytes = file.readBytes()
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                val bundle = Bundle(WebView::class.java.classLoader)
                bundle.readFromParcel(parcel)
                val list = webView.restoreState(bundle)
                list != null && list.size > 0
            } finally {
                parcel.recycle()
            }
        }.getOrElse {
            // Corrupt / incompatible blob — drop it and fall back to URL load.
            file.delete()
            false
        }
    }

    /** Deletes blobs for tabs that no longer exist, mirroring the WebView GC. */
    fun retain(liveIds: Set<String>) {
        runCatching {
            dir.listFiles()?.forEach { f ->
                val id = f.name.removeSuffix(".bin")
                if (id !in liveIds) f.delete()
            }
        }
    }

    fun remove(tabId: String) {
        runCatching { fileFor(tabId).delete() }
    }

    companion object {
        private const val DIR_NAME = "tabstate"
        // WebView.saveState already caps the serialized history; this is just a
        // sanity ceiling so a pathological blob never bloats internal storage.
        private const val MAX_BLOB_BYTES = 1_500_000
    }
}
