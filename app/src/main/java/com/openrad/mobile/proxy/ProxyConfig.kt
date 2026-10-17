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
) {
    fun transport(): OutboundTransport = when (mode) {
        "DIRECT" -> DirectTransport()
        "PROXY" -> Socks5Transport(host, port, username, password, timeoutMs)
        else -> error("Only DIRECT and SOCKS5 are implemented in this MVP")
    }
    fun json(includeSecret: Boolean = false) = JSONObject().put("mode", mode).put("host", host).put("port", port)
        .put("username", username).put("timeoutMs", timeoutMs).apply {
            if (includeSecret) put("password", password) else put("hasPassword", password.isNotEmpty())
        }
    override fun toString() = "ProxyConfig(mode=$mode, credentials=REDACTED)"
    companion object {
        fun from(o: JSONObject, old: ProxyConfig = ProxyConfig()) = ProxyConfig(
            o.optString("mode", "DIRECT"), o.optString("host", "").trim(), o.optInt("port", 1080),
            o.optString("username", ""),
            if (o.optBoolean("clearPassword", false)) "" else if (o.has("password")) o.getString("password") else old.password,
            o.optInt("timeoutMs", 10000),
        ).also { it.transport() }
    }
}

/** Keystore failures never fall back to plain preferences. */
class ProxyStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(context, "remote_proxy",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    fun load(): ProxyConfig = prefs.getString("config", null)?.let { ProxyConfig.from(JSONObject(it)) } ?: ProxyConfig()
    fun save(config: ProxyConfig) {
        config.transport()
        check(prefs.edit().putString("config", config.json(true).toString()).commit()) { "proxy save failed" }
    }
}
