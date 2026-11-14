package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.settings.HudConfig;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class Upstream252AudioTest {
    @Test void slowDecoderDisposalDoesNotBlockTheCallerAndRetainsReleaseOrder() throws Exception {
        var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);var last=new CountDownLatch(1);
        try {
            AudioPlayer.releaseLater(()->{entered.countDown();try{finish.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            AudioPlayer.releaseLater(last::countDown);
            assertEquals(1,last.getCount(),"Cleanup stays serialized behind the stalled old decoder");
        } finally {finish.countDown();}
        assertTrue(last.await(2,TimeUnit.SECONDS));
    }
    @Test void mirrorCalibrationHasIndependentOverridesAndCanReturnToAutomatic() {
        int delay=HudConfig.smtcTimelineDelayMs,window=HudConfig.smtcTimelineWindowMs;
        try {
            SmtcTimelineStabilizer.setUserDelayMs(710);SmtcTimelineStabilizer.setUserWindowMs(6000);
            assertEquals(710,SmtcTimelineStabilizer.effectiveDelayMs("AppleMusic"));
            assertEquals(6000,SmtcTimelineStabilizer.effectiveWindowMs("unknown-player"));
            SmtcTimelineStabilizer.setUserDelayMs(-1);SmtcTimelineStabilizer.setUserWindowMs(-1);
            assertEquals(450,SmtcTimelineStabilizer.effectiveDelayMs("AppleMusic"));
            assertEquals(500,SmtcTimelineStabilizer.effectiveWindowMs("unknown-player"));
            assertEquals(-1,HudConfig.smtcTimelineDelayMs);assertEquals(-1,HudConfig.smtcTimelineWindowMs);
        } finally {SmtcTimelineStabilizer.setUserDelayMs(delay);SmtcTimelineStabilizer.setUserWindowMs(window);}
    }
}
