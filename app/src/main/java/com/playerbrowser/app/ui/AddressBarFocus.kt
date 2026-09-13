package com.playerbrowser.app.ui

/**
 * 주소창(Compose OutlinedTextField)이 포커스를 쥐고 있는지 — [focusPageUnlessTyping] 이
 * 페이지에 View 포커스를 줄 때 입력 중인 주소창을 뺏지 않기 위한 플래그. Compose 의
 * AndroidComposeView 는 onCheckIsTextEditor 를 신뢰할 수 없어 직접 기록한다 (v1.3.84).
 */
internal object AddressBarFocus {
    @Volatile
    var typing: Boolean = false
}
