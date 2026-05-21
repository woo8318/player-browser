package com.playerbrowser.app.ui

object ErrorPage {

    fun build(url: String, code: Int, description: String): String {
        val safeUrl = url.replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        val safeDesc = description.replace("<", "&lt;").replace(">", "&gt;")
        val codeName = nameFor(code)
        val sniBlock = if (isLikelySniBlock(code)) sniBlockHtml() else ""
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
  <a class="btn secondary" href="intent:$safeUrl#Intent;action=android.intent.action.VIEW;end">Chrome 등 외부 앱으로 열기</a>
</div>
</body></html>
        """.trimIndent()
    }

    private fun sniBlockHtml(): String = """
<div class="sni">
  <h2>🚧 한국 통신사의 사이트 차단으로 보입니다</h2>
  <p>이 사이트는 한국 인터넷 사업자가 도메인 단위로 차단했을 가능성이 높습니다. 다음 중 하나를 시도해 보세요.</p>
  <div class="row">
    <a class="btn" href="playerbrowser://private-dns">시스템 Private DNS 설정 열기</a>
    <a class="btn secondary" href="playerbrowser://settings">앱 프록시 설정 열기</a>
  </div>
  <details>
    <summary>설명: 왜 막혔고 어떻게 풀리나요?</summary>
    <p><b>원인.</b> KT·SKT·LGU+ 등 ISP는 사용자가 어떤 도메인에 접속하는지(SNI)를 평문으로 들여다보고, 차단 목록에 있으면 연결을 끊습니다. Chrome은 최신 버전에서 이 신호를 암호화(ECH)해서 우회하지만, 기기에 깔린 WebView 엔진이 더 오래된 경우 풀리지 않습니다.</p>
    <p><b>방법 1 — Private DNS (권장).</b> 안드로이드 설정 → 비공개 DNS → <code>1dot1dot1dot1.cloudflare-dns.com</code> 입력. 이 한 줄이 도메인 조회를 암호화하고, 그 부산물로 ECH 키도 받아와서 SNI까지 가립니다. 한국 SNI 차단 사이트 상당수가 풀립니다.</p>
    <p><b>방법 2 — 앱 프록시.</b> 해외 HTTP 프록시를 거치면 ISP는 프록시 IP만 보게 되어 차단이 무력화됩니다. 신뢰할 수 있는 프록시만 쓰세요.</p>
    <p><b>참고.</b> 통신사·국가의 정책에 따라 일부 사이트는 사이드로드 가능한 어떤 방법으로도 열리지 않을 수 있습니다.</p>
  </details>
</div>
    """.trimIndent()

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
