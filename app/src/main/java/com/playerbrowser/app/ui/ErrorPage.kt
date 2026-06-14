package com.playerbrowser.app.ui

object ErrorPage {

    fun build(url: String, code: Int, description: String): String {
        val safeUrl = url.replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        val safeDesc = description.replace("<", "&lt;").replace(">", "&gt;")
        val codeName = nameFor(code)
        val sniBlock = if (isLikelySniBlock(code)) sniBlockHtml() else ""
        // "외부 앱으로 열기"는 url을 intent: 스킴 문자열에 그대로 끼워 넣는다.
        // url에 '#'/';' 등이 있으면 임의 intent 주입이 가능하므로, 그런 문자가 없는
        // 평범한 http(s) URL일 때만 버튼을 노출한다.
        val openExternal = if (isPlainHttpUrl(url)) {
            """<a class="btn secondary" href="intent:$safeUrl#Intent;action=android.intent.action.VIEW;end">Chrome 등 외부 앱으로 열기</a>"""
        } else ""
        return """
<!doctype html>
<html lang="ko"><head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1" />
<title>연결 실패</title>
<style>
  body{margin:0;padding:32px 24px;font:16px/1.5 -apple-system,BlinkMacSystemFont,'Apple SD Gothic Neo',sans-serif;
       background:#121212;color:#e0e0e0}
  h1{font-size:22px;margin:0 0 8px}
  h2{font-size:18px;margin:0 0 8px}
  .code{display:inline-block;padding:2px 8px;border-radius:6px;background:#2c2c2c;color:#ff8a80;font-family:monospace;font-size:13px}
  .url{margin:16px 0;padding:12px;background:#1e1e1e;border-radius:8px;word-break:break-all;font-family:monospace;font-size:13px;color:#90caf9}
  p{color:#bdbdbd;margin:12px 0}
  ul{color:#bdbdbd;padding-left:20px;margin:8px 0 16px}
  code{background:#2c2c2c;padding:1px 6px;border-radius:4px;font-size:13px;color:#ffe082}
  .row{display:flex;gap:8px;flex-wrap:wrap;margin-top:16px}
  a.btn{display:inline-block;padding:12px 16px;background:#1565c0;color:#fff;border-radius:8px;text-decoration:none;font-weight:600}
  a.btn.secondary{background:#2c2c2c;color:#e0e0e0}
  .sni{margin-top:24px;padding:16px;background:#1e1e1e;border-radius:10px;border-left:4px solid #ffb74d}
  .sni h2{color:#ffb74d}
  details{margin-top:12px;color:#9e9e9e;font-size:14px}
  summary{cursor:pointer;color:#90caf9}
</style></head><body>
<h1>페이지에 연결할 수 없습니다</h1>
<div><span class="code">$codeName</span></div>
<div class="url">$safeUrl</div>
<p>$safeDesc</p>
$sniBlock
<div class="row">
  <a class="btn" href="$safeUrl">다시 시도</a>
  $openExternal
</div>
</body></html>
        """.trimIndent()
    }

    /** 숫자 증감 후보로 살아있는 주소를 찾는 동안 잠깐 보여 주는 대기 화면. */
    fun probing(url: String): String {
        val safeUrl = url.replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        return """
<!doctype html>
<html lang="ko"><head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1" />
<title>대체 주소 확인 중</title>
<style>
  body{margin:0;padding:48px 24px;font:16px/1.5 -apple-system,BlinkMacSystemFont,'Apple SD Gothic Neo',sans-serif;
       background:#121212;color:#e0e0e0;text-align:center}
  h1{font-size:20px;margin:24px 0 8px}
  p{color:#bdbdbd;margin:8px 0}
  .url{margin:16px auto;max-width:90%;padding:12px;background:#1e1e1e;border-radius:8px;word-break:break-all;font-family:monospace;font-size:13px;color:#90caf9}
  .spinner{width:44px;height:44px;margin:0 auto;border:4px solid #2c2c2c;border-top-color:#1565c0;border-radius:50%;animation:spin 0.9s linear infinite}
  @keyframes spin{to{transform:rotate(360deg)}}
</style></head><body>
<div class="spinner"></div>
<h1>접속이 안 돼 대체 주소를 찾는 중…</h1>
<p>URL의 숫자가 바뀐 사이트일 수 있어 다음 번호들을 확인하고 있어요.</p>
<div class="url">$safeUrl</div>
</body></html>
        """.trimIndent()
    }

