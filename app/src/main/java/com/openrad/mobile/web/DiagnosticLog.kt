package com.openrad.mobile.web

import com.openrad.mobile.vpn.EngineHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Process lifetime, bounded, not persisted and no request bodies/credentials. */
object DiagnosticLog {
    private val rows = ArrayDeque<JSONObject>()
    fun start(scope: CoroutineScope) { scope.launch { EngineHost.events.collect { add(it.message, it.error) } } }
    @Synchronized fun add(message: String, error: Boolean = false) {
        rows.addLast(JSONObject().put("time", System.currentTimeMillis()).put("error", error).put("message", message.take(2000)))
        while (rows.size > 200) rows.removeFirst()
    }
    @Synchronized fun json(): JSONArray = JSONArray(rows.toList())
    @Synchronized fun clear() { rows.clear() }
}
