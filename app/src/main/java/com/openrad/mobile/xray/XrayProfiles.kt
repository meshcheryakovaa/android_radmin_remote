package com.openrad.mobile.xray

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/** Import a node, never execute arbitrary imported Xray root/env/inbounds/routing. */
class ProfileImportException(message: String) : IllegalArgumentException(message)

class XrayProfile(
    val id: String, val name: String, val host: String, val port: Int,
    private val uuid: String, private val flow: String, private val sni: String,
    private val publicKey: String, private val shortId: String, private val fingerprint: String,
) {
    fun summary() = JSONObject().put("id", id).put("name", name).put("host", host).put("port", port)
        .put("protocol", "VLESS + Reality / TCP")

    fun stored() = summary().put("uuid", uuid).put("flow", flow).put("sni", sni)
        .put("publicKey", publicKey).put("shortId", shortId).put("fingerprint", fingerprint)

    /** A minimal private session: one loopback authenticated SOCKS inbound, one VLESS outbound. */
    fun runtimeJson(localPort: Int, socksUser: String, socksPassword: String): String {
        require(localPort in 1..65535 && socksUser.isNotEmpty() && socksPassword.isNotEmpty())
        val outbound = JSONObject().put("tag", "proxy").put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", host).put("port", port)
                .put("users", JSONArray().put(JSONObject().put("id", uuid).put("encryption", "none").put("flow", flow))))))
            .put("streamSettings", JSONObject().put("network", "tcp").put("security", "reality")
                .put("realitySettings", JSONObject().put("serverName", sni).put("publicKey", publicKey)
                    .put("shortId", shortId).put("fingerprint", fingerprint)))
        return JSONObject().put("log", JSONObject().put("loglevel", "none"))
            .put("inbounds", JSONArray().put(JSONObject().put("tag", "openrad-local").put("listen", "127.0.0.1")
                .put("port", localPort).put("protocol", "socks").put("settings", JSONObject().put("udp", false)
                    .put("auth", "password").put("accounts", JSONArray().put(JSONObject().put("user", socksUser).put("pass", socksPassword))))))
            .put("outbounds", JSONArray().put(outbound)).toString()
    }

    override fun toString() = "XrayProfile(id=$id, credentials=REDACTED)"

    companion object {
        private fun text(o: JSONObject, key: String, max: Int = 253): String = o.optString(key, "").also {
            if (it.isEmpty() || it.length > max || it.any { c -> c.isISOControl() || c.isWhitespace() })
                throw ProfileImportException("Некорректное поле профиля: $key")
        }
        fun restore(o: JSONObject): XrayProfile {
            val id = text(o, "id", 80)
            val name = o.optString("name", "VLESS TCP").take(120).filterNot { it.isISOControl() }
            val host = text(o, "host")
            val port = o.optInt("port", 0)
            if (port !in 1..65535) throw ProfileImportException("Некорректный порт сервера")
            val uuid = text(o, "uuid", 36)
            if (!Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}").matches(uuid))
                throw ProfileImportException("Некорректный UUID сервера")
            val flow = o.optString("flow", "")
            if (flow !in setOf("", "xtls-rprx-vision")) throw ProfileImportException("Этот flow пока не поддерживается")
            val sni = text(o, "sni")
            val key = text(o, "publicKey", 44)
            val keyBytes = try { Base64.getUrlDecoder().decode(key) } catch (_: Exception) { ByteArray(0) }
            if (keyBytes.size != 32) throw ProfileImportException("Некорректный Reality public key")
            val sid = o.optString("shortId", "")
            if (sid.length > 16 || sid.length % 2 != 0 || !Regex("[0-9a-fA-F]*").matches(sid))
                throw ProfileImportException("Некорректный Reality short ID")
            val fp = o.optString("fingerprint", "chrome")
            if (fp !in setOf("chrome", "firefox", "safari", "edge", "ios", "android", "random", "randomized", "360", "qq"))
                throw ProfileImportException("Этот fingerprint пока не поддерживается")
            return XrayProfile(id, name.ifEmpty { "VLESS TCP" }, host, port, uuid, flow, sni, key, sid, fp)
        }
        fun fromOutbound(o: JSONObject): XrayProfile {
            val stream = o.getJSONObject("streamSettings")
            val reality = stream.getJSONObject("realitySettings")
            val vnext = o.getJSONObject("settings").getJSONArray("vnext")
            if (vnext.length() != 1) throw ProfileImportException("Ожидается один сервер в профиле")
            val server = vnext.getJSONObject(0)
            val users = server.getJSONArray("users")
            if (users.length() != 1) throw ProfileImportException("Ожидается один пользователь в профиле")
            val user = users.getJSONObject(0)
            if (user.optString("encryption", "none") != "none") throw ProfileImportException("Неподдерживаемое VLESS encryption")
            return restore(JSONObject().put("id", UUID.randomUUID().toString()).put("name", o.optString("tag", "VLESS TCP"))
                .put("host", server.getString("address")).put("port", server.getInt("port"))
                .put("uuid", user.getString("id")).put("flow", user.optString("flow", ""))
                .put("sni", reality.getString("serverName"))
                .put("publicKey", reality.optString("publicKey", reality.optString("password", "")))
                .put("shortId", reality.optString("shortId", ""))
                .put("fingerprint", reality.optString("fingerprint", "chrome")))
        }
    }
}

object XrayImport {
    const val MAX_BYTES = 512 * 1024
    data class Result(val profiles: List<XrayProfile>, val skipped: Int)
    fun parse(raw: String): Result {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw ProfileImportException("JSON превышает 512 КБ")
        val outbounds = try { JSONObject(raw).getJSONArray("outbounds") }
            catch (_: Exception) { throw ProfileImportException("Нужен JSON Xray с массивом outbounds") }
        if (outbounds.length() > 256) throw ProfileImportException("Слишком много записей outbounds")
        val profiles = ArrayList<XrayProfile>()
        var skipped = 0
        for (i in 0 until outbounds.length()) {
            val o = outbounds.optJSONObject(i)
            val stream = o?.optJSONObject("streamSettings")
            if (o?.optString("protocol") != "vless" || stream?.optString("security") != "reality" ||
                stream.optString("network", "tcp") !in setOf("tcp", "raw")) {
                skipped++
                continue
            }
            // Reject malformed supported nodes instead of silently replacing an existing import.
            val p = try { XrayProfile.fromOutbound(o) } catch (e: ProfileImportException) { throw e }
                catch (_: Exception) { throw ProfileImportException("В TCP + Reality профиле не хватает обязательных полей") }
            profiles.add(p)
            if (profiles.size > 32) throw ProfileImportException("Можно импортировать не более 32 TCP профилей")
        }
        if (profiles.isEmpty()) throw ProfileImportException("Нет VLESS + Reality TCP профилей. gRPC будет добавлен позже.")
        return Result(profiles, skipped)
    }
}
