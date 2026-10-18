package com.openrad.mobile.remote

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.util.UUID

/** Fixed commands and binary file chunks only. Never connect to actual classroom devices. */
class VeyonCommandsTest {
    @Test fun commandsMatchIndependentQt55Fixtures() {
        val fixtures = JSONObject((javaClass.getResource("/fixtures/veyon-commands-qt55.json") ?: error("missing fixture")).readText()).getJSONArray("vectors")
        val transfer = UUID.fromString("11111111-2222-3333-4444-555555555555")
        for (i in 0 until fixtures.length()) {
            val f = fixtures.getJSONObject(i); val out = ByteArrayOutputStream(); val output = DataOutputStream(out)
            val send: (UUID, Int, Map<String, Any>) -> Unit = { uid, cmd, args -> VeyonWire.feature(output, uid, cmd, args) }
            when (f.getString("name")) {
                "shutdown", "reboot", "website" -> VeyonCommands.send(f.getString("name"), "https://example.org/lesson", send)
                "fileStart" -> send(VeyonCommands.FILES, 0, mapOf("0" to transfer, "1" to "урок.txt", "4" to false))
                "fileChunk" -> send(VeyonCommands.FILES, 1, mapOf("0" to transfer, "2" to byteArrayOf(0,1,2,-1)))
                "fileFinish" -> send(VeyonCommands.FILES, 3, mapOf("0" to transfer, "1" to "урок.txt", "3" to false))
                "fileStop" -> send(VeyonCommands.FILES, 5, emptyMap())
            }
            assertArrayEquals(f.getString("hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray(), out.toByteArray())
        }
    }
    @Test fun streamsBinaryChunksInOrderAndDoesNotOverwriteOrOpenFiles() {
        val bytes = ByteArray(190001) { (it % 251).toByte() }; val payload = ByteArrayOutputStream()
        val commands = ArrayList<Int>(); var transfer: Any? = null; var progress = 0L
        VeyonCommands.file("урок.xlsx", ByteArrayInputStream(bytes), { uid, command, args ->
            assertEquals(VeyonCommands.FILES, uid); commands.add(command)
            when(command) {
                0 -> { transfer = args["0"]; assertEquals(false,args["4"]); assertEquals("урок.xlsx",args["1"]) }
                1 -> { assertEquals(transfer,args["0"]); val chunk=args["2"] as ByteArray; assertTrue(chunk.size<=65536); payload.write(chunk) }
                3 -> { assertEquals(transfer,args["0"]); assertEquals(false,args["3"]) }
            }
        }, progress={progress=it})
        assertArrayEquals(bytes,payload.toByteArray()); assertEquals(bytes.size.toLong(),progress)
        assertEquals(listOf(0,1,1,1,3,5),commands)
    }
    @Test fun cancellationSendsCancelAndNeverSendsFinish() {
        var active=true;val commands=ArrayList<Int>()
        try {
            VeyonCommands.file("test.bin",ByteArrayInputStream(ByteArray(100000)),{_,cmd,_->commands.add(cmd);if(cmd==1)active=false},{active})
            fail("cancelled transfer completed")
        } catch(e:RemoteError){assertEquals("CANCELLED",e.code)}
        assertEquals(listOf(0,1,2,5),commands)
    }
    @Test fun unknownSizeStreamCannotExceedTheFileLimit() {
        val input=object:InputStream(){override fun read()=0;override fun read(b:ByteArray,off:Int,len:Int)=len}
        var sent=0L;val commands=ArrayList<Int>()
        try {
            VeyonCommands.file("bounded.bin",input,{_,cmd,args->commands.add(cmd);if(cmd==1)sent+=(args["2"] as ByteArray).size})
            fail("unbounded stream accepted")
        }catch(e:RemoteError){assertEquals("FILE_TOO_LARGE",e.code)}
        assertEquals(VeyonCommands.MAX_FILE_BYTES,sent);assertFalse(commands.contains(3));assertEquals(listOf(2,5),commands.takeLast(2))
    }
    @Test fun rejectsNonWebSchemesAndNormalizesFileNames() {
        for(url in listOf("file:///C:/secret","javascript:alert(1)","https://user:pass@example.org","not-a-url","https://example.org/\n")){
            try{VeyonCommands.website(url);fail("invalid URL accepted")}catch(e:RemoteError){assertEquals("INVALID_URL",e.code)}
        }
        assertEquals("https://example.org/%D1%83%D1%80%D0%BE%D0%BA",VeyonCommands.website("https://example.org/урок"))
        assertEquals("lesson.docx",VeyonCommands.fileName("../../lesson.docx"))
        assertEquals("lesson.docx",VeyonCommands.fileName("C:\\Users\\lesson.docx"))
        assertEquals("file_CON.txt",VeyonCommands.fileName("CON.txt"))
        assertEquals("a_b.txt",VeyonCommands.fileName("a:b.txt"))
    }
}
