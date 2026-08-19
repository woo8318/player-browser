package com.playerbrowser.app.network

/**
 * `BrowserEnvPatch` 의 JS 환경 정규화를 켜고 끄는 스위치 (v1.3.67).
 *
 * v1.3.58~63 동안 WebView 표식을 **지우는** 방향으로만 계속 더했고
 * (UA / UA-CH / window.chrome / Notification / 결손 API 9종 /
 * `Function.prototype.toString` 마스킹), 진단은 마침내 `없음=[-]` 를
 * 찍었는데도 Cloudflare 챌린지 루프는 그대로였다. 그렇다면 **더한 것이
 * 오히려 해가 되고 있는지**를 한 번도 확인하지 않았다는 뜻이다 —
 * `Function.prototype.toString` 이 네이티브가 아닌 함수로 교체된 것 자체가
 * 자동화 신호이고, 다른 realm(새 iframe)의 원본과 비교하면 바로 드러난다.
 *
 * 그래서 껐다 켜며 **직접 비교할 수 있게** 한다. 기본은 켜짐(종전 동작).
 * WebView 생성 시점에 한 번 읽히므로 토글 후에는 새 탭이나 앱 재시작이
 * 필요하다.
 */
object EnvSpoofSwitch {
    @Volatile
    var enabled: Boolean = true
}
