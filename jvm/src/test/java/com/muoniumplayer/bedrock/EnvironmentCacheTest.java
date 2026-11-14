package com.muoniumplayer.bedrock;
import java.nio.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EnvironmentCacheTest {
    @Test void sameNativeSequenceDecodesOnceAndNewCountsSessionExpiryRemainFresh() {
        var cache=new EnvironmentSnapshotCache();var raw=EnvironmentFrameTest.packet();
        var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);var copies=new AtomicInteger();
        java.util.function.Supplier<byte[]> read=()->{copies.incrementAndGet();return raw;};
        var f=cache.read(1,1000,7,1,1001,7,read);assertNotNull(f);
        for(int i=0;i<2000;i++)assertSame(f,cache.read(1,1000,7,1,1001,7,read));
        assertEquals(1,copies.get(),"Unchanged 75KB snapshots were recopied/decoded every overlay frame");
        b.putLong(8,2).putInt(132,2);
        var next=cache.read(2,1000,7,1,1001,7,read);assertNotSame(f,next);assertEquals(2,next.ambient().enemyCount);
        assertEquals(2,copies.get());assertNull(cache.read(2,1000,7,1,3001,7,read));
        assertNull(cache.read(2,1000,7,1,1001,8,read));
        assertNull(cache.read(2,1000,7,0,1001,7,read));
        cache.clear();assertNotNull(cache.read(2,1000,7,1,1001,7,read));assertEquals(3,copies.get());
    }
}
