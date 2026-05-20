package com.playerbrowser.app.ui

object ErrorPage {
    fun build(url: String, code: Int, description: String): String {
        val safeUrl = url.replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        val safeDesc = description.replace("<", "&lt;").replace(">", "&gt;")
        val codeName = nameFor(code)
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
  .code{display:inline-block;padding:2px 8px;border-radius:6px;background:#2c2c2c;color:#ff8a80;font-family:monospace;font-size:13px}
  .url{margin:16px 0;padding:12px;background:#1e1e1e;border-radius:8px;word-break:break-all;font-family:monospace;font-size:13px;color:#90caf9}
  p{color:#bdbdbd;margin:12px 0}
  ul{color:#bdbdbd;padding-left:20px;margin:8px 0 16px}
  .row{display:flex;gap:8px;flex-wrap:wrap;margin-top:24px}
  a.btn{display:inline-block;padding:12px 16px;background:#1565c0;color:#fff;border-radius:8px;text-decoration:none;font-weight:600}
  a.btn.secondary{background:#2c2c2c;color:#e0e0e0}
</style></head><body>
<h1>페이지에 연결할 수 없습니다</h1>
<div><span class="code">$codeName</span></div>
<div class="url">$safeUrl</div>
<p>$safeDesc</p>
<p>가능한 원인:</p>
<ul>
  <li>사이트가 내장 브라우저(WebView)를 차단</li>
  <li>네트워크 상태 일시적 문제</li>
  <li>사이트의 HTTPS/QUIC 핸드셰이크 거부</li>
</ul>
<div class="row">
  <a class="btn" href="$safeUrl">다시 시도</a>
  <a class="btn secondary" href="intent:$safeUrl#Intent;action=android.intent.action.VIEW;end">Chrome 등 외부 앱으로 열기</a>
</div>
</body></html>
        """.trimIndent()
    }

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
