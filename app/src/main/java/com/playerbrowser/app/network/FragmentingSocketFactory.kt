package com.playerbrowser.app.network

import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Wraps a plain SocketFactory and returns Sockets whose first OutputStream.write()
 * is split into two TCP segments. The split happens AFTER the 5-byte TLS record
 * header so that DPI engines doing naive SNI substring matching on a single
 * packet fail to reassemble the SNI extension.
 *
 * This is an alpha-quality bypass. It works against some Korean ISPs that do
 * single-packet pattern matching; it will NOT defeat full TCP reassembly.
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
        val out = inner.getOutputStream()
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
        if (!firstWriteCheck() && len > 10) {
            // Split after the 5-byte TLS record header. The second flush ensures
            // the kernel doesn't coalesce both writes into a single TCP segment.
            val splitAt = 5
            delegate.write(b, off, splitAt)
            delegate.flush()
            delegate.write(b, off + splitAt, len - splitAt)
            delegate.flush()
            onFirstWriteDone()
        } else {
            delegate.write(b, off, len)
        }
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}
