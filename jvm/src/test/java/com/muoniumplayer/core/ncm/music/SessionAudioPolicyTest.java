package com.muoniumplayer.core.ncm.music;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionAudioPolicyTest {
    @Test void sweepsOlderTracksWhilePreservingTheTwoMostRecentDecoderGroups() throws Exception {
        Path root = MusicAudioCache.directory().toPath(); Files.createDirectories(root);
        for (int i = 0; i < 5; i++) {
            Path file = Files.write(root.resolve("policy" + i + ".flac"), new byte[32]);
            MusicAudioCache.touch(file.toFile());
        }
        try {
            MusicAudioCache.enforceLimit();
            for (int i = 0; i < 3; i++) assertFalse(Files.exists(root.resolve("policy" + i + ".flac")));
            for (int i = 3; i < 5; i++) assertTrue(Files.exists(root.resolve("policy" + i + ".flac")));
        } finally {
            for (int i = 0; i < 5; i++) Files.deleteIfExists(root.resolve("policy" + i + ".flac"));
        }
    }
}
