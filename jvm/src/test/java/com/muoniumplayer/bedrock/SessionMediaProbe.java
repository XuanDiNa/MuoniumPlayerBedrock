package com.muoniumplayer.bedrock;

/** Disposable subprocess fixture, never part of the production distribution. */
public final class SessionMediaProbe {
    public static void main(String[] args) throws Exception {
        var root = SessionMedia.directory().toPath();
        java.nio.file.Files.writeString(root.resolve("audio.flac"), "fixture");
        System.out.println("READY=" + root); System.out.flush();
        if (args.length > 0 && args[0].equals("exit")) return;
        Thread.sleep(30_000);
    }
}
