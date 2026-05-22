package com.playerbrowser.app.network

import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

private const val TAG = "SniBypass"

/**
 * Wraps a plain SocketFactory so the very first OutputStream.write() — which
 * for an HTTPS connection carries the TLS ClientHello — is split into
 * multiple TCP segments designed to defeat Korean-ISP SNI inspection.
 *
 * Two strategies, applied in order:
 *
 *  1. SNI-aware split. If the buffer looks like a TLS ClientHello and we can
 *     locate the server_name extension's host_name bytes, we split inside
 *     the host_name string. Reassembling DPI engines often parse the SNI
 *     extension from the first TCP segment only; if the host_name length
 *     prefix lies in segment 1 but the bytes it points at lie in segment 2,
 *     SNI parsing fails on the first inspection pass and the match is missed.
 *
 *  2. Fallback record-header split (legacy behavior). If parsing fails for
 *     any reason, we still send the 5-byte TLS record header on its own,
 *     which defeats the older single-packet substring matchers.
 *
 * Still alpha-quality. Some ISPs/middleboxes do full streaming reassembly
 * with deeper state tracking and will see the SNI either way; those
 * connections will need a real VPN (e.g. Cloudflare WARP).
 */
class FragmentingSocketFactory(
    private val delegate: SocketFactory = getDefault()
) : SocketFactory() {

    override fun createSocket(): Socket = wrap(delegate.createSocket())
    override fun createSocket(host: String, port: Int): Socket =
        wrap(delegate.createSocket(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        wrap(delegate.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket =
        wrap(delegate.createSocket(host, port))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        wrap(delegate.createSocket(address, port, localAddress, localPort))

    private fun wrap(socket: Socket): Socket = FragmentingSocket(socket)
}

private class FragmentingSocket(private val inner: Socket) : Socket() {
    private var firstWriteDone = false

    override fun connect(endpoint: java.net.SocketAddress?) = inner.connect(endpoint)
    override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) =
        inner.connect(endpoint, timeout)
    override fun bind(bindpoint: java.net.SocketAddress?) = inner.bind(bindpoint)
    override fun getInetAddress(): InetAddress? = inner.inetAddress
    override fun getLocalAddress(): InetAddress = inner.localAddress
    override fun getPort(): Int = inner.port
    override fun getLocalPort(): Int = inner.localPort
    override fun getRemoteSocketAddress(): java.net.SocketAddress? = inner.remoteSocketAddress
    override fun getLocalSocketAddress(): java.net.SocketAddress? = inner.localSocketAddress
    override fun getChannel(): java.nio.channels.SocketChannel? = inner.channel
    override fun getInputStream(): java.io.InputStream = inner.getInputStream()
    override fun getOutputStream(): OutputStream {
        // Disable Nagle so our hand-fragmented writes actually leave the host
        // as separate TCP segments instead of being coalesced by the kernel
        // into a single packet (which would defeat the whole point).
        runCatching { inner.tcpNoDelay = true }
        val out = inner.getOutputStream()
        DebugLog.d(TAG, "getOutputStream: wrapping ${inner.javaClass.simpleName} -> ${inner.remoteSocketAddress}")
        return FragmentingOutputStream(out) { firstWriteDone }.also {
            it.onFirstWriteDone = { firstWriteDone = true }
        }
    }
    override fun setTcpNoDelay(on: Boolean) { inner.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = inner.tcpNoDelay
    override fun setSoLinger(on: Boolean, linger: Int) { inner.setSoLinger(on, linger) }
    override fun getSoLinger(): Int = inner.soLinger
    override fun sendUrgentData(data: Int) = inner.sendUrgentData(data)
    override fun setOOBInline(on: Boolean) { inner.oobInline = on }
    override fun getOOBInline(): Boolean = inner.oobInline
    override fun setSoTimeout(timeout: Int) { inner.soTimeout = timeout }
    override fun getSoTimeout(): Int = inner.soTimeout
    override fun setSendBufferSize(size: Int) { inner.sendBufferSize = size }
    override fun getSendBufferSize(): Int = inner.sendBufferSize
    override fun setReceiveBufferSize(size: Int) { inner.receiveBufferSize = size }
    override fun getReceiveBufferSize(): Int = inner.receiveBufferSize
    override fun setKeepAlive(on: Boolean) { inner.keepAlive = on }
    override fun getKeepAlive(): Boolean = inner.keepAlive
    override fun setTrafficClass(tc: Int) { inner.trafficClass = tc }
    override fun getTrafficClass(): Int = inner.trafficClass
    override fun setReuseAddress(on: Boolean) { inner.reuseAddress = on }
    override fun getReuseAddress(): Boolean = inner.reuseAddress
    override fun close() = inner.close()
    override fun shutdownInput() = inner.shutdownInput()
    override fun shutdownOutput() = inner.shutdownOutput()
    override fun isConnected(): Boolean = inner.isConnected
    override fun isBound(): Boolean = inner.isBound
    override fun isClosed(): Boolean = inner.isClosed
    override fun isInputShutdown(): Boolean = inner.isInputShutdown
    override fun isOutputShutdown(): Boolean = inner.isOutputShutdown
    override fun toString(): String = inner.toString()
}

private class FragmentingOutputStream(
    private val delegate: OutputStream,
    private val firstWriteCheck: () -> Boolean
) : OutputStream() {
    var onFirstWriteDone: () -> Unit = {}

    override fun write(b: Int) {
        delegate.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (firstWriteCheck() || len < 6) {
            delegate.write(b, off, len)
            return
        }

        if (runCatching { writeFragmentedClientHello(b, off, len) }.getOrNull() == true) {
            onFirstWriteDone()
            return
        }

        // Couldn't split at TLS layer (not a ClientHello, malformed, or write
        // failed). Try TCP-layer split inside the SNI hostname bytes.
        val sniRange = runCatching { findSniHostnameRange(b, off, len) }.getOrNull()
        DebugLog.d(TAG, "FragOut.write: len=$len sniRange=$sniRange (tcp-layer)")
        if (sniRange != null && (sniRange.last - sniRange.first) >= 4) {
            val firstCut = sniRange.first + 1
            val secondCut = sniRange.first + (sniRange.last - sniRange.first) / 2 + 1
            val segments = listOf(
                off to firstCut,
                firstCut to secondCut,
                secondCut to (off + len)
            ).filter { it.second > it.first }
            for ((start, end) in segments) {
                delegate.write(b, start, end - start)
                delegate.flush()
            }
            DebugLog.d(TAG, "FragOut.write: TCP-layer SNI split into ${segments.size} segments")
            onFirstWriteDone()
            return
        }

        // Fallback: 5-byte TLS record header in its own segment.
        delegate.write(b, off, 5)
        delegate.flush()
        delegate.write(b, off + 5, len - 5)
        delegate.flush()
        DebugLog.d(TAG, "FragOut.write: fallback 5-byte header split")
        onFirstWriteDone()
    }

    /**
     * Splits a ClientHello at the TLS record layer into two records: the first
     * carries just the handshake type byte (0x01), the second carries the rest
     * of the handshake. RFC 5246 §6.2.1 explicitly allows handshake messages
     * to span multiple records — Cloudflare and all major servers handle this
     * fine. The win: a DPI engine that locates ClientHello by parsing TLS
     * records will not find a complete handshake header in record 1, and
     * record 2 alone has no record-prefixed handshake header to anchor on.
     * A 60ms gap between records also defeats engines with a fixed
     * reassembly time window.
     *
     * Returns true on a successful split write, false if the buffer doesn't
     * look like a single fragmentable ClientHello.
     */
    private fun writeFragmentedClientHello(b: ByteArray, off: Int, len: Int): Boolean {
        if (len < 7) return false
        val type = b[off].toInt() and 0xFF
        val verMajor = b[off + 1].toInt() and 0xFF
        val verMinor = b[off + 2].toInt() and 0xFF
        if (type != 0x16 || verMajor != 0x03) return false
        val recordLen = ((b[off + 3].toInt() and 0xFF) shl 8) or (b[off + 4].toInt() and 0xFF)
        if (recordLen < 5 || 5 + recordLen > len) return false
        // First byte of record payload must be the ClientHello handshake type.
        if ((b[off + 5].toInt() and 0xFF) != 0x01) return false

        val splitAt = 1 // bytes from record payload that go in record A
        val recordA = ByteArray(5 + splitAt).also {
            it[0] = 0x16
            it[1] = verMajor.toByte()
            it[2] = verMinor.toByte()
            it[3] = ((splitAt ushr 8) and 0xFF).toByte()
            it[4] = (splitAt and 0xFF).toByte()
            System.arraycopy(b, off + 5, it, 5, splitAt)
        }
        val bLen = recordLen - splitAt
        val recordB = ByteArray(5 + bLen).also {
            it[0] = 0x16
            it[1] = verMajor.toByte()
            it[2] = verMinor.toByte()
            it[3] = ((bLen ushr 8) and 0xFF).toByte()
            it[4] = (bLen and 0xFF).toByte()
            System.arraycopy(b, off + 5 + splitAt, it, 5, bLen)
        }

        delegate.write(recordA)
        delegate.flush()
        runCatching { Thread.sleep(60) }
        delegate.write(recordB)
        delegate.flush()

        // If the caller batched more bytes after the first TLS record (rare on
        // a brand-new socket but possible), forward them as-is.
        val tail = off + 5 + recordLen
        val tailLen = (off + len) - tail
        if (tailLen > 0) {
            delegate.write(b, tail, tailLen)
            delegate.flush()
        }

        DebugLog.d(
            TAG,
            "FragOut.write: TLS record split (A=${recordA.size}B, B=${recordB.size}B, gap=60ms, tail=${tailLen}B)"
        )
        return true
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()

    /**
     * Locates the absolute offsets `[start, end)` of the SNI `host_name` bytes
     * inside a buffer that begins with a TLS Handshake record containing a
     * ClientHello. Returns null if the buffer doesn't parse or no SNI
     * extension is present.
     */
    private fun findSniHostnameRange(b: ByteArray, off: Int, len: Int): IntRange? {
        val end = off + len
        var p = off
        if (end - p < 43) return null

        // TLS record header: type=0x16, version major=0x03, version minor, length(2)
        if ((b[p].toInt() and 0xFF) != 0x16) return null
        if ((b[p + 1].toInt() and 0xFF) != 0x03) return null
        val recordLen = u16(b, p + 3)
        if (recordLen > end - (p + 5)) return null
        p += 5

        // Handshake header: type=0x01 (client_hello), length(3)
        if ((b[p].toInt() and 0xFF) != 0x01) return null
        p += 4

        // ClientHello body
        if (end - p < 34) return null
        p += 2 // legacy_version
        p += 32 // random

        if (p >= end) return null
        val sidLen = b[p].toInt() and 0xFF
        p += 1 + sidLen
        if (p + 2 > end) return null

        val csLen = u16(b, p)
        p += 2 + csLen
        if (p >= end) return null

        val cmLen = b[p].toInt() and 0xFF
        p += 1 + cmLen
        if (p + 2 > end) return null

        val extsLen = u16(b, p)
        p += 2
        val extsEnd = p + extsLen
        if (extsEnd > end) return null

        while (p + 4 <= extsEnd) {
            val extType = u16(b, p)
            val extLen = u16(b, p + 2)
            val dataStart = p + 4
            val dataEnd = dataStart + extLen
            if (dataEnd > extsEnd) return null

            if (extType == 0x0000) {
                // server_name_list_length(2) + name_type(1) + host_name_length(2) + host_name
                if (dataStart + 5 > dataEnd) return null
                val nameType = b[dataStart + 2].toInt() and 0xFF
                if (nameType != 0x00) return null
                val hostnameLen = u16(b, dataStart + 3)
                val hostnameStart = dataStart + 5
                val hostnameEnd = hostnameStart + hostnameLen
                if (hostnameEnd > dataEnd) return null
                return hostnameStart until hostnameEnd
            }
            p = dataEnd
        }
        return null
    }

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
}
