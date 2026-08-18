package com.playerbrowser.app.network

import android.webkit.CookieManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `CookieManager.flush()` 디바운서.
 *
 * 우리가 가로챈 응답의 `Set-Cookie`는 [SniBypassClient] / [IframeScriptInjector]가
 * `CookieManager.setCookie()`로 직접 심는다. 그런데 WebView는 이렇게 들어온
 * 쿠키를 즉시 디스크에 쓰지 않아, 프로세스가 죽으면 `cf_clearance` 같은
 * 캡차 통과 쿠키가 통째로 날아간다 — 앱을 다시 켤 때마다 "사람인지 확인"을
 * 처음부터 다시 해야 하는 이유. flush는 디스크 I/O라 응답마다 부르면 낭비이므로
 * 1.5초 창으로 합쳐서 한 번만 호출한다.
 */
object CookieFlusher {

    private val pending = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "pb-cookie-flush").apply { isDaemon = true }
    }

    /** 쿠키가 바뀌었음을 알린다 — 실제 flush는 최대 1.5초 뒤 한 번. */
    fun schedule() {
        if (!pending.compareAndSet(false, true)) return
        runCatching {
            // Runnable로 명시 — schedule()은 Runnable/Callable 오버로드가 있어
            // 맨 람다는 해석이 모호해질 수 있다.
            executor.schedule(Runnable {
                pending.set(false)
                flushNow()
            }, 1500L, TimeUnit.MILLISECONDS)
        }.onFailure { pending.set(false) }
    }

    /** 즉시 flush (백그라운드 진입 등 프로세스가 죽기 직전 훅에서 사용). */
    fun flushNow() {
        runCatching { CookieManager.getInstance().flush() }
            .onFailure { DebugLog.w("Cookie", "flush 실패", it) }
    }
}
