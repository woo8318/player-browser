package com.playerbrowser.app.web

import android.content.Context
import java.io.BufferedReader

object WebAssetLoader {
    @Volatile private var cachedGestureJs: String? = null
    @Volatile private var cachedVisitedJs: String? = null

    fun gestureScript(context: Context): String {
        cachedGestureJs?.let { return it }
        return synchronized(this) {
            cachedGestureJs ?: read(context, "video_gestures.js").also { cachedGestureJs = it }
        }
    }

    /** `visited_links.js` — a bare function expression, see [VisitedLinkMarker]. */
    fun visitedLinksScript(context: Context): String {
        cachedVisitedJs?.let { return it }
        return synchronized(this) {
            cachedVisitedJs ?: read(context, "visited_links.js").also { cachedVisitedJs = it }
        }
    }

    private fun read(context: Context, name: String): String =
        context.assets.open(name).use { stream ->
            stream.bufferedReader().use(BufferedReader::readText)
        }
}
