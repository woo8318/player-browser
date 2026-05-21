package com.playerbrowser.app.network

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * DNS-over-HTTPS resolver using Cloudflare 1.1.1.1. Bypasses ISP DNS.
 *
 * Falls back to system DNS if DoH fails, so a flaky DoH endpoint never breaks
 * browsing entirely.
 */
class DohClient(
    private val httpClient: OkHttpClient = defaultClient()
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        return runCatching { resolve(hostname) }
            .getOrElse { Dns.SYSTEM.lookup(hostname) }
            .ifEmpty { Dns.SYSTEM.lookup(hostname) }
    }

    private fun resolve(hostname: String): List<InetAddress> {
        val results = mutableListOf<InetAddress>()
        results += query(hostname, type = 1)  // A
        results += query(hostname, type = 28) // AAAA
        if (results.isEmpty()) throw UnknownHostException(hostname)
        return results
    }

    private fun query(hostname: String, type: Int): List<InetAddress> {
        val url = "https://cloudflare-dns.com/dns-query?name=$hostname&type=$type"
        val req = Request.Builder()
            .url(url)
            .header("accept", "application/dns-json")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val answers = json.optJSONArray("Answer") ?: return emptyList()
            val out = mutableListOf<InetAddress>()
            for (i in 0 until answers.length()) {
                val ans = answers.optJSONObject(i) ?: continue
                if (ans.optInt("type") != type) continue
                val data = ans.optString("data").orEmpty()
                if (data.isBlank()) continue
                runCatching { InetAddress.getByName(data) }.getOrNull()?.let { out += it }
            }
            return out
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
