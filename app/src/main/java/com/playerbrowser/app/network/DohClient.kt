package com.playerbrowser.app.network

import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DNS-over-HTTPS resolver. Uses RFC 8484 wire-format queries (POST
 * `application/dns-message`) so it works with ANY standard DoH endpoint —
 * Cloudflare, Google, Quad9, AdGuard, NextDNS, self-hosted, ... — not just the
 * JSON-API providers. The endpoint is read live from [PrivateDnsSwitch.dohUrl]
 * so changing the provider in settings takes effect without rebuilding clients.
 *
 * Falls back to system DNS if DoH fails, so a flaky endpoint never breaks
 * browsing entirely.
 *
 * **결과를 캐시한다 (v1.3.55).** OkHttp는 *커넥션을 새로 열 때마다* [lookup]을
 * 부르는데, 예전엔 그때마다 DoH POST를 두 번(A/AAAA) 새로 날렸다. 웹툰
 * 페이지처럼 한 CDN 호스트로 이미지가 몰리면(서브리소스 클라이언트는
 * `maxRequestsPerHost=16`) 커넥션 16개 × 2 = DoH 요청 32개가 동시에 터져
 * 리졸버가 밀리고, 밀린 만큼 로드가 늦어지거나 타임아웃 → intercept가 null →
 * WebView가 (단편화 없는) 네이티브 로더로 떨어져 DPI에 막히는 "가끔 한 번에
 * 안 열림"의 주범이었다. 이제:
 *
 *  - **TTL 캐시**: 응답의 실제 DNS TTL을 [MIN_TTL_MS]~[MAX_TTL_MS]로 클램프해 보관.
 *    엔드포인트 URL을 엔트리에 넣어 두므로 설정에서 제공자를 바꾸면 자동 무효화.
 *  - **동시 요청 합류(in-flight dedup)**: 같은 호스트를 동시에 찾는 스레드는
 *    호스트별 락에서 대기했다가 첫 스레드의 결과를 그대로 쓴다 — 위의 32-요청
 *    폭주가 1회 조회로 접힌다.
 *  - **공유 HTTP 클라이언트**: 예전엔 `DohClient()`를 만드는 6곳이 각자
 *    OkHttpClient(=각자 커넥션 풀)를 들어 리졸버로 TLS 핸드셰이크를 계속 새로
 *    했다. 이제 한 벌을 공유해 keep-alive가 산다.
 *  - **빠른 실패**: 타임아웃을 3s로 줄이고, A 질의가 실패하면 AAAA는 건너뛴다
 *    (엔드포인트가 죽은 상태) — 죽은 DoH에서 20초를 버리지 않고 ~3초 만에
 *    시스템 DNS로 폴백. 폴백 결과도 짧게 캐시해 매 커넥션마다 재시도하지 않음.
 */
