package com.openrad.mobile.xray

import com.openrad.mobile.engine.SocketProtector
import com.openrad.mobile.transport.DirectTransport
import com.openrad.mobile.transport.OutboundTransport
import com.openrad.mobile.transport.Socks5Transport
import libXray.DialerController
import libXray.LibXray
import org.json.JSONObject
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** One native Go runtime and one managed Xray instance; no second VpnService/TUN. */
object XrayRuntime {
    @Volatile var phase = "STOPPED"
        private set
    @Volatile var stage = "STOPPED"
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var version: String? = null
        private set
    @Volatile private var fdProtector: ((Int) -> Boolean)? = null
    private var initialized = false
    private val controller = object : DialerController {
        override fun protectFd(fd: Long): Boolean = fd in 0..Int.MAX_VALUE.toLong() &&
            runCatching { fdProtector?.invoke(fd.toInt()) == true }.getOrDefault(false)
    }

    fun status() = JSONObject().put("phase", phase).put("version", version ?: JSONObject.NULL)
        .put("error", lastError ?: JSONObject.NULL).put("stage", stage)

    private fun invoke(method: String, payload: JSONObject = JSONObject()): JSONObject {
        val request = JSONObject().put("apiVersion", 3).put("method", method).put("payload", payload)
        val result = JSONObject(LibXray.invoke(request.toString()))
        // Native error messages are not surfaced: they may contain config values.
        check(result.optBoolean("success")) { "XRAY_${method.uppercase()}_FAILED" }
        return result.optJSONObject("data") ?: JSONObject()
    }

    @Synchronized fun start(context: android.content.Context, profile: XrayProfile, protect: (Int) -> Boolean, dns: String, timeoutMs: Int,
                            sockets: SocketProtector): OutboundTransport {
        check(phase != "READY" && phase != "STARTING") { "Xray already active" }
        phase = "STARTING"
        lastError = null
        fdProtector = protect
        stage = "REGISTER_PROTECTION"
        try {
            if (!initialized) {
                go.Seq.setContext(context.applicationContext)
                LibXray.registerDialerController(controller)
                LibXray.registerListenerController(controller)
                initialized = true
            }
            invoke("stopXray") // Clear a partial/failed previous session before starting again.
            stage = "CONFIGURE_DNS"
            LibXray.setDNS(controller, dns)
            version = invoke("xrayVersion").optString("version", "unknown")
            val user = "openrad"
            val pass = UUID.randomUUID().toString()
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            stage = "START_CORE"
            invoke("runXray", JSONObject().put("xrayJson", profile.runtimeJson(port, user, pass)))
            check(invoke("getXrayState").optBoolean("running")) { "XRAY_NOT_RUNNING" }
            stage = "VERIFY_LOCAL_SOCKS"
            verifyLocalSocks(port, user, pass, sockets)
            val socks = Socks5Transport("127.0.0.1", port, user, pass, timeoutMs)
            phase = "READY"
            stage = "READY"
            return object : OutboundTransport {
                override val label = "XRAY_VLESS_REALITY"
                override val relayOnly = true
                override fun connectTcp(host: String, port: Int, timeoutMs: Int, protector: SocketProtector?, stop: AtomicBoolean?) =
                    socks.connectTcp(host, port, timeoutMs, protector, stop)
            }
        } catch (_: LinkageError) {
            failStartup("XRAY_NATIVE_UNAVAILABLE")
        } catch (_: Exception) {
            failStartup("XRAY_START_FAILED")
        }
    }

    private fun failStartup(code: String): Nothing {
        if (initialized) {
            runCatching { invoke("stopXray") }
            runCatching { LibXray.resetDNS() }
        }
        fdProtector = null
        phase = "ERROR"
        lastError = "$code:$stage"
        throw IllegalStateException(code)
    }

    /** Verify protocol and per-session auth, not merely an open/reused localhost port. */
    private fun verifyLocalSocks(port: Int, user: String, password: String, protector: SocketProtector) {
        DirectTransport().connectTcp("127.0.0.1", port, 3000, protector, null).use { socket ->
            socket.soTimeout = 3000
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            output.write(byteArrayOf(5, 1, 2)); output.flush()
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 2)
            val u = user.toByteArray(); val p = password.toByteArray()
            val auth = byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p
            try { output.write(auth); output.flush() } finally { auth.fill(0); u.fill(0); p.fill(0) }
            check(input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0)
        }
    }

    @Synchronized fun stop() {
        if (!initialized) { phase = "STOPPED"; fdProtector = null; return }
        phase = "STOPPING"
        stage = "STOPPING"
        try {
            invoke("stopXray")
            LibXray.resetDNS()
            phase = "STOPPED"
            stage = "STOPPED"
        } catch (_: Exception) {
            phase = "ERROR"
            lastError = "XRAY_STOP_FAILED"
        } finally { fdProtector = null }
    }
}
