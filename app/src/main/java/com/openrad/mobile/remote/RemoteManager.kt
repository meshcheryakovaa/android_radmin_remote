package com.openrad.mobile.remote

import android.content.Context
import android.graphics.Bitmap
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.openrad.mobile.engine.EngineState
import com.openrad.mobile.engine.Ip
import com.openrad.mobile.engine.LinkState
import com.openrad.mobile.vpn.EngineHost
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** Activity-owned sessions; credentials encrypted, frames RAM-only, max four explicitly selected peers. */
class RemoteManager(context: Context) {
    private val app = context.applicationContext
    private val prefs by lazy { EncryptedSharedPreferences.create(app, "veyon_remote",
        MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM) }
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private val sessions = LinkedHashMap<String, Session>()
    private val pixels = HashMap<String, Int>()
    @Volatile private var disabled = false
    private val pixelLimit = minOf(12_000_000L,
        (app.getSystemService(android.app.ActivityManager::class.java)?.memoryClass ?: 128) * 1024L * 1024 / 24)
    private class Session(val id: String, val rid: Long, val name: String, val ip: String, val epoch: Any, val activityEpoch: Long) {
        @Volatile var phase = "CONNECTING"
        @Volatile var error: String? = null
        @Volatile var closed = false
        @Volatile var client: VeyonClient? = null
        @Volatile var socket: Socket? = null
        @Volatile var worker: Thread? = null
        @Volatile var control = false
        @Volatile var input: RemoteInput? = null
        var imageSerial = -1L
        var imageWidth = 0
        var imageAt = 0L
        var image: ByteArray? = null
    }
    @Synchronized fun resume() { disabled = false }
    private fun ready(): Boolean = !disabled && EngineHost.engine?.isRunning == true &&
        EngineHost.state.value.phase == EngineState.Phase.ONLINE && EngineHost.interfaceStatus.value.ready
    private fun requireReady() { if (!ready()) throw RemoteError("VPN_REQUIRED") }
    private fun noActive() { if (sessions.values.any { !it.closed }) throw RemoteError("CLOSE_SESSIONS_FIRST") }
    @Synchronized fun config(): JSONObject = JSONObject().put("mode", prefs.getString("mode", "key"))
        .put("username", prefs.getString("username", "")).put("keyName", prefs.getString("keyName", "teacher"))
        .put("port", prefs.getInt("port", 11100)).put("hasKey", !prefs.getString("privateKey", "").isNullOrEmpty())
        .put("hasPassword", !prefs.getString("password", "").isNullOrEmpty())
    @Synchronized fun save(a: JSONObject): JSONObject {
        noActive()
        val mode = a.optString("mode", "key"); val username = a.optString("username", "")
        val keyName = a.optString("keyName", "teacher"); val port = a.optInt("port", 11100)
        if (mode !in setOf("key", "logon") || port !in 1..65535 || username.length > 256 || username.any { it.isISOControl() } ||
            !Regex("[A-Za-z0-9_-]{1,64}").matches(keyName)) throw RemoteError("INVALID_SETTINGS")
        val password = a.optString("password", "")
        if (password.length > 512 || password.any { it == '\u0000' }) throw RemoteError("INVALID_SETTINGS")
        val edit = prefs.edit().putString("mode", mode).putString("username", username).putString("keyName", keyName).putInt("port", port)
        if (a.optBoolean("clearPassword")) edit.remove("password") else if (password.isNotEmpty()) edit.putString("password", password)
        if (a.optBoolean("clearKey")) edit.remove("privateKey")
        if (!edit.commit()) throw RemoteError("SETTINGS_SAVE_FAILED")
        return config()
    }
    @Synchronized fun importKey(text: String): JSONObject {
        noActive(); VeyonWire.privateKey(text)
        if (!prefs.edit().putString("privateKey", text).commit()) throw RemoteError("SETTINGS_SAVE_FAILED")
        return config()
    }
    @Synchronized fun open(rid: Long): JSONObject {
        requireReady()
        val engine = EngineHost.engine ?: throw RemoteError("VPN_REQUIRED")
        val peer = EngineHost.state.value.peers.firstOrNull { it.rid == rid && it.link == LinkState.CONNECTED }
            ?: throw RemoteError("PEER_NOT_CONNECTED")
        val ip = Ip.format(peer.vip)
        if (!ip.startsWith("26.")) throw RemoteError("PEER_NOT_CONNECTED")
        sessions.values.firstOrNull { it.rid == rid && valid(it) }?.let { return status(it) }
        if (sessions.values.count { valid(it) } >= 4) throw RemoteError("SESSION_LIMIT")
        val mode = prefs.getString("mode", "key")
        val auth = if (mode == "key") {
            val pem = prefs.getString("privateKey", "").orEmpty()
            if (pem.isEmpty()) throw RemoteError("CREDENTIALS_REQUIRED")
            VeyonClient.Auth(prefs.getString("username", "").orEmpty().ifEmpty { "android-teacher" },
                keyName = prefs.getString("keyName", "teacher").orEmpty(), privateKey = VeyonWire.privateKey(pem))
        } else {
            val user = prefs.getString("username", "").orEmpty(); val pass = prefs.getString("password", "").orEmpty()
            if (user.isBlank() || pass.isEmpty()) throw RemoteError("CREDENTIALS_REQUIRED")
            VeyonClient.Auth(user, pass.toByteArray(Charsets.UTF_8))
        }
        // Retire old errors for this peer and bound the retained session metadata.
        sessions.values.filter { it.closed && it.rid == rid }.map { it.id }.forEach { sessions.remove(it) }
        while (sessions.size >= 64) { val old = sessions.values.firstOrNull { it.closed } ?: break; sessions.remove(old.id) }
        val s = Session(UUID.randomUUID().toString(), rid, peer.name, ip, engine, generation.get())
        val port = prefs.getInt("port", 11100)
        sessions[s.id] = s
        Thread({
            try {
                val socket = Socket(); s.socket = socket
                if (!valid(s)) throw RemoteError("VPN_REQUIRED")
                socket.keepAlive = true; socket.tcpNoDelay = true; socket.soTimeout = 60000
                // No protect(): desktop traffic must enter the established Radmin TUN.
                socket.connect(InetSocketAddress(s.ip, port), 10000)
                val client = VeyonClient(socket) { n -> synchronized(this) {
                    if (!valid(s)) throw RemoteError("VPN_REQUIRED")
                    if (pixels.filterKeys { it != s.id }.values.sum().toLong() + n > pixelLimit) throw RemoteError("MEMORY_LIMIT")
                    pixels[s.id] = n
                } }
                s.client = client
                if (!valid(s)) throw RemoteError("VPN_REQUIRED")
                client.handshake(auth)
                if (!valid(s)) throw RemoteError("VPN_REQUIRED")
                s.input = RemoteInput(client::pointer, client::key)
                s.phase = "VIEWING"
                while (valid(s)) client.readUpdate()
            } catch (e: Exception) {
                if (!s.closed) { s.error = if (e is RemoteError) e.code else "CONNECTION_FAILED"; s.phase = "ERROR" }
            } finally {
                auth.wipe(); finish(s)
                runCatching { s.client?.dispose() }
                synchronized(this) { pixels.remove(s.id) }
            }
        }, "veyon-${s.id.take(8)}").also { s.worker = it; it.isDaemon = true; it.start() }
        return status(s)
    }
    private fun valid(s: Session) = !s.closed && ready() && EngineHost.engine === s.epoch && s.activityEpoch == generation.get() &&
        EngineHost.state.value.peers.any { it.rid == s.rid && it.link == LinkState.CONNECTED && Ip.format(it.vip) == s.ip }
    private fun status(s: Session) = JSONObject().put("id", s.id).put("rid", s.rid.toString()).put("name", s.name).put("ip", s.ip)
        .put("phase", s.phase).put("error", s.error ?: JSONObject.NULL).put("control", s.control)
        .put("width", s.client?.width ?: 0).put("height", s.client?.height ?: 0).put("serial", s.client?.serial ?: 0L)
        .put("hasFrame", !s.closed && (s.client?.serial ?: 0L) > 0)
    @Synchronized fun list(): JSONArray {
        if (!ready()) closeAll()
        sessions.values.filter { !it.closed && !valid(it) }.forEach {
            it.error = "PEER_NOT_CONNECTED"; it.phase = "ERROR"; finish(it)
        }
        return JSONArray(sessions.values.map { status(it) })
    }
    private fun session(id: String): Session = sessions[id]?.takeIf { valid(it) && it.phase == "VIEWING" } ?: throw RemoteError("SESSION_NOT_READY")
    @Synchronized fun control(id: String, enabled: Boolean): Boolean {
        val s = session(id)
        synchronized(s) {
            if (enabled && (s.client?.serial ?: 0L) == 0L) throw RemoteError("SESSION_NOT_READY")
            releaseInput(s)
            if (enabled) sessions.values.filter { it !== s && it.control }.forEach { other -> synchronized(other) { releaseInput(other); other.control = false } }
            s.input?.setEnabled(enabled)
            s.control = enabled
            s.client?.intervalMs = if (enabled) 250 else 1000
        }
        return true
    }
    private fun releaseInput(s: Session) { s.input?.release() }
    @Synchronized fun input(id: String, events: JSONArray): Boolean {
        val s = session(id)
        if (!s.control || events.length() !in 1..32) throw RemoteError("CONTROL_REQUIRED")
        // Validate the whole batch before sending any event.
        val commands = (0 until events.length()).map { i ->
            val e = events.getJSONObject(i)
            when (e.getString("type")) {
                "pointer" -> {
                    val x = e.getInt("x"); val y = e.getInt("y"); val buttons = e.getInt("buttons")
                    if (x !in 0 until (s.client?.width ?: 0) || y !in 0 until (s.client?.height ?: 0) || buttons !in 0..31) throw RemoteError("INVALID_INPUT")
                    Triple(x, y, buttons)
                }
                "key" -> {
                    val key = e.getInt("key")
                    if (key !in 1..0x0110ffff) throw RemoteError("INVALID_INPUT")
                    key to e.getBoolean("down")
                }
                else -> throw RemoteError("INVALID_INPUT")
            }
        }
        try {
            synchronized(s) {
                for (command in commands) when (command) {
                    is Triple<*, *, *> -> s.input?.pointer(command.first as Int, command.second as Int, command.third as Int)
                    is Pair<*, *> -> {
                        val key = command.first as Int; val down = command.second as Boolean
                        s.input?.key(key, down)
                    }
                }
            }
        } catch (_: java.io.IOException) {
            s.error = "CONNECTION_FAILED"; s.phase = "ERROR"; finish(s)
            throw RemoteError("CONNECTION_FAILED")
        }
        return true
    }
    @Synchronized fun image(id: String, requestedWidth: Int): ByteArray? {
        if (!ready()) { closeAll(); return null }
        val s = sessions[id]?.takeIf { valid(it) && it.phase == "VIEWING" } ?: return null
        synchronized(s) {
            val w = requestedWidth.coerceIn(160, 1600)
            val client = s.client ?: return null
            if (client.serial == 0L) return null
            val now = System.nanoTime() / 1_000_000
            if (s.image != null && s.imageWidth == w && (s.imageSerial == client.serial || now - s.imageAt < 200)) return s.image
            val frame = client.frame() ?: return null
            val bitmap = Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
            val targetW = minOf(w, frame.width); val targetH = maxOf(1, (frame.height.toLong() * targetW / frame.width).toInt())
            val scaled = if (targetW == frame.width) bitmap else Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            try {
                val out = ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.JPEG, 65, out)
                s.image = out.toByteArray(); s.imageSerial = frame.serial; s.imageWidth = w; s.imageAt = now
                return s.image
            } finally { if (scaled !== bitmap) scaled.recycle(); bitmap.recycle() }
        }
    }
    private fun finish(s: Session) = synchronized(s) {
        if (s.closed) return@synchronized
        s.control = false
        val abort = closer.schedule({ runCatching { s.socket?.close() } }, 200, java.util.concurrent.TimeUnit.MILLISECONDS)
        try { releaseInput(s) } finally {
            s.closed = true; s.image = null
            if (s.phase != "ERROR") s.phase = "STOPPED"
            runCatching { s.client?.close() }; runCatching { s.socket?.close() }
            if (s.worker !== Thread.currentThread()) s.worker?.interrupt()
            abort.cancel(false)
        }
    }
    @Synchronized fun close(id: String) { sessions[id]?.let { finish(it) } }
    @Synchronized fun closeAll() { sessions.values.toList().forEach { finish(it) } }
    fun pause() {
        disabled = true
        val epoch = generation.incrementAndGet()
        // Never block Android's main lifecycle thread on a desktop socket write.
        Thread({
            synchronized(this) { sessions.values.filter { it.activityEpoch < epoch }.forEach { finish(it) } }
        }, "veyon-pause").apply { isDaemon = true; start() }
    }
    companion object {
        private val closer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "veyon-close-deadline").apply { isDaemon = true }
        }
    }
}
