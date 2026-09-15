package com.playerbrowser.app.network

/**
 * "스트림 내용 감지 (실험)" 설정의 volatile 미러. `true`(기본) 면 페이지의
 * fetch/XHR 응답 앞부분을 JS 가 들여다봐 재생목록/mp4 를 알아내고
 * `PBPlayer.onStreamBody` 로 보고한다(v1.3.91). `false` 면 래퍼를 설치하지
 * 않고 브리지도 보고를 버린다 — 로그 없이 켜고 끄며 비교하기 위한 토글.
 *
 * JS 브리지 콜백마다 읽히므로 DataStore suspend 대신
 * `PlayerBrowserApp.observeNetworkSwitches` 가 미러한다.
 */
object BodySniffSwitch {
    @Volatile var enabled: Boolean = true
}
