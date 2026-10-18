package com.openrad.mobile.remote

import java.io.*
import java.net.Socket
import java.security.PrivateKey
import java.security.Signature
import java.util.zip.Inflater

/** Direct Veyon/RFB client. Its ordinary socket must follow 26/8 through the VPN, NEVER protect(). */
class VeyonClient(
    private val socket: Socket,
    private val reservePixels: (Int) -> Unit = {},
    private val thumbnail: Boolean = false,
) : Closeable {
    class Auth(val username: String, val password: ByteArray? = null, val keyName: String = "", val privateKey: PrivateKey? = null) {
        fun wipe() { password?.fill(0) }
        override fun toString() = "VeyonAuth(REDACTED)"
    }
    data class Frame(val width: Int, val height: Int, val pixels: IntArray, val serial: Long)
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 65536))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    private val outputLock = Any()
    private val frameLock = Any()
    private var pixels = IntArray(0)
    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    @Volatile var serial = 0L; private set
    @Volatile var intervalMs = 1000L
    @Volatile private var closed = false
    private val inflater = Inflater()

    fun handshake(auth: Auth, startFrames: Boolean = true) {
        try {
            val greeting = ByteArray(12); input.readFully(greeting)
            val version = greeting.toString(Charsets.US_ASCII)
            // Some Veyon builds terminate their 12-byte snprintf banner with NUL.
            if (version !in setOf("RFB 003.007\n", "RFB 003.008\n", "RFB 003.007\u0000", "RFB 003.008\u0000")) throw RemoteError("NOT_VEYON")
            synchronized(outputLock) { output.write((version.take(11) + "\n").toByteArray(Charsets.US_ASCII)); output.flush() }
            val count = input.readUnsignedByte()
            if (count == 0) throw RemoteError("NOT_VEYON")
            val types = ByteArray(count); input.readFully(types)
            if (types.none { it.toInt() == 40 }) throw RemoteError("NOT_VEYON")
            synchronized(outputLock) { output.writeByte(40); output.flush() }
            val methods = VeyonWire.receive(input)
            val method = if (auth.privateKey != null) 3 else 4
            if (methods.firstOrNull() !is Int || methods.size !in 2..33 || methods.first() != methods.size - 1)
                throw RemoteError("INVALID_MESSAGE")
            if (method !in methods.drop(1)) throw RemoteError("AUTH_METHOD_UNAVAILABLE")
            VeyonWire.send(output, method, auth.username)
            if (VeyonWire.receive(input).isNotEmpty()) throw RemoteError("INVALID_MESSAGE")
            if (method == 3) {
                val challenge = VeyonWire.receive(input).singleOrNull() as? ByteArray ?: throw RemoteError("INVALID_MESSAGE")
                if (challenge.size != 128) throw RemoteError("INVALID_MESSAGE")
                val signer = Signature.getInstance("SHA512withRSA")
                signer.initSign(auth.privateKey); signer.update(challenge)
                val signature = signer.sign()
                try { VeyonWire.send(output, auth.keyName, signature) } finally { signature.fill(0); challenge.fill(0) }
            } else {
                val publicKey = VeyonWire.receive(input).singleOrNull() as? String ?: throw RemoteError("INVALID_MESSAGE")
                val password = auth.password ?: throw RemoteError("CREDENTIALS_REQUIRED")
                val encrypted = VeyonWire.encryptedPassword(publicKey, password)
                try { VeyonWire.send(output, encrypted) } finally { encrypted.fill(0) }
            }
            if (input.readInt() != 0) throw RemoteError("AUTH_FAILED")
            output.writeByte(1); output.flush() // Shared desktop; do not evict another teacher.
            val w = input.readUnsignedShort(); val h = input.readUnsignedShort()
            if (w == 0 || h == 0) throw RemoteError("ACCESS_DENIED")
            val format = ByteArray(16); input.readFully(format)
            skip(input.readInt(), 16384) // Desktop name is not trusted or exposed.
            if (!startFrames) { width = w; height = h; socket.soTimeout = 0; return }
            resize(w, h)
            synchronized(outputLock) {
                // RGB332 for snapshots halves uncompressed wire bytes; zlib stays enabled.
                output.write(byteArrayOf(0, 0, 0, 0, if (thumbnail) 8 else 16, if (thumbnail) 8 else 16, 0, 1))
                output.writeShort(if (thumbnail) 7 else 31); output.writeShort(if (thumbnail) 7 else 63); output.writeShort(if (thumbnail) 3 else 31)
                output.write(if (thumbnail) byteArrayOf(0, 3, 6, 0, 0, 0) else byteArrayOf(11, 5, 0, 0, 0, 0))
                val encodings = intArrayOf(6, 1, 0, -223, -224) // zlib, copyrect, raw, resize, lastrect
                output.writeByte(2); output.writeByte(0); output.writeShort(encodings.size)
                encodings.forEach { output.writeInt(it) }; output.flush()
            }
            socket.soTimeout = 0
            request(false)
        } finally { auth.wipe() }
    }
    private fun resize(w: Int, h: Int) {
        val n = w.toLong() * h
        if (w !in 1..8192 || h !in 1..8192 || n > 8_294_400L) throw RemoteError("SCREEN_TOO_LARGE")
        reservePixels(n.toInt())
        val next = IntArray(n.toInt()) { 0xff000000.toInt() }
        synchronized(frameLock) { width = w; height = h; pixels = next; serial = 0 }
    }
    private fun skip(size: Int, max: Int = VeyonWire.MAX_MESSAGE) {
        if (size !in 0..max) throw RemoteError("INVALID_MESSAGE")
        val buffer = ByteArray(4096); var remaining = size
        while (remaining > 0) { val n = minOf(remaining, buffer.size); input.readFully(buffer, 0, n); remaining -= n }
    }
    private fun request(incremental: Boolean) = synchronized(outputLock) {
        if (!closed) {
            output.writeByte(3); output.writeBoolean(incremental); output.writeShort(0); output.writeShort(0)
            output.writeShort(width); output.writeShort(height); output.flush()
        }
    }
    fun readUpdate(autoRequest: Boolean = true) {
        when (input.readUnsignedByte()) {
            0 -> {
                input.readByte(); val count = input.readUnsignedShort()
                if (count > 4096 && count != 65535) throw RemoteError("INVALID_MESSAGE")
                var changed = false
                var processedPixels = 0L
                for (i in 0 until minOf(count, 4096)) {
                    val x = input.readUnsignedShort(); val y = input.readUnsignedShort()
                    val w = input.readUnsignedShort(); val h = input.readUnsignedShort(); val encoding = input.readInt()
                    if (encoding == -224) break
                    if (encoding == -223) { resize(w, h); continue }
                    if (w <= 0 || h <= 0 || x.toLong() + w > width || y.toLong() + h > height) throw RemoteError("INVALID_RECTANGLE")
                    processedPixels += w.toLong() * h
                    if (processedPixels > width.toLong() * height * 4) throw RemoteError("INVALID_MESSAGE")
                    when (encoding) {
                        0, 6 -> {
                            val bytes = ByteArray(w * h * if (thumbnail) 1 else 2)
                            if (encoding == 0) input.readFully(bytes) else {
                                val size = input.readInt()
                                if (size !in 1..16_777_216) throw RemoteError("INVALID_MESSAGE")
                                val compressed = ByteArray(size); input.readFully(compressed)
                                if (inflater.finished()) inflater.reset()
                                inflater.setInput(compressed)
                                var position = 0
                                while (position < bytes.size) {
                                    val n = inflater.inflate(bytes, position, bytes.size - position)
                                    if (n == 0) throw RemoteError("INVALID_ZLIB")
                                    position += n
                                }
                                val extra = ByteArray(1)
                                while (!inflater.needsInput() && !inflater.finished()) {
                                    if (inflater.inflate(extra) != 0 || inflater.needsDictionary()) throw RemoteError("INVALID_ZLIB")
                                    if (!inflater.needsInput() && !inflater.finished()) throw RemoteError("INVALID_ZLIB")
                                }
                            }
                            synchronized(frameLock) {
                                var source = 0
                                for (row in 0 until h) for (col in 0 until w) {
                                    val p = if (thumbnail) bytes[source++].toInt() and 255 else
                                        (bytes[source++].toInt() and 255) or ((bytes[source++].toInt() and 255) shl 8)
                                    val r = if (thumbnail) (p and 7) * 255 / 7 else (p ushr 11) * 255 / 31
                                    val g = if (thumbnail) ((p ushr 3) and 7) * 255 / 7 else ((p ushr 5) and 63) * 255 / 63
                                    val b = if (thumbnail) ((p ushr 6) and 3) * 255 / 3 else (p and 31) * 255 / 31
                                    pixels[(y + row) * width + x + col] = (255 shl 24) or (r shl 16) or (g shl 8) or b
                                }
                            }
                        }
                        1 -> {
                            val sx = input.readUnsignedShort(); val sy = input.readUnsignedShort()
                            if (sx.toLong() + w > width || sy.toLong() + h > height) throw RemoteError("INVALID_RECTANGLE")
                            synchronized(frameLock) {
                                val copy = IntArray(w * h)
                                for (row in 0 until h) System.arraycopy(pixels, (sy + row) * width + sx, copy, row * w, w)
                                for (row in 0 until h) System.arraycopy(copy, row * w, pixels, (y + row) * width + x, w)
                            }
                        }
                        else -> throw RemoteError("UNSUPPORTED_ENCODING")
                    }
                    changed = true
                    if (i == 4095 && count > 4096) throw RemoteError("INVALID_MESSAGE")
                }
                if (changed) synchronized(frameLock) { serial++ }
                if (autoRequest) { Thread.sleep(intervalMs.coerceIn(100, 60000)); request(true) }
            }
            1 -> { input.readByte(); input.readUnsignedShort(); skip(input.readUnsignedShort() * 6) }
            2 -> Unit // Bell
            3 -> { skip(3, 3); skip(input.readInt()) } // Clipboard discarded, never read into UI/logs.
            41 -> VeyonWire.skipMessage(input) // Veyon feature/status messages; not arbitrary code.
            else -> throw RemoteError("UNSUPPORTED_MESSAGE")
        }
    }
    fun frame(): Frame? = synchronized(frameLock) { if (serial == 0L) null else Frame(width, height, pixels.copyOf(), serial) }
    fun pointer(x: Int, y: Int, buttons: Int) = synchronized(outputLock) {
        if (closed || x !in 0 until width || y !in 0 until height || buttons !in 0..31) throw RemoteError("INVALID_INPUT")
        output.writeByte(5); output.writeByte(buttons); output.writeShort(x); output.writeShort(y); output.flush()
    }
    fun key(key: Int, down: Boolean) = synchronized(outputLock) {
        if (closed || key !in 1..0x11ffff_ff) throw RemoteError("INVALID_INPUT")
        output.writeByte(4); output.writeBoolean(down); output.writeShort(0); output.writeInt(key); output.flush()
    }
    fun screenLock(enabled: Boolean) = synchronized(outputLock) {
        if (closed) throw RemoteError("SESSION_NOT_READY")
        VeyonWire.screenLock(output, enabled)
    }
    override fun close() {
        closed = true; runCatching { socket.close() }
        // Reader owns Inflater: end it only after readUpdate exits, via dispose().
    }
    fun dispose() { close(); inflater.end(); synchronized(frameLock) { pixels = IntArray(0); serial = 0 } }
}
