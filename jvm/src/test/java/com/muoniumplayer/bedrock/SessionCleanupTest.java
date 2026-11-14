package com.muoniumplayer.bedrock;

import java.nio.file.*;
import java.io.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionCleanupTest {
    @Test void normalExitRemovesOwnedTemporaryAudio() throws Exception {
        exercise(false);
    }
    @Test void nextLaunchReclaimsOnlyTheKilledPlayersTemporaryAudio() throws Exception {
        exercise(true);
    }
    private static void exercise(boolean kill) throws Exception {
        Path fixture = Files.createTempDirectory("muonium-disposable-player-");
        Path preserved = Files.writeString(fixture.resolve("playlist.json"), "keep");
        String cp = Path.of(SessionMediaProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator
                + Path.of(SessionMedia.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator
                + Path.of(com.sun.jna.Native.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator
                + Path.of(com.sun.jna.platform.win32.Kernel32.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", cp, SessionMediaProbe.class.getName(), kill ? "wait" : "exit")
                .directory(fixture.toFile()).redirectErrorStream(true).start();
        try {
            var reader = child.inputReader(); String line; Path session = null; var output = new StringBuilder();
            while ((line = reader.readLine()) != null) { output.append(line).append('\n'); if (line.startsWith("READY=")) {
                session = Path.of(line.substring(6)); break;
            } }
            assertNotNull(session, "Fixture must create a real owned session: " + output);
            assertTrue(session.startsWith(fixture));
            if (kill) {
                SessionMedia.reclaimStale(fixture.resolve("session-media"));
                assertTrue(Files.exists(session.resolve("audio.flac")), "Live playback must survive a stale sweep");
                child.destroyForcibly();
            }
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            if (kill) {
                assertTrue(Files.exists(session.resolve("audio.flac")), "Hard exit must leave a reclaimable session");
                SessionMedia.reclaimStale(fixture.resolve("session-media"));
            }
            assertFalse(Files.exists(session), "Normal exit / stale recovery must remove owned audio");
            assertEquals("keep", Files.readString(preserved));
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS); }
            if (Files.isDirectory(fixture.resolve("session-media"))) SessionMedia.reclaimStale(fixture.resolve("session-media"));
            Files.deleteIfExists(fixture.resolve("session-media"));
            Files.deleteIfExists(preserved); Files.deleteIfExists(fixture);
        }
    }
}
