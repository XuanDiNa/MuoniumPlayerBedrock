package com.muoniumplayer.core.update;

import java.util.List;

/** Bedrock has no publisher announcement feed until its own service is supplied.
 * Keeps upstream UI linkage without reading old files or contacting its feed. */
public final class AnnouncementChecker {
    public static final int MAX_READ_IDS = 100;
    public static final String DEFAULT_LINK_TEXT = "查看详细说明";
    public static final class Snapshot {
        public List<Announcement> announcements() { return List.of(); }
        public List<Announcement> sponsorships() { return List.of(); }
        public List<Announcement> communities() { return List.of(); }
        public boolean hasContent() { return false; }
    }
    private AnnouncementChecker() {}
    public static void beginFetch(String version, String minecraft, String loader) {}
    public static boolean refresh() { return false; }
    public static List<Announcement> all() { return List.of(); }
    public static Snapshot snapshot() { return new Snapshot(); }
    public static boolean hasContent() { return false; }
    public static boolean isFetching() { return false; }
    public static boolean isResolved() { return true; }
    public static boolean isRead(String id) { return true; }
    public static int unreadCount() { return 0; }
    public static boolean hasUnread() { return false; }
    public static String unreadLabel() { return ""; }
    public static boolean shouldShow() { return false; }
    public static void markShownThisSession() {}
    public static void markAllRead() {}
    public static boolean isLinkAllowed(String link) { return false; }
    public static String linkText(Announcement announcement) { return DEFAULT_LINK_TEXT; }
}
