package com.openrad.mobile.transport

import com.openrad.mobile.engine.CancelledException
import com.openrad.mobile.engine.SocketProtector
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Socket factory below OpenRad framing/crypto. No Android dependency. */
interface OutboundTransport {
    val label: String
    val relayOnly: Boolean
    fun connectTcp(host: String, port: Int, timeoutMs: Int, protector: SocketProtector?, stop: AtomicBoolean?): Socket
}

class DirectTransport : OutboundTransport {
    override val label = "DIRECT"
    override val relayOnly = false
    override fun connectTcp(host: String, port: Int, timeoutMs: Int, protector: SocketProtector?, stop: AtomicBoolean?): Socket {
        if (stop?.get() == true) throw CancelledException()
        val socket = Socket()
        try {
            // Android creates the native descriptor lazily. Materialize it by binding
            // locally before protect(), but never connect/send before protection succeeds.
            socket.bind(InetSocketAddress(0))
            if (protector != null && !protector.protect(socket)) throw IOException("socket protection failed")
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            if (stop?.get() == true) throw CancelledException()
            return socket
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }
}

/** RFC 1928 CONNECT + RFC 1929 credentials. UDP is deliberately relay-only. */
class Socks5Transport(
    private val host: String,
    private val port: Int,
    private val username: String = "",
    private val password: String = "",
    private val timeoutMs: Int = 10000,
) : OutboundTransport {
    override val label = "SOCKS5"
    override val relayOnly = true

    init {
        require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it.isISOControl() }) { "invalid proxy host" }
        require(port in 1..65535) { "invalid proxy port" }
        require(timeoutMs in 1000..30000) { "invalid timeout" }
        require(username.toByteArray(Charsets.UTF_8).size <= 255 && password.toByteArray(Charsets.UTF_8).size <= 255) { "credentials too long" }
        require(username.isEmpty() == password.isEmpty()) { "both username and password required" }
    }

    override fun connectTcp(host: String, port: Int, timeoutMs: Int, protector: SocketProtector?, stop: AtomicBoolean?): Socket {
        require(port in 1..65535)
        // OpenRad supplies numeric addresses: do not leak target DNS queries.
        val ip = com.openrad.mobile.engine.Ip.parse(host)
        val budget = minOf(timeoutMs, this.timeoutMs).coerceAtLeast(1)
        val deadline = System.nanoTime() + budget * 1_000_000L
        val socket = DirectTransport().connectTcp(this.host, this.port, budget, protector, stop)
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            fun read(size: Int): ByteArray {
                val bytes = ByteArray(size)
                var at = 0
                while (at < size) {
                    if (stop?.get() == true) throw CancelledException()
                    val left = (deadline - System.nanoTime()) / 1_000_000
                    if (left <= 0) throw SocketTimeoutException("SOCKS5 handshake timed out")
                    socket.soTimeout = left.coerceIn(1, 200).toInt()
                    val n = try { input.read(bytes, at, size - at) } catch (_: SocketTimeoutException) { continue }
                    if (n < 0) throw EOFException("SOCKS5 connection closed")
                    at += n
                }
                return bytes
            }
            val method = if (username.isEmpty()) 0 else 2
            output.write(byteArrayOf(5, 1, method.toByte()))
            output.flush()
            val greeting = read(2)
            if (greeting[0].toInt() != 5 || greeting[1].toInt() != method) throw IOException("SOCKS5 authentication method rejected")
            if (method == 2) {
                val user = username.toByteArray(Charsets.UTF_8)
                val pass = password.toByteArray(Charsets.UTF_8)
                val auth = byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass
                try {
                    output.write(auth)
                    output.flush()
                    val result = read(2)
                    if (result[0].toInt() != 1 || result[1].toInt() != 0) throw IOException("SOCKS5 authentication failed")
                } finally { user.fill(0); pass.fill(0); auth.fill(0) }
            }
            output.write(byteArrayOf(5, 1, 0, 1,
                (ip ushr 24).toByte(), (ip ushr 16).toByte(), (ip ushr 8).toByte(), ip.toByte(),
                (port ushr 8).toByte(), port.toByte()))
            output.flush()
            val header = read(4)
            if (header[0].toInt() != 5 || header[2].toInt() != 0) throw IOException("invalid SOCKS5 reply")
            if (header[1].toInt() != 0) throw IOException("SOCKS5 CONNECT refused (code ${header[1].toInt() and 255})")
            when (header[3].toInt() and 255) {
                1 -> read(4)
                4 -> read(16)
                3 -> { val n = read(1)[0].toInt() and 255; if (n == 0) throw IOException("invalid SOCKS5 address"); read(n) }
                else -> throw IOException("invalid SOCKS5 address type")
            }
            read(2)
            socket.soTimeout = 0
            if (stop?.get() == true) throw CancelledException()
            return socket
        } catch (e: Exception) { socket.close(); throw e }
    }
}
