package com.playerbrowser.app

import android.app.Application
import com.playerbrowser.app.data.BrowserRepository
import com.playerbrowser.app.network.NetworkSettingsRepository
import com.playerbrowser.app.network.ProxyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PlayerBrowserApp : Application() {
    val repository: BrowserRepository by lazy { BrowserRepository.get(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        applyStoredProxy()
    }

    private fun applyStoredProxy() {
        if (!ProxyManager.isSupported()) return
        appScope.launch {
            val settings = NetworkSettingsRepository.get(this@PlayerBrowserApp).current()
            ProxyManager.apply(settings)
        }
    }
}
