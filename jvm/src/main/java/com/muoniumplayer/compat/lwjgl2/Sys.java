package com.muoniumplayer.compat.lwjgl2;
import java.awt.Desktop;
import java.net.URI;
public final class Sys {
    public static boolean openURL(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) return false;
            Desktop.getDesktop().browse(uri); return true;
        } catch (Exception e) { System.err.println("[Muonium/URL] " + e); return false; }
    }
}
