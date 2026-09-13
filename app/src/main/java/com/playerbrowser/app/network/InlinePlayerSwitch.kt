package com.playerbrowser.app.network

/**
 * "영상 재생 방식" 설정의 volatile 미러. `true` 면 페이지의 `<video>` 가 재생을
 * 시작하고 스트림이 스니핑되는 즉시 그 자리에 우리 Media3 플레이어를 덮어
 * 씌운다(`InlinePlayerOverlay`). `false`(기본) 면 사이트 플레이어 그대로.
 *
 * JS 브리지(`InlinePlayerBridge`) 콜백마다 읽히므로 DataStore suspend 대신
 * `PlayerBrowserApp.observeNetworkSwitches` 가 미러한다.
 */
object InlinePlayerSwitch {
    @Volatile var enabled: Boolean = false
}
