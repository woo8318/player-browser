package com.playerbrowser.app.network

/**
 * Hot-readable switch for the ad blocker, mirrored from
 * [NetworkSettings.adBlockEnabled] by the application observer. Lives
 * outside DataStore because [AdBlocker.intercept] is called on every
 * subresource request and must not suspend.
 */
object AdBlockSwitch {
    @Volatile
    var enabled: Boolean = true
}
