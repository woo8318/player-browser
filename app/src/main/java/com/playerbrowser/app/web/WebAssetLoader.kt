package com.playerbrowser.app.web

import android.content.Context
import java.io.BufferedReader

object WebAssetLoader {
    @Volatile private var cachedGestureJs: String? = null

    fun gestureScript(context: Context): String {
        cachedGestureJs?.let { return it }
        return synchronized(this) {
            cachedGestureJs ?: context.assets.open("video_gestures.js").use { stream ->
                stream.bufferedReader().use(BufferedReader::readText)
            }.also { cachedGestureJs = it }
        }
    }
}
