package com.muoniumplayer.bedrock;

import com.google.gson.JsonParser;
import com.muoniumplayer.core.ncm.music.SourceFailureDetails;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceFailureDetailsTest {
    @Test void bilibiliHttpRejectionIsScopedToTheExactVideoAndCanBeCleared() {
        SourceFailureDetails.bilibiliFailed("BVCaseSensitive",new SourceFailureDetails.ApiHttpException("bilibili",412));
        assertTrue(SourceFailureDetails.lookup("bilibili","BVCaseSensitive").contains("412"));
        assertEquals("",SourceFailureDetails.lookup("bilibili","BVcasesensitive"));
        assertEquals("",SourceFailureDetails.lookup("kugou","BVCaseSensitive"));
        SourceFailureDetails.clear("bilibili","BVCaseSensitive");
        assertEquals("",SourceFailureDetails.lookup("bilibili","BVCaseSensitive"));
    }
    @Test void unrelatedOrRawRemoteErrorsAreNotMislabelledAsBilibiliHttpFailures() {
        SourceFailureDetails.bilibiliFailed("owned",new SourceFailureDetails.ApiHttpException("qq",403));
        assertEquals("",SourceFailureDetails.lookup("bilibili","owned"));
        SourceFailureDetails.bilibiliFailed("owned",new RuntimeException("https://remote.example/?token=secret"));
        assertEquals("",SourceFailureDetails.lookup("bilibili","owned"));
    }
    @Test void kugouPackageDenialIndicatesAccountEntitlementWithoutClaimingAllSongsRequireVip() {
        SourceFailureDetails.kugouDenied("aabb",JsonParser.parseString("{\"fail_process\":[\"pkg\",\"buy\"]}").getAsJsonObject());
        assertTrue(SourceFailureDetails.lookup("kugou","AABB").contains("授权"));
        assertEquals("",SourceFailureDetails.lookup("kugou","other-hash"));
        SourceFailureDetails.kugouDenied("unknown",JsonParser.parseString("{\"fail_process\":[\"unknown-server-message\"]}").getAsJsonObject());
        assertEquals("",SourceFailureDetails.lookup("kugou","unknown"));
    }
    @Test void sessionDiagnosticStorageIsBoundedAndNeverUsedAsMusicCache() {
        for(int i=0;i<140;i++)SourceFailureDetails.kugouRestricted("bounded-"+i);
        assertEquals("",SourceFailureDetails.lookup("kugou","bounded-0"));
        assertFalse(SourceFailureDetails.lookup("kugou","bounded-139").isEmpty());
    }
}
