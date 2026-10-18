package com.openrad.mobile.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteInputTest {
    @Test fun viewOnlyAndClosingANeverControlledSessionSendNoPackets() {
        val packets=ArrayList<String>();val gate=RemoteInput({x,y,b->packets.add("p:$x:$y:$b")},{k,d->packets.add("k:$k:$d")})
        for(action in listOf<()->Unit>({gate.pointer(30,40,1)},{gate.key(65,true)})){
            try{action();fail("input while viewing")}catch(e:RemoteError){assertEquals("CONTROL_REQUIRED",e.code)}
        }
        gate.release();gate.setEnabled(false);assertTrue(packets.isEmpty())
    }
    @Test fun disablingReleasesHeldKeysAndMouseAtLastCoordinatesOnlyOnce() {
        val packets=ArrayList<String>();val gate=RemoteInput({x,y,b->packets.add("p:$x:$y:$b")},{k,d->packets.add("k:$k:$d")})
        gate.setEnabled(true);gate.key(0xffe3,true);gate.pointer(800,600,1);gate.setEnabled(false);gate.release()
        assertEquals(listOf("k:65507:true","p:800:600:1","k:65507:false","p:800:600:0"),packets)
        assertFalse(gate.enabled)
    }
    @Test fun releaseContinuesAfterAPartialNetworkWriteFailure() {
        val released=ArrayList<Int>();val gate=RemoteInput({_,_,_->},{k,d->if(!d){released.add(k);if(k==1)throw java.io.IOException("synthetic failure")}})
        gate.setEnabled(true);gate.key(1,true);gate.key(2,true);gate.release()
        assertEquals(listOf(1,2),released);assertFalse(gate.enabled)
    }
}
