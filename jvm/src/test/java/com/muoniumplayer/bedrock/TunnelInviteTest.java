package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TunnelInviteTest {
    static final String TOKEN="0123456789abcdef0123456789abcdef";
    static final String URL="https://fixture-test.trycloudflare.com";
    @Test void encryptedInviteRoundTripsWithoutLosingRoomPasswordOrCode() {
        String invite=TunnelInvite.create(URL,TOKEN,"abcdefgh","ABC123");
        var parsed=TunnelInvite.parse(invite);
        assertEquals("wss://fixture-test.trycloudflare.com/room/"+TOKEN,parsed.endpoint().toString());
        assertEquals("abcdefgh",parsed.password());assertEquals("ABC123",parsed.roomCode());
        assertEquals("127.0.0.1:25001#abcdefgh#ABC123",parsed.localInvite(25001));
    }
    @Test void malformedAndInsecureInvitesAreRejected() {
        String good=TunnelInvite.create(URL,TOKEN,"abcdefgh","");
        for(String bad:new String[]{"",good.replace("wss:","ws:"),good.replace("fixture-test.trycloudflare.com","example.org"),
            good.replace("/room/","/other/"),good+"#extra#extra",good.replace("abcdefgh","bad"),
            good.replace("wss://","wss://user:pass@"),good.replace("/room/","/?q=/room/"),
            good.replace(TOKEN,"short"),good.replace(".com/",".com:8443/")})
            assertThrows(IllegalArgumentException.class,()->TunnelInvite.parse(bad),bad);
        assertFalse(TunnelInvite.isTunnel("127.0.0.1:24500#abcdefgh"));
        assertTrue(TunnelInvite.isTunnel(good));
    }
}
