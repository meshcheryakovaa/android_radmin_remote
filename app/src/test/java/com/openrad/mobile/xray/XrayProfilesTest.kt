package com.openrad.mobile.xray

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Synthetic profiles only. No subscriptions, real UUIDs or remote connections. */
class XrayProfilesTest {
    private val uuid = "11111111-2222-4333-8444-555555555555"
    private val publicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private fun outbound(network: String = "tcp", tag: String = "Test node"): JSONObject = JSONObject()
        .put("tag", tag).put("protocol", "vless")
        .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", "127.0.0.1")
            .put("port", 4443).put("users", JSONArray().put(JSONObject().put("id", uuid)
                .put("flow", "xtls-rprx-vision").put("encryption", "none"))))))
        .put("streamSettings", JSONObject().put("network", network).put("security", "reality")
            .put("realitySettings", JSONObject().put("serverName", "example.org").put("publicKey", publicKey)
                .put("shortId", "50").put("fingerprint", "edge")))
    private fun document(vararg nodes: JSONObject) = JSONObject().put("outbounds", JSONArray(nodes.toList())).toString()

    @Test fun importsOnlySupportedNodesFromMixedHappConfiguration() {
        val result = XrayImport.parse(document(outbound(), outbound("tcp", "Second"), outbound("grpc"), outbound("grpc"), outbound("grpc"),
            JSONObject().put("protocol", "freedom"), JSONObject().put("protocol", "blackhole")))
        assertEquals(2, result.profiles.size)
        assertEquals(5, result.skipped)
        assertNotEquals(result.profiles[0].id, result.profiles[1].id)
    }

    @Test fun runtimeDiscardsImportedTunEnvRoutingAndUntrustedOutboundOptions() {
        val node = outbound().put("proxySettings", JSONObject().put("tag", "direct")).put("sendThrough", "26.1.2.3")
        node.getJSONObject("streamSettings").put("sockopt", JSONObject().put("dialerProxy", "other"))
        val malicious = JSONObject(document(node)).put("env", JSONObject().put("xray.tun.fd", 12))
            .put("inbounds", JSONArray().put(JSONObject().put("protocol", "tun")))
            .put("routing", JSONObject().put("rules", JSONArray()))
            .put("log", JSONObject().put("access", "/tmp/config-secret"))
        val p = XrayImport.parse(malicious.toString()).profiles.single()
        val json = JSONObject(p.runtimeJson(32123, "test-user", "synthetic-secret"))
        assertEquals(setOf("log", "inbounds", "outbounds"), json.keys().asSequence().toSet())
        val inbound = json.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("127.0.0.1", inbound.getString("listen"))
        assertEquals("socks", inbound.getString("protocol"))
        assertFalse(inbound.getJSONObject("settings").getBoolean("udp"))
        assertEquals("password", inbound.getJSONObject("settings").getString("auth"))
        val output = json.getJSONArray("outbounds").getJSONObject(0)
        assertEquals(1, json.getJSONArray("outbounds").length())
        assertFalse(output.has("proxySettings"))
        assertFalse(output.has("sendThrough"))
        assertFalse(output.getJSONObject("streamSettings").has("sockopt"))
        assertEquals("none", json.getJSONObject("log").getString("loglevel"))
        // The native smoke script consumes this exact generated config, with synthetic credentials.
        val f = File("build/test-artifacts/xray-profile.json")
        f.parentFile?.mkdirs(); f.writeText(json.toString())
    }

    @Test fun publicSummaryAndToStringExcludeCredentials() {
        val p = XrayImport.parse(document(outbound())).profiles.single()
        assertFalse(p.summary().toString().contains(uuid))
        assertFalse(p.summary().has("uuid"))
        assertFalse(p.summary().has("publicKey"))
        assertFalse(p.toString().contains(uuid))
        val restored = XrayProfile.restore(p.stored())
        assertEquals(p.runtimeJson(1234, "a", "b"), restored.runtimeJson(1234, "a", "b"))
    }

    @Test fun malformedSupportedProfileDoesNotSilentlyReplaceExistingImport() {
        val invalid = outbound()
        invalid.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
            .getJSONArray("users").getJSONObject(0).put("id", "secret-invalid-uuid")
        try { XrayImport.parse(document(outbound(), invalid)); fail("accepted invalid UUID") }
        catch (e: ProfileImportException) { assertFalse(e.message.orEmpty().contains("secret-invalid-uuid")) }
    }

    @Test fun unsupportedOnlyImportHasActionableError() {
        try { XrayImport.parse(document(outbound("grpc"))); fail("no TCP node") }
        catch (e: ProfileImportException) { assertTrue(e.message.orEmpty().contains("TCP")) }
    }

    @Test fun rejectsOddShortIdAndInvalidPublicKey() {
        for ((key, value) in listOf("shortId" to "5", "publicKey" to "bad-secret-key")) {
            val n = outbound(); n.getJSONObject("streamSettings").getJSONObject("realitySettings").put(key, value)
            try { XrayImport.parse(document(n)); fail("accepted invalid Reality field") }
            catch (e: ProfileImportException) { assertFalse(e.message.orEmpty().contains(value)) }
        }
    }

    @Test fun capsSizeBeforeParsingAndKeepsRawPayloadOutOfErrors() {
        try { XrayImport.parse("x".repeat(XrayImport.MAX_BYTES + 1)); fail("oversized document") }
        catch (e: ProfileImportException) { assertTrue(e.message.orEmpty().contains("512")) }
        try { XrayImport.parse("private-invalid-config"); fail("bad document") }
        catch (e: ProfileImportException) { assertFalse(e.message.orEmpty().contains("private-invalid-config")) }
    }
}
