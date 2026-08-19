package com.playerbrowser.app

import android.app.Application
import android.webkit.WebView
import com.google.android.gms.cast.framework.CastContext
import com.playerbrowser.app.data.BrowserRepository
import com.playerbrowser.app.network.AdBlockSwitch
import com.playerbrowser.app.network.ChallengeDetector
import com.playerbrowser.app.network.CookieBannerSwitch
import com.playerbrowser.app.network.CrashRecorder
import com.playerbrowser.app.network.DebugLog
import com.playerbrowser.app.network.DohProvider
import com.playerbrowser.app.network.EnvSpoofSwitch
import com.playerbrowser.app.network.LinkNewTabSwitch
import com.playerbrowser.app.network.NetworkSettingsRepository
import com.playerbrowser.app.network.PrivateDnsSwitch
import com.playerbrowser.app.network.ProxyManager
import com.playerbrowser.app.network.ResumeSwitch
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
        // 저장된 챌린지 격리 목록을 먼저 올린다 — 첫 페이지 로드부터 적용돼야
        // 격리 이전의 요청 한 번이 우회(OkHttp) 경로로 새지 않는다.
        ChallengeDetector.attach(this)
        // WebView 는 앱이 뜨자마자 만들어지므로 DataStore 의 비동기 미러를
        // 기다릴 수 없다 — 동기 미러에서 먼저 확정한다.
        EnvSpoofSwitch.attach(this)
        // Debug builds only: expose WebView contents to chrome://inspect so we
        // can diagnose page issues (e.g. images stalling) over USB / WiFi adb.
        if (BuildConfig.DEBUG) {
            runCatching { WebView.setWebContentsDebuggingEnabled(true) }
        }
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
                CookieBannerSwitch.enabled = it.cookieBannerEnabled
                ResumeSwitch.enabled = it.resumePlaybackEnabled
                LinkNewTabSwitch.enabled = it.openLinksInNewTab
                PrivateDnsSwitch.enabled = it.privateDnsEnabled
                EnvSpoofSwitch.enabled = it.jsEnvSpoofEnabled
                PrivateDnsSwitch.dohUrl = DohProvider.resolveUrl(it.dohProvider, it.dohCustomUrl)
            }
        }
    }
}