    private fun sniBlockHtml(): String = """
<div class="sni">
  <h2>🚧 한국 통신사의 사이트 차단으로 보입니다</h2>
  <p>이 사이트는 한국 인터넷 사업자가 도메인 단위로 차단했을 가능성이 높습니다. 효과 큰 순서대로:</p>

  <p><b>① 가장 확실 — Cloudflare 1.1.1.1 + WARP 설치 (무료, 1분).</b><br/>
  시스템 VPN처럼 동작하며 모든 앱의 차단을 한꺼번에 우회합니다.</p>
  <div class="row">
    <a class="btn" href="playerbrowser://install-warp">Play 스토어에서 WARP 설치</a>
  </div>

  <p style="margin-top:20px"><b>② 차선 — 앱 내 프록시.</b><br/>
  본인이 소유한 해외 프록시 서버가 있다면 사용하세요.</p>
  <div class="row">
    <a class="btn secondary" href="playerbrowser://settings">앱 프록시 설정 열기</a>
  </div>

  <p style="margin-top:20px"><b>③ 가벼운 시도 — WebView 업데이트 + Private DNS.</b><br/>
  일부 사이트에는 효과가 있지만 한국의 SNI 차단 대부분에는 부족합니다.</p>
  <div class="row">
    <a class="btn secondary" href="playerbrowser://update-webview">WebView 업데이트</a>
    <a class="btn secondary" href="playerbrowser://private-dns">Private DNS 설정 열기</a>
  </div>

  <details>
    <summary>왜 막히는지 / 왜 Private DNS만으로는 부족한지</summary>
    <p><b>차단 원리.</b> KT·SKT·LGU+ 등은 사용자가 어떤 도메인에 접속하는지(TLS의 SNI 필드)를 평문 그대로 들여다보고, 차단 목록에 있으면 연결을 끊습니다. DNS 조회 단계가 아니라 TLS 핸드셰이크 단계에서 일어나는 일입니다.</p>
    <p><b>그래서 Private DNS만으로는 보통 부족합니다.</b> DoH(Private DNS)는 "어떤 IP인지 묻는 과정"만 가립니다. 실제 연결 시 SNI가 평문으로 나가는 건 그대로라서 ISP는 여전히 봅니다. ECH가 추가로 활성화돼야 SNI까지 가려지는데, 기기에 깔린 WebView 엔진 버전·사이트의 CDN 지원 여부에 따라 갈립니다.</p>
    <p><b>그래서 VPN/프록시가 확실합니다.</b> 트래픽 자체가 다른 IP로 빠지면 ISP는 SNI를 볼 기회조차 없습니다. WARP는 Cloudflare가 무료로 운영하는 서비스로, 시스템 VPN 권한만 한 번 허용하면 끝납니다.</p>
    <p><b>참고.</b> 통신사·국가 정책에 따라 어떤 방법으로도 안 풀리는 사이트가 있을 수 있습니다.</p>
  </details>
</div>
    """.trimIndent()

    // intent:/href 컨텍스트를 깨뜨릴 수 있는 문자(#, ;, 따옴표, 공백, <>, \)가 없는
    // 평범한 http(s) URL인지. 외부 앱 링크 노출 가드용.
    private val PLAIN_HTTP_URL = Regex("^https?://[^\\s\"'<>#;\\\\]+$", RegexOption.IGNORE_CASE)

    private fun isPlainHttpUrl(url: String): Boolean = PLAIN_HTTP_URL.matches(url)

    fun isLikelySniBlock(code: Int): Boolean = code in SNI_BLOCK_CODES

    // -6 ERR_CONNECT, -8 ERR_TIMEOUT, -11 ERR_FAILED_SSL_HANDSHAKE,
    // -2 ERR_HOST_LOOKUP (DNS-based block), -16 (custom CONNECTION_RESET label).
    // Some OEM WebView builds also surface -1 (UNKNOWN) for SNI RST.
    private val SNI_BLOCK_CODES = setOf(-1, -2, -6, -8, -11, -16)

    private fun nameFor(code: Int): String = when (code) {
        -1 -> "ERR_UNKNOWN ($code)"
        -2 -> "ERR_HOST_LOOKUP ($code)"
        -3 -> "ERR_UNSUPPORTED_AUTH_SCHEME ($code)"
        -4 -> "ERR_AUTHENTICATION ($code)"
        -5 -> "ERR_PROXY_AUTHENTICATION ($code)"
        -6 -> "ERR_CONNECT ($code)"
        -7 -> "ERR_IO ($code)"
        -8 -> "ERR_TIMEOUT ($code)"
        -9 -> "ERR_REDIRECT_LOOP ($code)"
        -10 -> "ERR_UNSUPPORTED_SCHEME ($code)"
        -11 -> "ERR_FAILED_SSL_HANDSHAKE ($code)"
        -12 -> "ERR_BAD_URL ($code)"
        -13 -> "ERR_FILE ($code)"
        -14 -> "ERR_FILE_NOT_FOUND ($code)"
        -15 -> "ERR_TOO_MANY_REQUESTS ($code)"
        -16 -> "ERR_CONNECTION_RESET ($code)"
        else -> "ERR ($code)"
    }
}
