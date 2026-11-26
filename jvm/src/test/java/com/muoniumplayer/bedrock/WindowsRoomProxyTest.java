package com.muoniumplayer.bedrock;

import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WindowsRoomProxyTest {
    final URI room=URI.create("wss://room.invalid:443/room/private-token");
    @Test void selectsHttpsMappingAndHonorsWindowsBypassWithoutLookingUpTheTarget() throws Exception {
        var value=WindowsRoomProxy.parse("http=127.0.0.1:8080;https=127.0.0.1:8443","",room);
        assertEquals(RoomProxySettings.Mode.HTTP,value.mode());assertEquals(8443,value.port());
        assertEquals(RoomProxySettings.Mode.DIRECT,WindowsRoomProxy.parse("127.0.0.1:8080","*.invalid",room).mode());
        assertEquals(RoomProxySettings.Mode.DIRECT,WindowsRoomProxy.parse("127.0.0.1:8080","<local>",URI.create("wss://room/")).mode());
        assertEquals(RoomProxySettings.Mode.HTTP,WindowsRoomProxy.parse("127.0.0.1:8080","<local>",room).mode());
    }
    @Test void acceptsSocksAndUnmappedProtocolsMeanExplicitSystemDirect() throws Exception {
        assertEquals(RoomProxySettings.Mode.SOCKS5,WindowsRoomProxy.parse("socks=localhost:1080","",room).mode());
        assertEquals(RoomProxySettings.Mode.DIRECT,WindowsRoomProxy.parse("http=localhost:8080","",room).mode());
        assertThrows(Exception.class,()->WindowsRoomProxy.parse("https=secret@proxy:8080","",room));
    }
    @Test void windowsApiCanReadConfigurationWithoutChangingIt() throws Exception {
        // A real Windows API read only; no PAC lookup or network request.
        var config=WindowsRoomProxy.read();
        assertNotNull(config);assertNotNull(config.proxy());assertNotNull(config.bypass());
    }
}
