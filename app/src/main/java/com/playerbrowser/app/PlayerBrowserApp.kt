package com.playerbrowser.app

import android.app.Application
import com.google.android.gms.cast.framework.CastContext
import com.playerbrowser.app.data.BrowserRepository
import com.playerbrowser.app.network.AdBlockSwitch
import com.playerbrowser.app.network.CrashRecorder
import com.playerbrowser.app.network.DebugLog
import com.playerbrowser.app.network.NetworkSettingsRepository
import com.playerbrowser.app.network.ProxyManager
import com.playerbrowser.app.network.SniBypassSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PlayerBrowserApp : Application() {
    val repository: BrowserRepository by lazy { BrowserRepository.get(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Install crash recorder first so any subsequent init failure is captured.
        CrashRecorder.install(this)
        applyStoredProxy()
        observeNetworkSwitches()
        initCast()
    }

    private fun initCast() {
        // Cast framework requires Google Play Services. Devices without GMS
        // (e.g. some China-region ROMs, emulators) would otherwise crash on
        // startup, so initialization is best-effort and silently skipped.
        runCatching { CastContext.getSharedInstance(this) }
            .onFailure { DebugLog.w("Cast", "Cast init failed (Play Services missing?)", it) }
    }

    private fun applyStoredProxy() {
        if (!ProxyManager.isSupported()) return
        appScope.launch {
            val settings = NetworkSettingsRepository.get(this@PlayerBrowserApp).current()
            ProxyManager.apply(settings)
        }
    }

    private fun observeNetworkSwitches() {
        appScope.launch {
            NetworkSettingsRepository.get(this@PlayerBrowserApp).settings.collectLatest {
                SniBypassSwitch.enabled = it.sniBypassEnabled
                AdBlockSwitch.enabled = it.adBlockEnabled
            }
        }
    }
}
