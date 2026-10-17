package com.openrad.mobile.transport

import com.openrad.mobile.engine.Framed
import com.openrad.mobile.engine.SocketProtector
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Only loopback sockets, synthetic endpoints and credentials; never provisions identities. */
class Socks5TransportTest {
    private fun <T> proxy(server: (Socket) -> Unit, client: (Int) -> T): T {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 5000
            val failure = AtomicReference<Throwable?>()
            val thread = Thread {
                try { listener.accept().use { it.soTimeout = 3000; server(it) } }
                catch (e: Throwable) { failure.set(e) }
            }.apply { isDaemon = true; start() }
            val result = try { client(listener.localPort) } finally { thread.join(6000) }
            assertFalse("proxy worker leaked", thread.isAlive)
            failure.get()?.let { throw AssertionError("fake proxy failed", it) }
            return result
        }
    }
    private fun greeting(s: Socket) {
        val b = ByteArray(3); DataInputStream(s.getInputStream()).readFully(b)
        assertArrayEquals(byteArrayOf(5,1,0), b)
        s.getOutputStream().write(byteArrayOf(5,0))
    }
    private fun connect(s: Socket, replyType: Int = 1) {
        val b = ByteArray(10); DataInputStream(s.getInputStream()).readFully(b)
        assertArrayEquals(byteArrayOf(5,1,0,1,26,10,20,30,0x13,0x88.toByte()), b)
        val reply = when(replyType) {
            3 -> byteArrayOf(5,0,0,3,3,97,98,99,0,1)
            4 -> byteArrayOf(5,0,0,4) + ByteArray(16) + byteArrayOf(0,1)
            else -> byteArrayOf(5,0,0,1,127,0,0,1,0,1)
        }
        // Fragment response to exercise exact reads across packet boundaries.
        for (byte in reply) s.getOutputStream().write(byteArrayOf(byte))
    }
    @Test fun connectUsesProxyAndPreservesFraming() {
        proxy({ s ->
            greeting(s); connect(s)
            val input = DataInputStream(s.getInputStream()); assertEquals(3,input.readInt())
            val body=ByteArray(3); input.readFully(body); assertArrayEquals(byteArrayOf(1,2,3),body)
            s.getOutputStream().write(byteArrayOf(0,0,0,2,9,8))
        }) { port ->
            var protected = false
            val p = object : SocketProtector {
                override val outboundTransport = Socks5Transport("127.0.0.1",port)
                override fun protect(socket: Socket): Boolean { assertFalse(socket.isConnected); protected=true; return true }
                override fun protect(socket: DatagramSocket) = true
            }
            val framed = Framed.connect("26.10.20.30",5000,3000,p,AtomicBoolean(false))
            try {
                assertTrue(protected); assertTrue(p.outboundTransport.relayOnly)
                framed.send(byteArrayOf(1,2,3))
                assertArrayEquals(byteArrayOf(9,8),framed.receive(16,System.nanoTime()/1000000+3000))
            } finally { framed.close() }
        }
    }
    @Test fun acceptsDomainAndIpv6BoundAddresses() {
        for (type in listOf(3,4)) proxy({ s -> greeting(s); connect(s,type) }) { port ->
            Socks5Transport("127.0.0.1",port).connectTcp("26.10.20.30",5000,3000,null,null).close()
        }
    }
    @Test fun authenticatesWithRfc1929() {
        proxy({ s ->
            val i=DataInputStream(s.getInputStream()); val greet=ByteArray(3); i.readFully(greet)
            assertArrayEquals(byteArrayOf(5,1,2),greet); s.getOutputStream().write(byteArrayOf(5,2))
            assertEquals(1,i.readUnsignedByte()); val u=ByteArray(i.readUnsignedByte());i.readFully(u)
            val p=ByteArray(i.readUnsignedByte());i.readFully(p)
            assertEquals("test",String(u));assertEquals("example",String(p))
            s.getOutputStream().write(byteArrayOf(1,0));connect(s)
        }) { port -> Socks5Transport("127.0.0.1",port,"test","example").connectTcp("26.10.20.30",5000,3000,null,null).close() }
    }
    @Test fun rejectsAuthenticationDowngrade() {
        proxy({ s ->
            val b=ByteArray(3);DataInputStream(s.getInputStream()).readFully(b)
            s.getOutputStream().write(byteArrayOf(5,0))
            assertEquals(-1,s.getInputStream().read())
        }) { port ->
            try { Socks5Transport("127.0.0.1",port,"test","secret").connectTcp("26.10.20.30",5000,3000,null,null);fail("downgrade accepted") }
            catch(e: IOException) { assertFalse(e.message.orEmpty().contains("secret")) }
        }
    }
    @Test fun rejectedConnectDoesNotFallBackDirect() {
        proxy({ s ->
            greeting(s); val b=ByteArray(10);DataInputStream(s.getInputStream()).readFully(b)
            s.getOutputStream().write(byteArrayOf(5,5,0,1)); assertEquals(-1,s.getInputStream().read())
        }) { port ->
            try { Socks5Transport("127.0.0.1",port).connectTcp("26.10.20.30",5000,3000,null,null);fail("refusal ignored") }
            catch(e: IOException) { assertTrue(e.message.orEmpty().contains("refused")) }
        }
    }
    @Test fun failedProtectPreventsConnection() {
        val protector = object : SocketProtector {
            override fun protect(socket: Socket) = false
            override fun protect(socket: DatagramSocket) = false
        }
        try { DirectTransport().connectTcp("127.0.0.1",1,100,protector,null);fail("protection failure ignored") }
        catch(e: IOException) { assertEquals("socket protection failed",e.message) }
    }
    @Test fun handshakeDeadlineClosesSocket() {
        proxy({ s -> val b=ByteArray(3);DataInputStream(s.getInputStream()).readFully(b); assertEquals(-1,s.getInputStream().read()) }) { port ->
            try { Socks5Transport("127.0.0.1",port,timeoutMs=1000).connectTcp("26.10.20.30",5000,1000,null,null);fail("missing timeout") }
            catch(_: SocketTimeoutException) { }
        }
    }
    @Test fun cancelledConnectDoesNotOpenSocket() {
        try { Socks5Transport("127.0.0.1",1).connectTcp("26.10.20.30",5000,1000,null,AtomicBoolean(true));fail("cancel ignored") }
        catch(_: com.openrad.mobile.engine.CancelledException) { }
    }
}