class DohClient(
    private val httpClient: OkHttpClient = defaultClient(),
    private val endpoint: () -> String = { PrivateDnsSwitch.dohUrl }
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val url = endpoint().trim().ifBlank { DEFAULT_URL }
        cached(hostname, url)?.let { return it }

        // 같은 호스트를 동시에 찾는 스레드들을 한 번의 조회로 합류시킨다.
        val lock = locks.getOrPut(hostname) { Any() }
        return try {
            synchronized(lock) {
                // 대기하는 동안 다른 스레드가 이미 채웠을 수 있다.
                val filled = cached(hostname, url)
                if (filled != null) {
                    filled
                } else {
                    val doh = runCatching { resolve(url, hostname) }.getOrNull().orEmpty()
                    if (doh.isNotEmpty()) {
                        doh
                    } else {
                        // DoH 실패 — 시스템 DNS 폴백(UnknownHostException은 OkHttp가
                        // 기대하는 신호라 그대로 전파). 결과를 짧게 캐시해 커넥션마다
                        // 죽은 엔드포인트를 다시 두드리지 않게 한다.
                        store(hostname, url, Dns.SYSTEM.lookup(hostname), FALLBACK_TTL_MS)
                    }
                }
            }
        } finally {
            locks.remove(hostname, lock)
        }
    }

    /** DoH로 A(+AAAA)를 조회해 캐시에 넣고 돌려준다. 실패/빈 결과면 빈 리스트. */
    private fun resolve(url: String, hostname: String): List<InetAddress> {
        // A가 통째로 실패하면 엔드포인트가 죽은 것 — AAAA로 3초 더 버리지 않는다.
        val a = runCatching { query(url, hostname, TYPE_A) }.getOrNull() ?: return emptyList()
        // A가 비어 있어도(AAAA 전용 호스트) AAAA는 시도한다.
        val aaaa = runCatching { query(url, hostname, TYPE_AAAA) }.getOrNull()

        val addresses = ArrayList<InetAddress>(a.addresses.size + (aaaa?.addresses?.size ?: 0))
        addresses += a.addresses
        aaaa?.let { addresses += it.addresses }
        if (addresses.isEmpty()) return emptyList()

        val ttlSec = listOfNotNull(
            a.ttlSec.takeIf { a.addresses.isNotEmpty() },
            aaaa?.ttlSec?.takeIf { aaaa.addresses.isNotEmpty() }
        ).minOrNull() ?: 0L
        val ttlMs = (ttlSec * 1000L).coerceIn(MIN_TTL_MS, MAX_TTL_MS)
        return store(hostname, url, addresses, ttlMs)
    }

    private fun query(endpointUrl: String, hostname: String, type: Int): Answer {
        val req = Request.Builder()
            .url(endpointUrl)
            .header("accept", DNS_MESSAGE_TYPE)
            .post(buildQuery(hostname, type).toRequestBody(DNS_MESSAGE))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return EMPTY_ANSWER
            val bytes = resp.body?.bytes() ?: return EMPTY_ANSWER
            return parseAnswers(bytes, type)
        }
    }

    /** Builds a minimal RFC 1035 query message for a single name + record type. */
    private fun buildQuery(hostname: String, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { dos ->
            dos.writeShort(0)        // ID (0 is fine for DoH — no UDP spoofing surface)
            dos.writeShort(0x0100)   // flags: RD=1 (recursion desired)
            dos.writeShort(1)        // QDCOUNT
            dos.writeShort(0)        // ANCOUNT
            dos.writeShort(0)        // NSCOUNT
            dos.writeShort(0)        // ARCOUNT
            for (label in hostname.split('.')) {
                if (label.isEmpty()) continue
                val bytes = label.toByteArray(Charsets.US_ASCII)
                dos.writeByte(bytes.size)
                dos.write(bytes)
            }
            dos.writeByte(0)         // root label terminator
            dos.writeShort(type)     // QTYPE
            dos.writeShort(1)        // QCLASS = IN
        }
        return out.toByteArray()
    }

    /**
     * Extracts A/AAAA addresses of [wantType] from a wire-format response,
     * plus the smallest record TTL (seconds) so the cache can honour it.
     */
    private fun parseAnswers(bytes: ByteArray, wantType: Int): Answer {
        val buf = ByteBuffer.wrap(bytes) // big-endian by default (network order)
        if (buf.remaining() < 12) return EMPTY_ANSWER
        buf.short                                       // ID
        buf.short                                       // flags
        val questionCount = buf.short.toInt() and 0xFFFF
        val answerCount = buf.short.toInt() and 0xFFFF
        buf.short                                       // NSCOUNT
        buf.short                                       // ARCOUNT
        repeat(questionCount) {
            skipName(buf)
            if (buf.remaining() < 4) return EMPTY_ANSWER
            buf.short                                   // QTYPE
            buf.short                                   // QCLASS
        }
        val out = ArrayList<InetAddress>()
        var minTtl = Long.MAX_VALUE
        repeat(answerCount) {
            if (buf.remaining() < 1) return Answer(out, ttlOf(minTtl))
            skipName(buf)
            if (buf.remaining() < 10) return Answer(out, ttlOf(minTtl))
            val type = buf.short.toInt() and 0xFFFF
            buf.short                                   // CLASS
            val ttl = buf.int.toLong() and 0xFFFFFFFFL
            val rdLength = buf.short.toInt() and 0xFFFF
            if (buf.remaining() < rdLength) return Answer(out, ttlOf(minTtl))
            val familyOk = (wantType == TYPE_A && rdLength == 4) ||
                (wantType == TYPE_AAAA && rdLength == 16)
            if (type == wantType && familyOk) {
                val addr = ByteArray(rdLength)
                buf.get(addr)
                runCatching { InetAddress.getByAddress(addr) }.getOrNull()?.let {
                    out += it
                    if (ttl < minTtl) minTtl = ttl
                }
            } else {
                buf.position(buf.position() + rdLength) // skip CNAME/other rdata
            }
        }
        return Answer(out, ttlOf(minTtl))
    }

    private fun ttlOf(minTtl: Long): Long = if (minTtl == Long.MAX_VALUE) 0L else minTtl

    /** Advances past a (possibly compressed) DNS name without resolving it. */
    private fun skipName(buf: ByteBuffer) {
        while (buf.remaining() >= 1) {
            val len = buf.get().toInt() and 0xFF
            if (len == 0) return
            if (len and 0xC0 == 0xC0) {
                if (buf.remaining() >= 1) buf.get()     // consume 2nd pointer byte; name ends
                return
            }
            if (buf.remaining() < len) return
            buf.position(buf.position() + len)
        }
    }

    private class Answer(val addresses: List<InetAddress>, val ttlSec: Long)

    companion object {
        const val DEFAULT_URL = "https://cloudflare-dns.com/dns-query"
        private const val TYPE_A = 1
        private const val TYPE_AAAA = 28
        private const val DNS_MESSAGE_TYPE = "application/dns-message"
        private val DNS_MESSAGE = DNS_MESSAGE_TYPE.toMediaType()
        private val EMPTY_ANSWER = Answer(emptyList(), 0L)

        /** 서버 TTL이 아주 짧아도 이 아래로는 안 내려간다(재조회 폭주 방지). */
        private const val MIN_TTL_MS = 60_000L
        /** 반대로 아주 길어도 이 위로는 안 올라간다(주소 변경 추종). */
        private const val MAX_TTL_MS = 10 * 60_000L
        /** DoH가 죽어 시스템 DNS로 폴백했을 때의 짧은 캐시. */
        private const val FALLBACK_TTL_MS = 60_000L
        private const val MAX_ENTRIES = 256

        private class Entry(
            val endpoint: String,
            val addresses: List<InetAddress>,
            val expiresAt: Long
        )

        /** 모든 DohClient 인스턴스가 공유 — 6곳에서 각자 만들어도 조회는 한 벌. */
        private val cache = ConcurrentHashMap<String, Entry>()
        private val locks = ConcurrentHashMap<String, Any>()

        private fun cached(hostname: String, endpoint: String): List<InetAddress>? {
            val e = cache[hostname] ?: return null
            if (e.endpoint != endpoint || System.currentTimeMillis() >= e.expiresAt) {
                cache.remove(hostname, e)
                return null
            }
            return e.addresses
        }

        private fun store(
            hostname: String,
            endpoint: String,
            addresses: List<InetAddress>,
            ttlMs: Long
        ): List<InetAddress> {
            if (addresses.isEmpty()) return addresses
            // 오래 돌아도 무한히 자라지 않게 — 넘치면 비운다(캐시라 손실 무해).
            if (cache.size > MAX_ENTRIES) cache.clear()
            cache[hostname] = Entry(endpoint, addresses, System.currentTimeMillis() + ttlMs)
            return addresses
        }

        /** 제공자 변경은 엔드포인트 비교로 자동 무효화되지만, 명시 초기화도 제공. */
        fun clearCache() {
            cache.clear()
        }

        // 리졸버로의 커넥션을 재사용하도록 한 벌만 만들어 공유한다. 타임아웃이
        // 짧은 이유: DNS는 connect 이전 단계라 OkHttp의 connectTimeout(6s)이
        // 걸리지 않는다 — 여기서 오래 끌면 페이지 로드가 그만큼 통째로 멈춘다.
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
                .retryOnConnectionFailure(true)
                .build()
        }

        fun defaultClient(): OkHttpClient = sharedClient
    }
}
