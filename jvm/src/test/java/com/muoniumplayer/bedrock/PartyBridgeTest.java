package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PartyBridgeTest {
    @TempDir Path temporary;
    @Test void ownsStableExplicitlyLocalIdentityAndNoBedrockTransport() throws Exception {
        var a=PartyBridge.identity(temporary.resolve("identity.json"));
        var b=PartyBridge.identity(temporary.resolve("identity.json"));
        assertEquals(a,b);assertTrue(a.name().startsWith("Muonium-"));
        var host=PartyBridge.host(a);assertFalse(host.canSend());
        assertEquals(a.id(),host.selfUuid());assertEquals(a.name(),host.selfName());
        assertThrows(IllegalStateException.class,()->host.send(new byte[]{1}));
    }
}
