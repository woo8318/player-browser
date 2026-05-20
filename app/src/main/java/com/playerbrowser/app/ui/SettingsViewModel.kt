package com.playerbrowser.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
            repository.update { next }
            val result = ProxyManager.apply(next)
            _event.value = ProxyApplyEvent.Message(result.toMessage())
        }
    }

    private fun ProxyApplyResult.toMessage(): String = when (this) {
        ProxyApplyResult.Success -> "프록시 적용됨"
        ProxyApplyResult.Cleared -> "프록시 해제됨"
        ProxyApplyResult.Unsupported -> "이 기기의 WebView가 프록시 설정을 지원하지 않습니다"
        is ProxyApplyResult.Invalid -> "프록시 설정 오류: $reason"
    }
}
