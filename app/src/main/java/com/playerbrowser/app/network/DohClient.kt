package com.playerbrowser.app.network

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.ByteBuffer
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
 */
class DohClient(
    private val httpClient: OkHttpClient = defaultClient(),
    private val endpoint: () -> String = { PrivateDnsSwitch.dohUrl }
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> =
        runCatching { resolve(hostname) }
            .getOrElse { Dns.SYSTEM.lookup(hostname) }
            .ifEmpty { Dns.SYSTEM.lookup(hostname) }

    private fun resolve(hostname: String): List<InetAddress> {
        val url = endpoint().trim().ifBlank { DEFAULT_URL }
        val results = ArrayList<InetAddress>()
        results += query(url, hostname, TYPE_A)
        results += query(url, hostname, TYPE_AAAA)
        if (results.isEmpty()) throw UnknownHostException(hostname)
        return results
    }

    private fun query(endpointUrl: String, hostname: String, type: Int): List<InetAddress> {
        val req = Request.Builder()
            .url(endpointUrl)
            .header("accept", DNS_MESSAGE_TYPE)
            .post(buildQuery(hostname, type).toRequestBody(DNS_MESSAGE))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val bytes = resp.body?.bytes() ?: return emptyList()
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

    /** Extracts A/AAAA addresses of [wantType] from a wire-format response. */
    private fun parseAnswers(bytes: ByteArray, wantType: Int): List<InetAddress> {
        val buf = ByteBuffer.wrap(bytes) // big-endian by default (network order)
        if (buf.remaining() < 12) return emptyList()
        buf.short                                       // ID
        buf.short                                       // flags
        val questionCount = buf.short.toInt() and 0xFFFF
        val answerCount = buf.short.toInt() and 0xFFFF
        buf.short                                       // NSCOUNT
        buf.short                                       // ARCOUNT
        repeat(questionCount) {
            skipName(buf)
            if (buf.remaining() < 4) return emptyList()
            buf.short                                   // QTYPE
            buf.short                                   // QCLASS
        }
        val out = ArrayList<InetAddress>()
        repeat(answerCount) {
            if (buf.remaining() < 1) return out
            skipName(buf)
            if (buf.remaining() < 10) return out
            val type = buf.short.toInt() and 0xFFFF
            buf.short                                   // CLASS
            buf.int                                     // TTL
            val rdLength = buf.short.toInt() and 0xFFFF
            if (buf.remaining() < rdLength) return out
            val familyOk = (wantType == TYPE_A && rdLength == 4) ||
                (wantType == TYPE_AAAA && rdLength == 16)
            if (type == wantType && familyOk) {
                val addr = ByteArray(rdLength)
                buf.get(addr)
                runCatching { InetAddress.getByAddress(addr) }.getOrNull()?.let { out += it }
            } else {
                buf.position(buf.position() + rdLength) // skip CNAME/other rdata
            }
        }
        return out
    }

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

    companion object {
        const val DEFAULT_URL = "https://cloudflare-dns.com/dns-query"
        private const val TYPE_A = 1
        private const val TYPE_AAAA = 28
        private const val DNS_MESSAGE_TYPE = "application/dns-message"
        private val DNS_MESSAGE = DNS_MESSAGE_TYPE.toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
