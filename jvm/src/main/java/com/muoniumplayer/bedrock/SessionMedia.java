package com.muoniumplayer.bedrock;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;

/** File-based original codecs need a seekable working area, never a durable
 * media library. Each owned JVM has an exclusive session; previous sessions
 * are reclaimed only after acquiring their lock. No user-selected paths. */
public final class SessionMedia {
    private static Path directory;
    private static FileChannel channel;
    private static FileLock lock;
    public static synchronized File directory() {
        if (directory != null) return directory.toFile();
        javax.imageio.ImageIO.setUseCache(false);
        Path parent = Path.of("session-media").toAbsolutePath().normalize();
        try {
            requirePlainRoot(parent);
            Files.createDirectories(parent);
            reclaimStale(parent);
            directory = parent.resolve("session-" + UUID.randomUUID());
            Files.createDirectory(directory);
            channel = FileChannel.open(directory.resolve(".lock"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null) throw new IOException("Media session already owned");
            Runtime.getRuntime().addShutdownHook(new Thread(SessionMedia::close, "Media cleanup"));
            System.out.println("MEDIA_POLICY scope=session retention=none imageio-disk=false codec-work=session-only");
            return directory.toFile();
        } catch (IOException e) { throw new IllegalStateException("Session media unavailable", e); }
    }
    static void reclaimStale(Path parent) throws IOException {
        requirePlainRoot(parent);
        try (var children = Files.newDirectoryStream(parent, "session-*")) {
            for (Path child : children) {
                if (!child.getFileName().toString().matches("session-[0-9a-f-]{36}") || isLink(child)
                        || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) continue;
                Path marker = child.resolve(".lock");
                if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || isLink(marker)) continue;
                try (var stream = FileChannel.open(marker, StandardOpenOption.WRITE)) {
                    try (var lease = stream.tryLock()) {
                        if (lease == null) continue;
                        clear(child, false);
                    }
                } catch (java.nio.channels.OverlappingFileLockException | IOException busy) { continue; }
                Files.deleteIfExists(marker);
                try { Files.delete(child); } catch (DirectoryNotEmptyException raced) { /* Another owner: preserve. */ }
            }
        }
    }
    static boolean isLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) return true;
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        // Windows directory junctions are not Java symbolic links. Refuse all
        // reparse points before walking/deleting, including mount junctions.
        int attributes = Kernel32.INSTANCE.GetFileAttributes(path.toAbsolutePath().toString());
        if (attributes == WinBase.INVALID_FILE_ATTRIBUTES) throw new IOException("Media attributes unavailable");
        return (attributes & 0x400) != 0;
    }
    static void requirePlainRoot(Path root) throws IOException {
        for (Path part = root.toAbsolutePath().normalize(); part != null; part = part.getParent())
            if (isLink(part)) throw new IOException("Media working root contains a reparse point");
        if (Files.exists(root) && !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Media working root is not a plain directory");
    }
    private static void clear(Path root, boolean marker) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (isLink(dir)) {
                    if (dir.equals(root)) throw new IOException("Media session changed to a reparse point");
                    Files.delete(dir); return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (marker || !file.equals(root.resolve(".lock"))) Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                if (!dir.equals(root)) Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
    public static synchronized void close() {
        ProgressiveAudio.closePending();
        if (directory == null) return;
        try { clear(directory, false); } catch (IOException e) { System.err.println("MEDIA_POLICY cleanup deferred"); }
        try { if (lock != null) lock.release(); if (channel != null) channel.close();
            Files.deleteIfExists(directory.resolve(".lock")); Files.deleteIfExists(directory);
        } catch (IOException e) { /* Owned session is reclaimed at next launch. */ }
        directory = null; channel = null; lock = null;
    }
}
