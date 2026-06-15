package com.playerbrowser.app.network

/**
 * Hot-readable switch for "이어보기" (resume playback), mirrored from
 * [NetworkSettings.resumePlaybackEnabled] by the application observer. Read on
 * the JS-bridge thread for every save/load call, so it lives outside DataStore
 * to avoid suspending.
 */
object ResumeSwitch {
    @Volatile
    var enabled: Boolean = true
}
