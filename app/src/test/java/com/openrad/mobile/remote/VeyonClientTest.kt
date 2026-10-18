package com.openrad.mobile.remote

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import java.security.spec.MGF1ParameterSpec

/** Synthetic loopback Veyon peer, never real VPN / classroom computers / credentials. */
class VeyonClientTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Test fun matchesIndependentQt55WireVectors() {
        val fixture = JSONObject((javaClass.getResource("/fixtures/veyon-qt55.json") ?: error("missing Qt fixture")).readText()).getJSONArray("vectors")
        val values = listOf(arrayOf<Any>(2,3,4), arrayOf<Any>(4,"ПК\\учитель"), arrayOf<Any>(ByteArray(16){it.toByte()}), emptyArray())
        for(i in 0 until fixture.length()) {
            val expected = hex(fixture.getJSONObject(i).getString("hex"))
            val output = ByteArrayOutputStream();VeyonWire.send(DataOutputStream(output),*values[i])
            assertArrayEquals(expected,output.toByteArray())
            val actual=VeyonWire.receive(DataInputStream(ByteArrayInputStream(expected)))
            assertEquals(values[i].size,actual.size)
            values[i].forEachIndexed { index,v -> if(v is ByteArray)assertArrayEquals(v,actual[index] as ByteArray)else assertEquals(v,actual[index]) }
        }
    }
    @Test fun rejectsOversizedAndUnknownQtMessagesBeforeAllocating() {
        for(bytes in listOf(hex("7fffffff"),hex("00000005000003e700"),hex("000000090000000a007fffffff"))) {
            try {VeyonWire.receive(DataInputStream(ByteArrayInputStream(bytes)));fail("malformed message accepted")}
            catch (_:IOException) { }
        }
    }
    private fun runSession(keyAuth:Boolean, nulBanner:Boolean=false, snapshot:Boolean=false, featureOnly:Boolean=false) {
        val pair=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair()
        val worker=Executors.newSingleThreadExecutor { Thread(it, "synthetic-veyon").apply { isDaemon=true } }
        try { ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")).use { listener ->
            val peer=worker.submit<Unit> {
                listener.accept().use { s ->
                    s.soTimeout=5000;val i=DataInputStream(s.getInputStream());val o=DataOutputStream(s.getOutputStream())
                    o.write((if(nulBanner)"RFB 003.008\u0000" else "RFB 003.008\n").toByteArray());o.flush()
                    val greeting=ByteArray(12);i.readFully(greeting);assertEquals("RFB 003.008\n",greeting.toString(Charsets.US_ASCII))
                    o.write(byteArrayOf(1,40));o.flush();assertEquals(40,i.readUnsignedByte())
                    VeyonWire.send(o,2,3,4)
                    val choice=VeyonWire.receive(i);assertEquals(if(keyAuth)3 else 4,choice[0]);assertEquals("synthetic-user",choice[1])
                    VeyonWire.send(o)
                    if(keyAuth){
                        val challenge=ByteArray(128){it.toByte()};VeyonWire.send(o,challenge)
                        val proof=VeyonWire.receive(i);assertEquals("teacher",proof[0])
                        val verifier=Signature.getInstance("SHA512withRSA");verifier.initVerify(pair.public);verifier.update(challenge)
                        assertTrue(verifier.verify(proof[1] as ByteArray))
                    } else {
                        val publicPem="-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder(64,byteArrayOf(10)).encodeToString(pair.public.encoded)+"\n-----END PUBLIC KEY-----"
                        VeyonWire.send(o,publicPem)
                        val encrypted=VeyonWire.receive(i).single() as ByteArray
                        val cipher=Cipher.getInstance("RSA/ECB/OAEPPadding");cipher.init(Cipher.DECRYPT_MODE,pair.private,OAEPParameterSpec("SHA-1","MGF1",MGF1ParameterSpec.SHA1,PSource.PSpecified.DEFAULT))
                        assertEquals("synthetic-password",cipher.doFinal(encrypted).toString(Charsets.UTF_8))
                    }
                    o.writeInt(0);o.flush();assertEquals(1,i.readUnsignedByte())
                    o.writeShort(2);o.writeShort(2);o.write(ByteArray(16));o.writeInt(4);o.write("test".toByteArray());o.flush()
                    if(featureOnly){
                        for(enabled in listOf(true,false)) {
                            val fixture=JSONObject((javaClass.getResource("/fixtures/veyon-screenlock-qt55.json") ?: error("missing Qt fixture")).readText()).getJSONArray("vectors")
                            val expected=hex(fixture.getJSONObject(if(enabled)0 else 1).getString("hex"))
                            val actual=ByteArray(expected.size);i.readFully(actual);assertArrayEquals(expected,actual)
                        }
                        assertEquals(-1,i.read());return@submit
                    }
                    val format=ByteArray(20);i.readFully(format);assertEquals(if(snapshot)8 else 16,format[4].toInt());assertEquals(1,format[7].toInt())
                    assertEquals(2,i.readUnsignedByte());i.readByte();val count=i.readUnsignedShort();assertEquals(5,count)
                    assertEquals(listOf(6,1,0,-223,-224),List(count){i.readInt()})
                    fun request(incremental:Boolean){assertEquals(3,i.readUnsignedByte());assertEquals(incremental,i.readBoolean());assertEquals(0,i.readUnsignedShort());assertEquals(0,i.readUnsignedShort());assertEquals(2,i.readUnsignedShort());assertEquals(2,i.readUnsignedShort())}
                    fun rect(encoding:Int,data:ByteArray){o.write(byteArrayOf(0,0,0,1));o.writeShort(0);o.writeShort(0);o.writeShort(2);o.writeShort(2);o.writeInt(encoding);if(encoding==6)o.writeInt(data.size);o.write(data);o.flush()}
                    if(snapshot){
                        assertArrayEquals(byteArrayOf(8,8,0,1,0,7,0,7,0,3,0,3,6,0,0,0),format.copyOfRange(4,20))
                        request(false)
                        val deflater=Deflater();val bytes=ByteArray(1024)
                        try{deflater.setInput(hex("0738c0ff"));val n=deflater.deflate(bytes,0,bytes.size,Deflater.SYNC_FLUSH);rect(6,bytes.copyOf(n))}finally{deflater.end()}
                        // Snapshot mode must close after one image, without requesting another full/incremental frame.
                        assertEquals(-1,i.read());return@submit
                    }
                    request(false);rect(0,hex("00f8e0071f00ffff"));request(true)
                    assertEquals(5,i.readUnsignedByte());assertEquals(1,i.readUnsignedByte());assertEquals(1,i.readUnsignedShort());assertEquals(1,i.readUnsignedShort())
                    assertEquals(4,i.readUnsignedByte());assertEquals(1,i.readUnsignedByte());i.readUnsignedShort();assertEquals(0xff0d,i.readInt())
                    val deflater=Deflater()
                    try {
                        for(color in listOf("00f8","e007")) {
                            deflater.setInput(hex(color.repeat(4)));val compressed=ByteArray(1024);val n=deflater.deflate(compressed,0,compressed.size,Deflater.SYNC_FLUSH)
                            rect(6,compressed.copyOf(n));request(true)
                        }
                    } finally {deflater.end()}
                    o.write(byteArrayOf(41));val feature=byteArrayOf(1,2,3,4);o.writeInt(feature.size);o.write(feature);o.flush()
                    o.write(byteArrayOf(0,0,0,1));o.writeShort(1);o.writeShort(1);o.writeShort(1);o.writeShort(1);o.writeInt(1);o.writeShort(0);o.writeShort(0);o.flush();request(true)
                    o.write(byteArrayOf(0,0,0,1));o.writeShort(0);o.writeShort(0);o.writeShort(65535);o.writeShort(65535);o.writeInt(0);o.flush()
                }
            }
            val socket=Socket("127.0.0.1",listener.localPort);socket.soTimeout=5000
            val auth=if(keyAuth)VeyonClient.Auth("synthetic-user",keyName="teacher",privateKey=pair.private)else VeyonClient.Auth("synthetic-user","synthetic-password".toByteArray())
            val client=VeyonClient(socket,thumbnail=snapshot);client.intervalMs=250
            try {
                client.handshake(auth,startFrames=!featureOnly);assertTrue(auth.password==null||auth.password.all{it==0.toByte()})
                if(featureOnly){client.screenLock(true);client.screenLock(false);client.close();peer.get(5,TimeUnit.SECONDS);return}
                if(snapshot){
                    client.readUpdate(autoRequest=false)
                    assertArrayEquals(intArrayOf(0xffff0000.toInt(),0xff00ff00.toInt(),0xff0000ff.toInt(),0xffffffff.toInt()),client.frame()?.pixels)
                    client.close();peer.get(5,TimeUnit.SECONDS);return
                }
                client.readUpdate();val frame=client.frame() ?: error("missing frame")
                assertArrayEquals(intArrayOf(0xffff0000.toInt(),0xff00ff00.toInt(),0xff0000ff.toInt(),0xffffffff.toInt()),frame.pixels)
                client.pointer(1,1,1);client.key(0xff0d,true)
                client.readUpdate();assertTrue(client.frame()?.pixels?.all{it==0xffff0000.toInt()}==true)
                client.readUpdate();assertTrue(client.frame()?.pixels?.all{it==0xff00ff00.toInt()}==true)
                client.readUpdate();assertEquals(3L,client.serial) // feature message must not consume the next framebuffer
                client.readUpdate();assertEquals(4L,client.serial)
                try {client.readUpdate();fail("oversized rectangle accepted")}catch(e:RemoteError){assertEquals("INVALID_RECTANGLE",e.code)}
                assertEquals(4L,client.serial)
                peer.get(5,TimeUnit.SECONDS)
            } finally {client.dispose()}
        }
        } finally { worker.shutdownNow() }
    }
    @Test fun authenticatesWithVeyonRsaKeyAndDecodesFramebufferAndInput()=runSession(true)
    @Test fun authenticatesWithRsaOaepLogonAndDecodesStreamingZlib()=runSession(false)
    @Test fun acceptsVeyonNulBannerButRepliesWithStandardNewline()=runSession(true,true)
    @Test fun snapshotUsesRgb332ZlibAndNeverRequestsAnotherFrame()=runSession(true,snapshot=true)
    @Test fun sendsLockAndUnlockWithoutRequestingFramebuffer()=runSession(true,featureOnly=true)
    @Test fun restoresPkcs8PrivateKeyAndRejectsPublicOrEncryptedKey() {
        val pair=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair()
        val pem="-----BEGIN PRIVATE KEY-----\n"+Base64.getEncoder().encodeToString(pair.private.encoded)+"\n-----END PRIVATE KEY-----"
        assertArrayEquals(pair.private.encoded,VeyonWire.privateKey(pem).encoded)
        for(bad in listOf("private-invalid-fixture","-----BEGIN ENCRYPTED PRIVATE KEY-----\nsecret")){
            try{VeyonWire.privateKey(bad);fail("bad private key accepted")}catch(e:RemoteError){assertEquals("INVALID_KEY",e.code);assertFalse(e.toString().contains(bad))}
        }
    }
    @Test fun refusesVncDowngradeWithoutSendingCredentials() {
        ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")).use { listener ->
            val worker=Executors.newSingleThreadExecutor { Thread(it, "synthetic-veyon").apply { isDaemon=true } };val peer=worker.submit<Unit>{listener.accept().use{s->s.soTimeout=3000;val o=s.getOutputStream();o.write("RFB 003.008\n".toByteArray());o.flush();val b=ByteArray(12);DataInputStream(s.getInputStream()).readFully(b);o.write(byteArrayOf(1,1));o.flush();assertEquals(-1,s.getInputStream().read())}}
            val client=VeyonClient(Socket("127.0.0.1",listener.localPort));val auth=VeyonClient.Auth("test","secret".toByteArray())
            try{client.handshake(auth);fail("downgrade accepted")}catch(e:RemoteError){assertEquals("NOT_VEYON",e.code)}finally{client.dispose()}
            assertTrue(auth.password?.all{it==0.toByte()}==true);peer.get(5,TimeUnit.SECONDS);worker.shutdownNow()
        }
    }
}
