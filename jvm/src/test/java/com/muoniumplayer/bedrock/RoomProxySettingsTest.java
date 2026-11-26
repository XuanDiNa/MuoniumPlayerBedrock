package com.muoniumplayer.bedrock;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RoomProxySettingsTest {
    @TempDir Path root;
    @Test void openingDoesNotCreateFilesAndSavedProxySurvivesRestart() throws Exception {
        var settings=new RoomProxySettings(root);
        assertEquals(RoomProxySettings.Mode.DIRECT,settings.get().mode());
        assertFalse(Files.exists(root.resolve("config")),"Opening a page must not save or connect");
        settings.save(new RoomProxySettings.Value(RoomProxySettings.Mode.SOCKS5,"127.0.0.1",1080));
        var restarted=new RoomProxySettings(root);
        assertEquals(RoomProxySettings.Mode.SOCKS5,restarted.get().mode());
        assertEquals("127.0.0.1",restarted.get().host());assertEquals(1080,restarted.get().port());
    }
    @Test void malformedOrUnsupportedConfigurationNeverSilentlyBecomesDirect() throws Exception {
        Path config=root.resolve("config/muonium/room-proxy.json");Files.createDirectories(config.getParent());
        for(String json:new String[]{"{", "{\"mode\":\"UNKNOWN\"}",
                "{\"mode\":\"HTTP\",\"host\":\"127.0.0.1\",\"port\":0}",
                "{\"mode\":\"DIRECT\",\"mode\":\"SOCKS5\"}"}) {
            Files.writeString(config,json);
            var settings=new RoomProxySettings(root);
            assertThrows(IllegalStateException.class,settings::get,json);
            settings.save(new RoomProxySettings.Value(RoomProxySettings.Mode.DIRECT,"",0));
            assertEquals(RoomProxySettings.Mode.DIRECT,new RoomProxySettings(root).get().mode());
        }
    }
    @Test void rejectsUrlCredentialsControlCharactersAndOutOfRangePorts() {
        for(String host:new String[]{"http://127.0.0.1","user:secret@proxy", "a\r\nb", "host/path", ""})
            assertThrows(IllegalArgumentException.class,()->new RoomProxySettings.Value(RoomProxySettings.Mode.HTTP,host,8080));
        for(int port:new int[]{-1,0,65536})assertThrows(IllegalArgumentException.class,
                ()->new RoomProxySettings.Value(RoomProxySettings.Mode.SOCKS5,"localhost",port));
    }
    @Test void failedSaveDoesNotReplaceLiveConfiguration() throws Exception {
        var settings=new RoomProxySettings(root);var before=settings.get();
        Files.writeString(root.resolve("config"),"not a directory");
        assertThrows(Exception.class,()->settings.save(new RoomProxySettings.Value(RoomProxySettings.Mode.HTTP,"localhost",8080)));
        assertEquals(before,settings.get());
    }
}
