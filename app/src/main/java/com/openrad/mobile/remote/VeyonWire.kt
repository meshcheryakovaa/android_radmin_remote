package com.openrad.mobile.remote

import java.io.*
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import java.security.spec.MGF1ParameterSpec

/** Independently implemented Qt 5.5 QVariant wire subset used by Veyon's RFB security 40. */
class RemoteError(val code: String) : IOException(code)

object VeyonWire {
    const val MAX_MESSAGE = 1024 * 1024
    fun receive(input: DataInputStream): List<Any> {
        val size = input.readInt()
        if (size !in 0..MAX_MESSAGE) throw RemoteError("INVALID_MESSAGE")
        val bytes = ByteArray(size); input.readFully(bytes)
        val stream = DataInputStream(ByteArrayInputStream(bytes))
        val result = ArrayList<Any>()
        while (stream.available() > 0) {
            if (result.size >= 64) throw RemoteError("INVALID_MESSAGE")
            val type = stream.readInt()
            if (stream.readUnsignedByte() != 0) throw RemoteError("INVALID_MESSAGE")
            result.add(when (type) {
                2 -> stream.readInt()
                10, 12 -> {
                    val len = stream.readInt()
                    if (len !in 0..stream.available() || (type == 10 && len % 2 != 0)) throw RemoteError("INVALID_MESSAGE")
                    val value = ByteArray(len); stream.readFully(value)
                    if (type == 10) value.toString(Charsets.UTF_16BE) else value
                }
                else -> throw RemoteError("INVALID_MESSAGE")
            })
        }
        return result
    }
    fun send(output: DataOutputStream, vararg values: Any) {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { stream -> values.forEach { value ->
            when (value) {
                is Int -> { stream.writeInt(2); stream.writeByte(0); stream.writeInt(value) }
                is String -> {
                    val data = value.toByteArray(Charsets.UTF_16BE)
                    stream.writeInt(10); stream.writeByte(0); stream.writeInt(data.size); stream.write(data)
                }
                is ByteArray -> { stream.writeInt(12); stream.writeByte(0); stream.writeInt(value.size); stream.write(value) }
                else -> throw RemoteError("INVALID_MESSAGE")
            }
        } }
        if (bytes.size() > MAX_MESSAGE) throw RemoteError("INVALID_MESSAGE")
        output.writeInt(bytes.size()); bytes.writeTo(output); output.flush()
    }
    /** Fixed Veyon ScreenLock feature: UUID QVariant, signed command, empty QVariantMap. */
    fun screenLock(output: DataOutputStream, enabled: Boolean) {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { stream ->
            val uid = java.util.UUID.fromString("ccb535a2-1d24-4cc1-a709-8b47d2b2ac79")
            stream.writeInt(30); stream.writeByte(0)
            stream.writeLong(uid.mostSignificantBits); stream.writeLong(uid.leastSignificantBits)
            stream.writeInt(2); stream.writeByte(0); stream.writeInt(if (enabled) 0 else 1)
            stream.writeInt(8); stream.writeByte(0); stream.writeInt(0)
        }
        output.writeByte(41); output.writeInt(bytes.size()); bytes.writeTo(output); output.flush()
    }
    fun skipMessage(input: DataInputStream) {
        val size = input.readInt()
        if (size !in 0..MAX_MESSAGE) throw RemoteError("INVALID_MESSAGE")
        val buffer = ByteArray(4096); var remaining = size
        while (remaining > 0) { val n = minOf(buffer.size, remaining); input.readFully(buffer, 0, n); remaining -= n }
    }
    private fun decodePem(pem: String, label: String): ByteArray {
        if (pem.length > 32768 || !pem.contains("-----BEGIN $label-----")) throw RemoteError("INVALID_KEY")
        return try { Base64.getMimeDecoder().decode(pem.substringAfter("-----BEGIN $label-----").substringBefore("-----END $label-----")) }
        catch (_: Exception) { throw RemoteError("INVALID_KEY") }
    }
    private fun der(tag: Int, bytes: ByteArray): ByteArray {
        val len = bytes.size
        val size = if (len < 128) byteArrayOf(len.toByte()) else {
            val n = if (len < 256) 1 else if (len < 65536) 2 else 3
            byteArrayOf((128 + n).toByte()) + ByteArray(n) { (len ushr (8 * (n - it - 1))).toByte() }
        }
        return byteArrayOf(tag.toByte()) + size + bytes
    }
    fun privateKey(pem: String): PrivateKey = try {
        val bytes = if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            val rsa = decodePem(pem, "RSA PRIVATE KEY")
            der(48, byteArrayOf(2, 1, 0) + byteArrayOf(48, 13, 6, 9, 42, -122, 72, -122, -9, 13, 1, 1, 1, 5, 0) + der(4, rsa))
        } else decodePem(pem, "PRIVATE KEY")
        try { KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes)) } finally { bytes.fill(0) }
    } catch (_: Exception) { throw RemoteError("INVALID_KEY") }
    fun encryptedPassword(publicPem: String, password: ByteArray): ByteArray = try {
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(decodePem(publicPem, "PUBLIC KEY")))
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
        cipher.doFinal(password)
    } catch (_: Exception) { throw RemoteError("PASSWORD_ENCRYPTION_FAILED") }
}
