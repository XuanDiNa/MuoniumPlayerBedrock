package com.muoniumplayer.bedrock;

import java.nio.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CinematicSnapshotCacheTest {
    @Test void synchronizedSamplesHonorTheExisting250msProtocolLifetime(){
        var cache=new CinematicSnapshotCache();
        for(long age:new long[]{0,100,140,172,250}){
            var s=cache.read(CinematicStateTest.packet(),1000+age);
            assertNotNull(s,"Native state is still valid at age "+age);
            assertEquals(1000,s.at());assertEquals("validated",cache.status());
        }
        assertNull(cache.read(CinematicStateTest.packet(),1251));
        assertNull(cache.contended(1252));
    }
    @Test void contentionCannotRenewEitherLease(){
        var cache=new CinematicSnapshotCache();var state=cache.read(CinematicStateTest.packet(),1010);
        assertSame(state,cache.contended(1020));assertSame(state,cache.contended(1110));
        assertNull(cache.contended(1111));assertNull(cache.contended(1112));
        assertEquals(4,cache.busyReads());
        state=cache.read(CinematicStateTest.packet(),1200);assertNotNull(state);
        assertSame(state,cache.contended(1250));assertNull(cache.contended(1251));
        assertEquals("busy-state-expired",cache.status());
    }
    @Test void invalidPublicationImmediatelyRevokesAnOtherwiseYoungSample(){
        for(int field:new int[]{0,4,24,40,108}){
            var cache=new CinematicSnapshotCache();assertNotNull(cache.read(CinematicStateTest.packet(),1010));
            var invalid=CinematicStateTest.packet();ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(field,0);
            assertNull(cache.read(invalid,1011),"Invalid field "+field);
            assertNull(cache.contended(1012),"Do not resurrect the last valid sample");
        }
    }
    @Test void clockReversalAndRequestRevocationClearTheCache(){
        var cache=new CinematicSnapshotCache();cache.read(CinematicStateTest.packet(),1010);
        assertNull(cache.contended(1009));
        cache.read(CinematicStateTest.packet(),1010);cache.clear("not-requested");
        assertNull(cache.contended(1011));
        assertNull(cache.read(CinematicStateTest.packet(),999));
    }
    @Test void replacementInputAndWorldAreNeverHiddenByPreviousValues(){
        var cache=new CinematicSnapshotCache();var session=new CinematicState.Session();
        session.changeReason(cache.read(CinematicStateTest.packet(),1010));
        var next=CinematicStateTest.packet();var b=ByteBuffer.wrap(next).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(32,78);assertEquals("native-input",session.changeReason(cache.read(next,1011)));
        b.putLong(24,9);assertEquals("native-context",session.changeReason(cache.read(next,1012)));
        b.putFloat(108,.5f);assertEquals("hurt",session.changeReason(cache.read(next,1013)));
    }
}
