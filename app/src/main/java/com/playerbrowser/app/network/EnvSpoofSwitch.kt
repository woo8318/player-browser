package com.playerbrowser.app.network

import android.content.Context

/**
 * `BrowserEnvPatch` 의 document-start 주입을 통째로 켜고 끄는 스위치 (v1.3.67).
 *
 * v1.3.58~63 이 "WebView 표식 지우기" 를 목표로 여덟 라운드를 돌아 진단이
 * `없음=[-]` 까지 갔는데도 캡차 루프가 그대로였다. 그렇다면 한 번도 확인하지
 * 않은 가정이 남는다 — **더한 것 자체가 신호일 가능성**. 특히
 * `Function.prototype.toString` 이 네이티브가 아닌 함수로 교체된 것은 그 자체가
 * 자동화 지표이고, 새 iframe(다른 realm)의 원본과 대조하면 즉시 드러난다.
 * 추측으로 한쪽을 고르는 대신 사용자가 끄고 켜서 A/B 로 답을 얻게 한다.
 *
 * **왜 DataStore 만으로는 안 되는가 (v1.3.70).** 다른 스위치들은
 * `shouldInterceptRequest` 같은 요청 hot path 에서 읽히므로 앱이 뜨고 한참 뒤에
 * 값이 필요하다 — `PlayerBrowserApp.observeNetworkSwitches()` 의 비동기 미러가
 * 도착할 시간이 충분하다. 그런데 이 스위치만은 **WebView 를 만드는 순간**
 * (`BrowserEnvPatch.install`) 읽힌다. 앱 시작 로그를 보면 UA 정규화 5ms 뒤에
 * 이미 주입이 끝나 있어서, DataStore 의 첫 emission(디스크 읽기)이 그 경쟁에서
 * 항상 진다 → 토글을 꺼도 재시작하면 기본값 `true` 로 주입됐다.
 * 그래서 이 값만 **동기적으로 읽히는 SharedPreferences 에 따로 미러**하고
 * [attach] 가 `Application.onCreate` 에서 먼저 올린다. DataStore 는 계속 원본
 * (설정 화면이 보여주는 값)이고, 여기 미러는 그 사본일 뿐이다.
 */
object EnvSpoofSwitch {

    private const val PREFS_NAME = "env_spoof"
    private const val KEY_ENABLED = "enabled"

    @Volatile
    var enabled: Boolean = true

    /** 앱 시작 시 1회 — WebView 가 만들어지기 전에 값을 확정한다. */
    fun attach(context: Context) {
        runCatching {
            enabled = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true)
        }
        DebugLog.d("UserAgent", "브라우저 위장 설정 로드: ${if (enabled) "켬" else "끔"}")
    }

    /** 설정이 저장될 때 미러도 함께 갱신한다. */
    fun persist(context: Context, value: Boolean) {
        enabled = value
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, value)
                .apply()
        }
    }
}
