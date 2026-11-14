package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.screens.GuiAnnouncements;
import com.muoniumplayer.core.update.AnnouncementChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnnouncementPolicyTest {
    @Test void originalFeedAndAllLegacyEntrypointsRemainInactive() {
        AnnouncementChecker.beginFetch("2.5.0", "26.3", "fabric");
        assertFalse(AnnouncementChecker.refresh());
        assertTrue(AnnouncementChecker.all().isEmpty());
        assertFalse(AnnouncementChecker.isFetching());
        assertFalse(AnnouncementChecker.shouldShow());
        assertEquals(0, AnnouncementChecker.unreadCount());
        assertFalse(AnnouncementChecker.isLinkAllowed("https://example.com"));
        var client=BedrockClient.getInstance();
        client.bindWindow(0);
        var before=client.gui.screen();
        GuiAnnouncements.open(null);
        GuiAnnouncements.tickAutoShow(client);
        assertSame(before,client.gui.screen(),"Retired entry points must not replace the current screen");
        assertTrue(Thread.getAllStackTraces().keySet().stream()
            .noneMatch(t->t.getName().startsWith("muonium-announcement-fetch")));
    }
}
