package com.playerbrowser.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.playerbrowser.app.network.DohProvider
import com.playerbrowser.app.network.NetworkSettings
import com.playerbrowser.app.network.NetworkSettingsRepository
import com.playerbrowser.app.network.ProxyApplyResult
import com.playerbrowser.app.network.ProxyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class ProxyApplyEvent {
    data class Message(val text: String) : ProxyApplyEvent()
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = NetworkSettingsRepository.get(app)

    val settings: StateFlow<NetworkSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkSettings.DEFAULT)

    val proxySupported: Boolean = ProxyManager.isSupported()

    private val _event = MutableStateFlow<ProxyApplyEvent?>(null)
    val event: StateFlow<ProxyApplyEvent?> = _event.asStateFlow()

    fun consumeEvent() { _event.value = null }

    fun saveAndApply(next: NetworkSettings) {
        viewModelScope.launch {
            repository.update { current ->
                next.copy(
                    sniBypassEnabled = current.sniBypassEnabled,
                    adBlockEnabled = current.adBlockEnabled,
                    cookieBannerEnabled = current.cookieBannerEnabled,
                    resumePlaybackEnabled = current.resumePlaybackEnabled,
                    openLinksInNewTab = current.openLinksInNewTab,
                    privateDnsEnabled = current.privateDnsEnabled,
                    jsEnvSpoofEnabled = current.jsEnvSpoofEnabled,
                    dohProvider = current.dohProvider,
                    dohCustomUrl = current.dohCustomUrl
                )
            }
            val result = ProxyManager.apply(next)
            _event.value = ProxyApplyEvent.Message(result.toMessage())
        }
    }

    fun setSniBypassEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.update { it.copy(sniBypassEnabled = enabled) }
            _event.value = ProxyApplyEvent.Message(
                if (enabled) "자동 SNI 우회 켜짐" else "자동 SNI 우회 꺼짐"
            )
        }
    }

    fun setAdBlockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.update { it.copy(adBlockEnabled = enabled) }
            _event.value = ProxyApplyEvent.Message(
                if (enabled) "광고 차단 켜짐" else "광고 차단 꺼짐"
            )
        }
    }

    fun setCookieBannerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.update { it.copy(cookieBannerEnabled = enabled) }
            _event.value = ProxyApplyEvent.Message(
                if (enabled) "쿠키 배너 자동 거부 켜짐" else "쿠키 배너 자동 거부 꺼짐"
            )
        }
    }

    fun setJsEnvSpoofEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.update { it.copy(jsEnvSpoofEnabled = enabled) }
            _event.value = ProxyApplyEvent.Message(
                if (enabled) "JS 환경 위장 켜짐 — 새 탭부터 적용"
                else "JS 환경 위장 꺼짐 — 새 탭부터 적용"
            )
        }
    }

    fun setResumePlaybackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.update { it.copy(resumePlaybackEnabled = enabled) }
            _event.value = ProxyApplyEvent.Message(
                if (enabled) "이어보기 켜짐" else "이어보기 꺼짐"
            )
        }
    }

    fun setOpenLinksInNewTab(enabled: Boolean) {
        viewModelScope.launch {
            repository.update { it.copy(openLinksInNewTab = enabled) }
            _event.value = ProxyApplyEvent.Message(
                if (enabled) "링크를 새 탭에서 열기 켜짐" else "링크를 새 탭에서 열기 꺼짐"
            )
        }
    }

    fun setPrivateDnsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.update { it.copy(privateDnsEnabled = enabled) }
            _event.value = ProxyApplyEvent.Message(
                if (enabled) "프라이빗 DNS 켜짐 (새로 로드되는 페이지부터 적용)"
                else "프라이빗 DNS 꺼짐"
            )
        }
    }

    /**
     * Persists the DoH provider selection. For a custom provider the URL must be
     * a valid https endpoint, otherwise the change is rejected with a message.
     */
    fun setDohProvider(providerKey: String, customUrl: String) {
        val provider = DohProvider.fromKey(providerKey)
        val trimmed = customUrl.trim()
        if (provider == DohProvider.CUSTOM && !trimmed.startsWith("https://")) {
            _event.value = ProxyApplyEvent.Message("유효한 https DoH URL을 입력하세요")
            return
        }
        viewModelScope.launch {
            repository.update { it.copy(dohProvider = provider.key, dohCustomUrl = trimmed) }
            _event.value = ProxyApplyEvent.Message("DNS 제공자 적용됨: ${provider.label}")
        }
    }

    private fun ProxyApplyResult.toMessage(): String = when (this) {
        ProxyApplyResult.Success -> "프록시 적용됨"
        ProxyApplyResult.Cleared -> "프록시 해제됨"
        ProxyApplyResult.Unsupported -> "이 기기의 WebView가 프록시 설정을 지원하지 않습니다"
        is ProxyApplyResult.Invalid -> "프록시 설정 오류: $reason"
    }
}
