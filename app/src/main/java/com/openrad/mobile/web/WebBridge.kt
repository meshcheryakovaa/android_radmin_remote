package com.openrad.mobile.web

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.openrad.mobile.data.EngineRepository
import com.openrad.mobile.engine.Protocol
import com.openrad.mobile.model.ConnectionPhase
import com.openrad.mobile.proxy.ProxyConfig
import com.openrad.mobile.proxy.ProxyStore
import com.openrad.mobile.vpn.EngineHost
import com.openrad.mobile.vpn.OpenRadVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Origin and main-frame checked by MainActivity. All engine/storage I/O off UI. */
class WebBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val requestVpn: suspend () -> Boolean,
    private val export: suspend (String) -> Unit,
    private val importFile: suspend () -> String?,
) {
    private val repository = EngineRepository(context, scope)
    private val settingsLock = Mutex()
    val remote = com.openrad.mobile.remote.RemoteManager(context)
    private val inflight = java.util.concurrent.atomic.AtomicInteger()

    fun receive(raw: String, reply: (String) -> Unit) {
        if (raw.length > 1100000 || inflight.incrementAndGet() > 16) {
            if (raw.length <= 1100000) inflight.decrementAndGet()
            return
        }
        scope.launch {
            var id = ""
            val response = JSONObject()
            try {
                val request = JSONObject(raw)
                id = request.getString("id").also { require(it.length in 1..80) }
                val method = request.getString("method")
                val args = request.optJSONObject("args") ?: JSONObject()
                val result = withContext(Dispatchers.IO) {
                    // Consent is user-driven; the request remains pending until they answer.
                    if (method in setOf("Radmin.connect", "Proxy.importFile", "Remote.importKey")) call(method, args) else withTimeout(45000) { call(method, args) }
                }
                response.put("result", result ?: JSONObject.NULL)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                response.put("error", "Operation timed out; see Diagnostics")
            } catch (e: com.openrad.mobile.remote.RemoteError) {
                response.put("error", "VEYON:" + e.code)
            } catch (e: com.openrad.mobile.xray.ProfileImportException) {
                response.put("error", e.message)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                // Never echo request arguments, network passwords, or arbitrary exception messages.
                response.put("error", "Operation failed (${e.javaClass.simpleName}); see Diagnostics")
            } finally {
                inflight.decrementAndGet()
            }
            response.put("id", id)
            reply(response.toString())
        }
    }

    private fun ensureStopped() {
        if (EngineHost.engine?.isRunning == true || EngineHost.sessionBusy ||
            com.openrad.mobile.xray.XrayRuntime.phase in setOf("READY", "STARTING", "STOPPING")) {
            throw com.openrad.mobile.xray.ProfileImportException("Сначала отключите VPN, затем меняйте профиль или proxy")
        }
    }

    private suspend fun call(method: String, a: JSONObject): Any? = when (method) {
        "Remote.getConfig" -> remote.config()
        "Remote.saveConfig" -> remote.save(a)
        "Remote.importKey" -> importFile()?.let { remote.importKey(it) } ?: JSONObject().put("cancelled", true)
        "Remote.grid" -> remote.grid(a.getBoolean("enabled"))
        "Remote.lock" -> remote.lock(a.getLong("rid"), a.getBoolean("enabled"))
        "Remote.open" -> remote.open(a.getLong("rid"))
        "Remote.list" -> remote.list()
        "Remote.close" -> { remote.close(a.getString("id")); true }
        "Remote.closeAll" -> { remote.closeAll(); true }
        "Remote.control" -> remote.control(a.getString("id"), a.getBoolean("enabled"))
        "Remote.input" -> remote.input(a.getString("id"), a.getJSONArray("events"))
        "Radmin.getState" -> state()
        "Radmin.connect" -> {
            check(requestVpn()) { "VPN permission denied" }
            repository.connect(); true
        }
        "Radmin.disconnect" -> { repository.disconnect(); true }
        "Radmin.reconnect" -> {
            if (EngineHost.engine?.isRunning == true) OpenRadVpnService.send(context, OpenRadVpnService.ACTION_RECONNECT)
            else { check(requestVpn()); repository.connect() }
            true
        }
        "Radmin.getNetworks" -> state().getJSONArray("networks")
        "Radmin.getPeers" -> state().getJSONArray("peers")
        "Radmin.joinNetwork" -> {
            require(EngineHost.state.value.phase == com.openrad.mobile.engine.EngineState.Phase.ONLINE)
            val name = a.getString("name")
            val password = a.optString("password", "").toCharArray()
            try {
                if (a.optBoolean("private", false)) repository.joinPrivate(name, password) else repository.joinPublic(name)
            } finally { password.fill('\u0000') }
            true
        }
        "Radmin.leaveNetwork" -> { repository.leaveNetwork(a.getString("id")); true }
        "Radmin.searchPublic" -> { repository.searchPublic(a.optString("query", "")); publicNetworks() }
        "Radmin.loadMorePublic" -> { repository.loadMorePublic(); publicNetworks() }
        "Radmin.pingPeer" -> { repository.testRtt(a.getLong("id")); true }
        "Radmin.copyIp" -> {
            val peer = repository.snapshot.value.peers.first { it.rid == a.getLong("id") }
            withContext(Dispatchers.Main) {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("VPN IP", peer.vip))
            }; true
        }
        "Proxy.getConfig" -> proxySettings(ProxyStore(context))
        "Proxy.importJson", "Proxy.importFile" -> {
            // A user may spend time in the document picker; don't hold the session lock there.
            val text = if (method == "Proxy.importFile") importFile() else a.getString("text")
            if (text == null) JSONObject().put("cancelled", true) else settingsLock.withLock {
                EngineHost.lifecycle.withLock {
                    ensureStopped()
                    ProxyStore(context).importProfiles(text)
                }
            }
        }
        "Proxy.setConfig" -> settingsLock.withLock {
            EngineHost.lifecycle.withLock {
                ensureStopped()
                val store = ProxyStore(context)
                val config = ProxyConfig.from(a, store.load())
                store.save(config); proxySettings(store)
            }
        }
        "Proxy.enable", "Proxy.disable" -> settingsLock.withLock {
            EngineHost.lifecycle.withLock {
                ensureStopped()
                val store = ProxyStore(context)
                val old = store.load()
                val mode = if (method == "Proxy.disable") "DIRECT" else if (old.profileId.isNotEmpty()) "XRAY" else "PROXY"
                store.save(ProxyConfig.from(old.json(true).put("mode", mode)))
                proxySettings(store)
            }
        }
        "Proxy.test" -> {
            val store = ProxyStore(context)
            val config = if (a.length() == 0) store.load() else ProxyConfig.from(a, store.load())
            val started = System.nanoTime()
            val transport = if (config.mode == "XRAY") {
                if (EngineHost.engine?.isRunning == true && EngineHost.transportLabel == "XRAY_VLESS_REALITY" &&
                    config.profileId == store.load().profileId) EngineHost.socketProtector?.outboundTransport else null
            } else config.transport()
            if (transport == null || (config.mode == "XRAY" &&
                    EngineHost.state.value.phase != com.openrad.mobile.engine.EngineState.Phase.ONLINE))
                JSONObject().put("requiresConnect", true)
                    .put("message", "Сохраните выбранный профиль и дождитесь CONNECTED на Главной. Затем проверьте PING до ПК.")
            else if (config.mode == "XRAY") {
                // A SOCKS CONNECT reply alone does not prove the remote Reality handshake.
                // The existing authenticated OpenRad control session proves that path instead.
                JSONObject().put("ok", true).put("xraySession", true).put("latencyMs", EngineHost.state.value.latencyMs)
                    .put("scope", "Existing authenticated OpenRad control session through Xray; peer channels must be tested separately")
            } else {
                transport.connectTcp(Protocol.DEFAULT_HOST, Protocol.DEFAULT_PORT, config.timeoutMs,
                    EngineHost.socketProtector, null).use { }
                JSONObject().put("ok", true).put("latencyMs", (System.nanoTime() - started) / 1000000)
                    .put("scope", "TCP CONNECT only; OpenRad authentication not tested")
            }
        }
        "Settings.get" -> JSONObject().put("nodeName", repository.settings.value.nodeName)
            .put("autoReconnect", repository.settings.value.autoReconnect)
        "Settings.save" -> {
            repository.saveSettings(repository.settings.value.copy(nodeName = a.getString("nodeName"),
                autoReconnect = a.optBoolean("autoReconnect", true)))
            true
        }
        "Diagnostics.getLogs" -> DiagnosticLog.json()
        "Diagnostics.clearLogs" -> { DiagnosticLog.clear(); true }
        "Diagnostics.get" -> diagnostics()
        "Diagnostics.copy" -> {
            val text = diagnostics().toString(2)
            withContext(Dispatchers.Main) { context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Diagnostics", text)) }; true
        }
        "Diagnostics.export" -> { export(diagnostics().toString(2)); true }
        else -> throw IllegalArgumentException("unknown method")
    }

    private fun proxySettings(store: ProxyStore) = store.load().json()
        .put("profiles", JSONArray(store.profiles().map { it.summary() }))

    private fun publicNetworks() = JSONObject().put("items", JSONArray(repository.publicResults.value.map {
        JSONObject().put("name", it.name).put("members", it.reportedCount).put("joined", it.joined)
    })).put("hasMore", repository.publicHasMore.value)

    private fun state(): JSONObject {
        val s = repository.snapshot.value
        val status = repository.status.value
        return JSONObject().put("remote", remote.list()).put("phase", if (EngineHost.sessionBusy) "CONNECTING" else if (EngineHost.connectionError != null) "ERROR" else status.phase.name)
            .put("message", EngineHost.connectionError ?: status.message)
            .put("xray", com.openrad.mobile.xray.XrayRuntime.status())
            .put("vpnReady", s.interfaceReady).put("interfaceError", s.interfaceError ?: JSONObject.NULL)
            .put("ip", s.vip ?: JSONObject.NULL).put("nodeName", s.nodeName ?: repository.settings.value.nodeName)
            .put("transport", if (EngineHost.engine?.isRunning == true) EngineHost.transportLabel else "NONE")
            .put("forceRelay", s.forceRelay).put("rx", s.traffic.receivedBytes).put("tx", s.traffic.sentBytes)
            .put("dropped", s.traffic.dropped).put("latencyMs", s.latencyMs)
            .put("networks", JSONArray(s.networks.map { n -> JSONObject().put("id", n.networkId).put("name", n.name)
                .put("members", n.memberCount).put("pendingApproval", n.pendingApproval) }))
            .put("peers", JSONArray(s.peers.map { p -> JSONObject().put("id", p.rid.toString()).put("name", p.name)
                .put("ip", p.vip).put("state", p.state.name).put("detail", p.detail)
                .put("transport", p.transport?.name ?: JSONObject.NULL).put("rttMs", p.rttMs ?: JSONObject.NULL)
                .put("rttFailed", p.rttFailed).put("rttRunning", p.rttRunning)
                .put("networkIds", JSONArray(p.networkIds.toList())) }))
    }

    private fun diagnostics(): JSONObject {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
            .filter { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        val uplink = when {
            capabilities.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } -> "Wi-Fi"
            capabilities.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } -> "mobile"
            capabilities.isNotEmpty() -> "other"
            else -> "unavailable"
        }
        val config = ProxyStore(context).load()
        val profile = if (config.mode == "XRAY") ProxyStore(context).profiles().firstOrNull { it.id == config.profileId } else null
        return state().put("uplink", uplink).put("route", "26.0.0.0/8").put("mtu", 1500)
            .put("proxy", JSONObject().put("mode", config.mode).put("host", profile?.host ?: config.host).put("port", profile?.port ?: config.port)
                .put("profileName", profile?.name ?: JSONObject.NULL).put("network", profile?.network ?: JSONObject.NULL))
            .put("udpPolicy", if (EngineHost.transportLabel in setOf("SOCKS5", "XRAY_VLESS_REALITY")) "disabled; TCP relay only" else "upstream Wi-Fi policy")
            .put("logs", DiagnosticLog.json()).put("activity", JSONArray(repository.activity.value.map {
                JSONObject().put("message", it.text).put("error", it.isError)
            }))
    }
}
