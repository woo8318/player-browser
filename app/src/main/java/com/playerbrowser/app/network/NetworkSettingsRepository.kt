package com.playerbrowser.app.network

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.networkDataStore by preferencesDataStore(name = "network_settings")

class NetworkSettingsRepository(private val context: Context) {

    private object Keys {
        val PROXY_ENABLED = booleanPreferencesKey("proxy_enabled")
        val PROXY_HOST = stringPreferencesKey("proxy_host")
        val PROXY_PORT = intPreferencesKey("proxy_port")
        val PROXY_USERNAME = stringPreferencesKey("proxy_username")
        val PROXY_PASSWORD = stringPreferencesKey("proxy_password")
        val SNI_BYPASS_ENABLED = booleanPreferencesKey("sni_bypass_enabled")
    }

    val settings: Flow<NetworkSettings> =
        context.networkDataStore.data.map { it.toSettings() }

    suspend fun current(): NetworkSettings =
        context.networkDataStore.data.first().toSettings()

    suspend fun update(transform: (NetworkSettings) -> NetworkSettings) {
        context.networkDataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.PROXY_ENABLED] = next.proxyEnabled
            prefs[Keys.PROXY_HOST] = next.proxyHost
            prefs[Keys.PROXY_PORT] = next.proxyPort
            prefs[Keys.PROXY_USERNAME] = next.proxyUsername
            prefs[Keys.PROXY_PASSWORD] = next.proxyPassword
            prefs[Keys.SNI_BYPASS_ENABLED] = next.sniBypassEnabled
        }
    }

    private fun Preferences.toSettings(): NetworkSettings = NetworkSettings(
        proxyEnabled = this[Keys.PROXY_ENABLED] ?: false,
        proxyHost = this[Keys.PROXY_HOST].orEmpty(),
        proxyPort = this[Keys.PROXY_PORT] ?: 0,
        proxyUsername = this[Keys.PROXY_USERNAME].orEmpty(),
        proxyPassword = this[Keys.PROXY_PASSWORD].orEmpty(),
        sniBypassEnabled = this[Keys.SNI_BYPASS_ENABLED] ?: true
    )

    companion object {
        @Volatile private var instance: NetworkSettingsRepository? = null
        fun get(context: Context): NetworkSettingsRepository =
            instance ?: synchronized(this) {
                instance ?: NetworkSettingsRepository(context.applicationContext)
                    .also { instance = it }
            }
    }
}
