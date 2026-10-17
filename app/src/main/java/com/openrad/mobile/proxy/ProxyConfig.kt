package com.openrad.mobile.proxy

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.openrad.mobile.transport.DirectTransport
import com.openrad.mobile.transport.OutboundTransport
import com.openrad.mobile.transport.Socks5Transport
import org.json.JSONObject

/** Future transports must implement OutboundTransport; unsupported types fail closed. */
class ProxyConfig(
    val mode: String = "DIRECT", val host: String = "", val port: Int = 1080,
    val username: String = "", val password: String = "", val timeoutMs: Int = 10000,
    val profileId: String = "",
) {
    fun validate(): ProxyConfig {
        require(timeoutMs in 1000..30000) { "invalid timeout" }
        when (mode) {
            "DIRECT" -> Unit
            "PROXY" -> Socks5Transport(host, port, username, password, timeoutMs)
            "XRAY" -> require(profileId.isNotEmpty()) { "Select an imported Xray profile" }
            else -> error("Unsupported transport mode")
        }
        return this
    }
    fun transport(): OutboundTransport = when (mode) {
        "DIRECT" -> DirectTransport()
        "PROXY" -> Socks5Transport(host, port, username, password, timeoutMs)
        else -> error("Xray transport must be started by the VPN service")
    }
    fun json(includeSecret: Boolean = false) = JSONObject().put("mode", mode).put("host", host).put("port", port)
        .put("username", username).put("timeoutMs", timeoutMs).put("profileId", profileId).apply {
            if (includeSecret) put("password", password) else put("hasPassword", password.isNotEmpty())
        }
    override fun toString() = "ProxyConfig(mode=$mode, credentials=REDACTED)"
    companion object {
        fun from(o: JSONObject, old: ProxyConfig = ProxyConfig()) = ProxyConfig(
            o.optString("mode", "DIRECT"), o.optString("host", "").trim(), o.optInt("port", 1080),
            o.optString("username", ""),
            if (o.optBoolean("clearPassword", false)) "" else if (o.has("password")) o.getString("password") else old.password,
            o.optInt("timeoutMs", 10000), o.optString("profileId", old.profileId),
        ).validate()
    }
}

/** Keystore failures never fall back to plain preferences. */
class ProxyStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(context, "remote_proxy",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    fun load(): ProxyConfig = prefs.getString("config", null)?.let { ProxyConfig.from(JSONObject(it)) } ?: ProxyConfig()
    fun profiles(): List<com.openrad.mobile.xray.XrayProfile> {
        val array = org.json.JSONArray(prefs.getString("xray_profiles", "[]"))
        return (0 until array.length()).map { com.openrad.mobile.xray.XrayProfile.restore(array.getJSONObject(it)) }
    }
    fun importProfiles(text: String): org.json.JSONObject {
        val parsed = com.openrad.mobile.xray.XrayImport.parse(text)
        val first = parsed.profiles.first().id
        val config = ProxyConfig.from(load().json(true).put("profileId", first))
        check(prefs.edit().putString("xray_profiles", org.json.JSONArray(parsed.profiles.map { it.stored() }).toString())
            .putString("config", config.json(true).toString()).commit()) { "Import save failed" }
        return org.json.JSONObject().put("profiles", org.json.JSONArray(parsed.profiles.map { it.summary() }))
            .put("profileId", first).put("imported", parsed.profiles.size).put("skipped", parsed.skipped)
    }
    fun save(config: ProxyConfig) {
        config.validate()
        if (config.mode == "XRAY") check(profiles().any { it.id == config.profileId }) { "Profile not found" }
        check(prefs.edit().putString("config", config.json(true).toString()).commit()) { "proxy save failed" }
    }
}
