package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.settings.ConfigPaths;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MediaPolicyTest {
    @Test void mediaIsNotRetainedInPersistentUserState() {
        Path cache = ConfigPaths.CACHE_DIR.toPath().toAbsolutePath().normalize();
        assertTrue(cache.toString().contains("session-media"), "Regenerable media must be session-only: " + cache);
        assertFalse(ConfigPaths.NETEASE_ACCOUNTS.toPath().toAbsolutePath().normalize().startsWith(cache));
        assertFalse(ConfigPaths.PLAYER.toPath().toAbsolutePath().normalize().startsWith(cache));
    }
    @Test void imageDecodingDoesNotCreateImageIoDiskCaches() {
        ConfigPaths.CACHE_DIR.getPath();
        assertFalse(javax.imageio.ImageIO.getUseCache(), "ImageIO disk buffering must be disabled before original player init");
    }
    @Test void cleanupPreservesActiveSessionsAndUserFiles() throws Exception {
        Path root = Files.createTempDirectory("muonium-session-test-");
        Path active = Files.createDirectory(root.resolve("session-00000000-0000-0000-0000-000000000001"));
        Path stale = Files.createDirectory(root.resolve("session-00000000-0000-0000-0000-000000000002"));
        Path outside = Files.writeString(root.resolve("keep.json"), "settings");
        Files.writeString(stale.resolve("audio.flac"), "old");
        Files.writeString(stale.resolve(".lock"), "");
        Files.writeString(active.resolve("audio.flac"), "playing");
        try (var channel = java.nio.channels.FileChannel.open(active.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            SessionMedia.reclaimStale(root);
            assertFalse(Files.exists(stale));
            assertTrue(Files.exists(active.resolve("audio.flac")));
            assertEquals("settings", Files.readString(outside));
        }
        SessionMedia.reclaimStale(root);
        assertFalse(Files.exists(active));
        Files.delete(outside); Files.delete(root);
    }
    @Test void aMatchingFolderWithoutAnOwnershipMarkerIsPreserved() throws Exception {
        Path root = Files.createTempDirectory("muonium-unowned-session-");
        Path unowned = Files.createDirectory(root.resolve("session-00000000-0000-0000-0000-000000000005"));
        Path keep = Files.writeString(unowned.resolve("keep.json"), "keep");
        try {
            SessionMedia.reclaimStale(root);
            assertTrue(Files.exists(keep), "An unmarked folder must not be adopted and deleted");
        } finally {
            Files.deleteIfExists(keep); Files.deleteIfExists(unowned.resolve(".lock"));
            Files.deleteIfExists(unowned); Files.deleteIfExists(root);
        }
    }
}
