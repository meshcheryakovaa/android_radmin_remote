package com.openrad.mobile.remote

import java.io.InputStream
import java.net.URI
import java.util.UUID

/** Independent implementation of fixed Veyon feature messages; no shell or arbitrary feature RPC. */
object VeyonCommands {
    val LOCK: UUID = UUID.fromString("ccb535a2-1d24-4cc1-a709-8b47d2b2ac79")
    val POWER_DOWN: UUID = UUID.fromString("6f5a27a0-0e2f-496e-afcc-7aae62eede10")
    val REBOOT: UUID = UUID.fromString("4f7d98f0-395a-4fff-b968-e49b8d0f748c")
    val WEBSITE: UUID = UUID.fromString("8a11a75d-b3db-48b6-b9cb-f8422ddd5b0c")
    val FILES: UUID = UUID.fromString("4a70bd5a-fab2-4a4b-a92a-a1e81d2b75ed")
    const val MAX_FILE_BYTES = 200L * 1024 * 1024
    fun website(value: String): String {
        val uri = try { URI(value.trim()) } catch (_: Exception) { throw RemoteError("INVALID_URL") }
        if (value.length > 2048 || !uri.scheme.orEmpty().lowercase().let { it == "https" || it == "http" } ||
            uri.host.isNullOrEmpty() || uri.rawUserInfo != null || value.any { it.isISOControl() }) throw RemoteError("INVALID_URL")
        return uri.toASCIIString()
    }
    fun fileName(value: String): String {
        val name = value.replace('\\', '/').substringAfterLast('/').replace(Regex("[<>:\"/\\\\|?*\\p{Cntrl}]"), "_")
            .take(200).trim().trimEnd('.', ' ')
        if (name.isEmpty() || name == "." || name == "..") throw RemoteError("INVALID_FILE")
        val stem = name.substringBefore('.').uppercase()
        return if (stem in setOf("CON", "PRN", "AUX", "NUL") || Regex("(COM|LPT)[1-9]").matches(stem)) "file_$name" else name
    }
    fun send(action: String, url: String = "", feature: (UUID, Int, Map<String, Any>) -> Unit) {
        when (action) {
            "lock", "unlock" -> feature(LOCK, if (action == "lock") 0 else 1, emptyMap())
            "shutdown" -> feature(POWER_DOWN, 0, emptyMap())
            "reboot" -> feature(REBOOT, 0, emptyMap())
            "website" -> feature(WEBSITE, 0, mapOf("1" to website(url)))
            else -> throw RemoteError("INVALID_COMMAND")
        }
    }
    /** Veyon file distribution has no per-file receipt ACK. Report transport-sent, never received. */
    fun file(name: String, input: InputStream, feature: (UUID, Int, Map<String, Any>) -> Unit,
             active: () -> Boolean = { true }, progress: (Long) -> Unit = {}) {
        val transfer = UUID.randomUUID()
        val safeName = fileName(name)
        var started = false
        try {
            if (!active()) throw RemoteError("CANCELLED")
            feature(FILES, 0, mapOf("0" to transfer, "1" to safeName, "4" to false)); started = true
            val buffer = ByteArray(65536); var total = 0L
            while (true) {
                if (!active()) throw RemoteError("CANCELLED")
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                total += n
                if (total > MAX_FILE_BYTES) throw RemoteError("FILE_TOO_LARGE")
                feature(FILES, 1, mapOf("0" to transfer, "2" to buffer.copyOf(n))); progress(total)
            }
            if (!active()) throw RemoteError("CANCELLED")
            feature(FILES, 3, mapOf("0" to transfer, "1" to safeName, "3" to false))
            feature(FILES, 5, emptyMap())
        } catch (e: Exception) {
            if (started) runCatching { feature(FILES, 2, mapOf("0" to transfer)); feature(FILES, 5, emptyMap()) }
            throw e
        }
    }
}
