package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TunnelInviteTest {
    static final String TOKEN="0123456789abcdef0123456789abcdef";
    static final String URL="https://fixture-test.trycloudflare.com";
    @Test void portPasswordFragmentReportsTheMissingAddressWithoutStartingAConnection() {
        for(String fragment:new String[]{"54883#fixture8", " 54883#fixture8 ", "54883#fixture8#ABC123"}) {
            String error=RoomTunnel.joinRoom(fragment,ignored->fail("Incomplete invites must not start background work"));
            assertNotNull(error);
            assertTrue(error.contains("缺少房主地址"),error);
            assertFalse(RoomTunnel.busy());
            assertFalse(com.muoniumplayer.party.direct.MpDirect.isPendingJoin());
        }
    }
    @Test void readyPublicInvitationCanBeCopiedWithoutAManualHostAddress() throws Exception {
        var invite=RoomTunnel.class.getDeclaredField("invite");invite.setAccessible(true);
        String previous=(String)invite.get(null);
        try {
            String complete=TunnelInvite.create(URL,TOKEN,"abcdefgh","ABC123");
            invite.set(null,complete);
            assertEquals(complete,RoomTunnel.completeInvitation(""));
            assertEquals(complete,RoomTunnel.completeInvitation("old-host.invalid"));
            assertFalse(RoomTunnel.busy(),"Copying must not start a helper or connection");
        } finally {invite.set(null,previous);}
    }
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
