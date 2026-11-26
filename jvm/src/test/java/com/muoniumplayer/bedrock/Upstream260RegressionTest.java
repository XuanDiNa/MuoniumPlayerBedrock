package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.settings.HudConfig;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Public-host regressions for the pinned upstream development update. */
class Upstream260RegressionTest {
    @Test void minimumPlaybackBeforeAutomaticMixScalesWithTrackLength() throws Exception {
        Class<?> planner = Class.forName("com.muoniumplayer.core.ncm.music.AutomixPlanner");
        Method minimum = planner.getDeclaredMethod("minimumPlayedMillis", long.class);
        minimum.setAccessible(true);
        assertEquals(50_000L, minimum.invoke(null, 70_000L));
        assertEquals(500_000L, minimum.invoke(null, 700_000L));
        assertEquals(5_000_000L, minimum.invoke(null, 7_000_000L));
    }

    @Test void musicCapsuleMaximumWidthIsIndependentFromBaseIslandWidth() throws Exception {
        var maximum = HudConfig.class.getField("islandMusicCapsuleMaxWidth");
        var base = HudConfig.class.getField("dynamicIslandMaxWidth");
        float previous = maximum.getFloat(null), previousBase = base.getFloat(null);
        try {
            maximum.setFloat(null, 560f);
            assertEquals(previousBase, base.getFloat(null));
            assertEquals(560f, maximum.getFloat(null));
        } finally { maximum.setFloat(null, previous); }
    }

    @Test void lyricsFollowTheSingingClockRatherThanAnInfiniteMarquee() throws Exception {
        Class<?> type = Class.forName("com.muoniumplayer.core.rendering.MusicCapsuleLyrics");
        Method follow = type.getDeclaredMethod("followOffset", double.class, double.class, double.class);
        follow.setAccessible(true);
        double first = (double) follow.invoke(null, 800d, 200d, 0d);
        double middle = (double) follow.invoke(null, 800d, 200d, 450d);
        double end = (double) follow.invoke(null, 800d, 200d, 800d);
        assertEquals(0d, first);
        assertTrue(middle > first && middle <= end);
        assertEquals(600d, end);
    }
}
