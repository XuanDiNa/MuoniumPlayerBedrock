package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A busy mutex must not restart the camera's idle timer every scan. */
class EnvironmentContentionTest {
    private final EnvironmentSnapshotCache cache=new EnvironmentSnapshotCache();
    private EnvironmentFrame publish(long now){
        return cache.read(1,1000,7,1,now,7,EnvironmentFrameTest::packet);
    }
    @Test void contentionReusesOnlyABoundedLeaseWithoutRenewingIt(){
        var frame=publish(1010);assertNotNull(frame);
        assertSame(frame,cache.contended(1020,7));
        assertSame(frame,cache.contended(1110,7));
        assertNull(cache.contended(1111,7));
        assertNull(cache.contended(1112,7));
    }
    @Test void foreignWorldExplicitRevocationAndClockRollbackNeverReuse(){
        publish(1010);assertNull(cache.contended(1020,8));
        publish(1010);assertNull(cache.read(2,1020,7,0,1020,7,()->null));
        assertNull(cache.contended(1021,7));
        publish(1010);assertNull(cache.contended(1009,7));
        publish(1010);cache.clear();assertNull(cache.contended(1020,7));
    }
    @Test void leaseCannotExtendAnExpiredPublication(){
        assertNotNull(publish(2999));
        assertNull(cache.contended(3001,7));
    }
}
