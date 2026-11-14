package com.muoniumplayer.bedrock;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionLinkPolicyTest {
    @Test void junctionWorkingRootCannotReclaimFilesInAnotherDirectory() throws Exception {
        Path fixture = Files.createTempDirectory("muonium-root-junction-");
        Path outside = Files.createTempDirectory("muonium-unrelated-");
        Path stale = Files.createDirectories(outside.resolve("session-00000000-0000-0000-0000-000000000003"));
        Files.writeString(stale.resolve(".lock"), "");
        Path keep = Files.writeString(stale.resolve("keep.flac"), "untouched");
        Path link = fixture.resolve("session-media");
        junction(link, outside);
        try {
            assertThrows(java.io.IOException.class, () -> SessionMedia.reclaimStale(link),
                    "A working-root junction must be refused before enumeration");
            assertEquals("untouched", Files.readString(keep));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(keep); Files.deleteIfExists(stale.resolve(".lock"));
            Files.deleteIfExists(stale); Files.deleteIfExists(outside); Files.deleteIfExists(fixture);
        }
    }
    @Test void junctionInsideAStaleSessionDoesNotDeleteItsTargetFiles() throws Exception {
        Path fixture = Files.createTempDirectory("muonium-inner-junction-");
        Path outside = Files.createTempDirectory("muonium-other-files-");
        Path keep = Files.writeString(outside.resolve("keep.flac"), "untouched");
        Path stale = Files.createDirectories(fixture.resolve("session-00000000-0000-0000-0000-000000000004"));
        Files.writeString(stale.resolve(".lock"), "");
        Path link = stale.resolve("linked"); junction(link, outside);
        try {
            SessionMedia.reclaimStale(fixture);
            assertEquals("untouched", Files.readString(keep));
            assertFalse(Files.exists(stale));
        } finally {
            Files.deleteIfExists(link); Files.deleteIfExists(stale.resolve(".lock"));
            Files.deleteIfExists(stale); Files.deleteIfExists(fixture);
            Files.deleteIfExists(keep); Files.deleteIfExists(outside);
        }
    }
    private static void junction(Path link, Path target) throws Exception {
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), String.join("\n", process.inputReader().lines().toList()));
    }
}
