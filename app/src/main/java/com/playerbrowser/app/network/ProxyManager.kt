package com.playerbrowser.app.network

import android.os.Handler
import android.os.Looper
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

sealed class ProxyApplyResult {
    object Success : ProxyApplyResult()
    object Cleared : ProxyApplyResult()
    object Unsupported : ProxyApplyResult()
    data class Invalid(val reason: String) : ProxyApplyResult()
}

object ProxyManager {

    fun isSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    suspend fun apply(settings: NetworkSettings): ProxyApplyResult {
        if (!isSupported()) return ProxyApplyResult.Unsupported
        if (!settings.proxyEnabled) {
            clearBlocking()
            return ProxyApplyResult.Cleared
        }
        if (!settings.isValid()) {
            return ProxyApplyResult.Invalid("host/port가 비어있거나 잘못되었습니다")
        }
        val config = ProxyConfig.Builder()
            .addProxyRule(settings.hostPort())
            .addDirect()
            .removeImplicitRules()
            .build()
        return suspendCancellableCoroutine { cont ->
            ProxyController.getInstance().setProxyOverride(
                config,
                { runnable -> Handler(Looper.getMainLooper()).post(runnable) },
                { if (cont.isActive) cont.resume(ProxyApplyResult.Success) }
            )
        }
    }

    private suspend fun clearBlocking() {
        if (!isSupported()) return
        suspendCancellableCoroutine<Unit> { cont ->
            ProxyController.getInstance().clearProxyOverride(
                { runnable -> Handler(Looper.getMainLooper()).post(runnable) },
                { if (cont.isActive) cont.resume(Unit) }
            )
        }
    }
}
