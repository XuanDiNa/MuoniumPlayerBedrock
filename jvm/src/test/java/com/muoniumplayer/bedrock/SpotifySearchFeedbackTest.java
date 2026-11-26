package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.ncm.spotify.SpotifyMusicService;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpotifySearchFeedbackTest {
    @Test void missingLoginShowsAnActionableSearchNoticeInsteadOfOnlyAnEmptyList()throws Exception {
        // Upstream's diskless auth fixture prevents reading any user's credentials.
        var auth=Class.forName("com.muoniumplayer.core.ncm.spotify.SpotifyAuthStore");
        var clear=auth.getDeclaredMethod("clearForTest");clear.setAccessible(true);clear.invoke(null);
        assertTrue(SpotifyMusicService.search("owned fixture",10).isEmpty());
        var field=DownloadDynamicIsland.class.getDeclaredField("noticeValue");field.setAccessible(true);
        String notices=String.valueOf(field.get(null));
        var queue=DownloadDynamicIsland.class.getDeclaredField("NOTICE_QUEUE");queue.setAccessible(true);
        for(Object notice:(Iterable<?>)queue.get(null)) {
            var value=notice.getClass().getDeclaredField("value");value.setAccessible(true);notices+=value.get(notice);
        }
        assertTrue(notices.contains("Spotify")&&notices.contains("登录"),notices);
    }
}
