package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.ncm.music.MusicPlatform;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Upstream261RegressionTest {
    @Test void bodianCanBeSelectedWithoutChangingStoredPlatformOrdinals() {
        String[] old={"NETEASE","QQ","KUGOU","KUWO","MIGU","YOUTUBE","BILIBILI","QISHUI","GD","SMTC","SPOTIFY","APPLE","LOCAL"};
        for(int i=0;i<old.length;i++)assertEquals(old[i],MusicPlatform.values()[i].name());
        assertEquals(old.length,MusicPlatform.valueOf("BODIAN").ordinal());
    }
    @Test void normalizationAttenuatesLoudTracksAndCannotAmplifyBeyondSamplePeak()throws Exception {
        Class<?> type=Class.forName("com.muoniumplayer.core.ncm.music.TrackLoudnessNormalization");
        var gain=type.getDeclaredMethod("gainFor",float.class,float.class);gain.setAccessible(true);
        float loud=(float)gain.invoke(null,-8f,0.9f);
        assertTrue(loud>0.2f&&loud<0.4f);
        float quiet=(float)gain.invoke(null,-30f,0.5f);
        assertTrue(quiet>=1f&&quiet<=2f,"Gain must respect available peak headroom");
    }
}
