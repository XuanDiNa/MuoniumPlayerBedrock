package com.muoniumplayer.bedrock;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class HostHudSyncTest {
    @TempDir Path temp;
    private JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
    @Test void savesOnlyChangedHudKeysAndPreservesUnrelatedSettings() throws Exception {
        Path config=temp.resolve("host.json");
        Files.writeString(config,"{\"infoXPercent\":0,\"volume\":44,\"openKey\":\"F7\"}");
        var sync=new HostHudSync(config);
        sync.observed(json(Files.readString(config)),json("{\"infoXPercent\":0}"));
        assertTrue(sync.save(json("{\"infoXPercent\":75}")));
        JsonObject result=json(Files.readString(config));
        assertEquals(75,result.get("infoXPercent").getAsInt());
        assertEquals(44,result.get("volume").getAsInt());
        assertEquals("F7",result.get("openKey").getAsString());
    }
    @Test void aConcurrentModMenuEditWinsForTheSameField() throws Exception {
        Path config=temp.resolve("host.json");Files.writeString(config,"{\"infoXPercent\":0}");
        var sync=new HostHudSync(config);
        sync.observed(json(Files.readString(config)),json("{\"infoXPercent\":0}"));
        Files.writeString(config,"{\"infoXPercent\":25,\"volume\":88}");
        assertTrue(sync.save(json("{\"infoXPercent\":75}")));
        assertEquals(25,json(Files.readString(config)).get("infoXPercent").getAsInt());
    }
    @Test void malformedFilesArePreservedAndNoPrivateStateIsExported() throws Exception {
        Path config=temp.resolve("host.json");Files.writeString(config,"{broken");
        var sync=new HostHudSync(config);sync.observed(json("{}"),json("{}"));
        assertFalse(sync.save(json("{\"infoXPercent\":25}")));
        assertEquals("{broken",Files.readString(config));
    }
    @Test void successiveEditorSavesDoNotMistakeOwnWriteForAModMenuEdit() throws Exception {
        Path config=temp.resolve("host.json");Files.writeString(config,"{\"infoXPercent\":0}");
        var sync=new HostHudSync(config);sync.observed(json("{\"infoXPercent\":0}"),json("{\"infoXPercent\":0}"));
        assertTrue(sync.save(json("{\"infoXPercent\":20}")));
        assertTrue(sync.save(json("{\"infoXPercent\":40}")));
        assertEquals(40,json(Files.readString(config)).get("infoXPercent").getAsInt());
    }
    @Test void windowsPublisherUiSwitchPersistsAlongsideUnrelatedHostKeys() throws Exception {
        Path config=temp.resolve("host.json");Files.writeString(config,"{\"smtcPublishEnabled\":false,\"miniPlayerKey\":\"F6\"}");
        var sync=new HostHudSync(config);
        sync.observed(json(Files.readString(config)),json("{\"smtcPublishEnabled\":false}"));
        assertTrue(sync.save(json("{\"smtcPublishEnabled\":true}")));
        assertTrue(json(Files.readString(config)).get("smtcPublishEnabled").getAsBoolean());
        assertEquals("F6",json(Files.readString(config)).get("miniPlayerKey").getAsString());
    }
}
