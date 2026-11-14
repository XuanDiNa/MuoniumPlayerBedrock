package com.muoniumplayer.bedrock;
import java.util.Arrays;
import java.nio.file.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LyricRefreshTest {
    @TempDir Path root;
    @Test void hud3dCategoryExposesFrameRateChoice() {
        var category=Arrays.stream(com.muoniumplayer.core.screens.hud.HudSettingsModel.categories())
            .filter(c->c.title.equals("3D 歌词")).findFirst().orElseThrow();
        var entry=Arrays.stream(category.groups).flatMap(g->Arrays.stream(g.entries))
            .filter(e->e.getLabel().equals("3D 字幕帧率")).findFirst();
        assertTrue(entry.isPresent(),"HUD editor must expose the 3D frame-rate selector");
        var policy=LyricRefresh.global();int previous=policy.index();
        try {
            assertArrayEquals(new String[]{"60","120","144","165","240","无上限"},entry.get().getChoices());
            for(int i=0;i<6;i++) {
                entry.get().setChoice(LyricRefresh.labels()[i]);
                assertEquals(i,policy.index());assertEquals(LyricRefresh.labels()[i],entry.get().getValueText());
            }
        }finally{policy.setIndex(previous);}
    }
    @Test void savesOnlyOwnedPreferenceAndReopensAllChoices()throws Exception {
        var policy=new LyricRefresh(root);assertEquals(5,policy.index());
        assertFalse(Files.exists(root.resolve("config/muonium/lyric-refresh.json")));
        Path other=root.resolve("unrelated.json");Files.writeString(other,"preserve");
        for(int i=0;i<6;i++){policy.setIndex(i);assertEquals(i,new LyricRefresh(root).index());}
        assertEquals("preserve",Files.readString(other));
        assertThrows(IllegalArgumentException.class,()->policy.setIndex(-1));
        assertThrows(IllegalArgumentException.class,()->policy.setIndex(6));
        var labels=LyricRefresh.labels();labels[0]="invalid";assertEquals("60",LyricRefresh.labels()[0]);
    }
    @Test void corruptPreferencesFallBackToUncappedWithoutRewritingThem()throws Exception {
        var p=root.resolve("config/muonium/lyric-refresh.json");Files.createDirectories(p.getParent());
        for(String s:new String[]{"{\"choice\":6}","{\"choice\":-1}","{\"choice\":1.5}","{\"choice\":\"1\"}","[]","garbage"}){
            Files.writeString(p,s);assertEquals(5,new LyricRefresh(root).index());assertEquals(s,Files.readString(p));
        }
    }
    @Test void capCannotBeBypassedBy360HzCameraNotificationsAndNeverQueuesCatchup() {
        for(int choice=0;choice<5;choice++){
            var gate=new LyricRefresh.Gate();long period=(1_000_000_000L+LyricRefresh.hertz(choice)-1)/LyricRefresh.hertz(choice);
            assertEquals(0,gate.remaining(true,choice,1));gate.presented(1);
            assertEquals(period-2_777_778L,gate.remaining(true,choice,2_777_779L));
            assertEquals(0,gate.remaining(true,choice,1+period));gate.presented(1+period);
            long afterSlowFrame=2_000_000_000L;
            assertEquals(0,gate.remaining(true,choice,afterSlowFrame));gate.presented(afterSlowFrame);
            assertEquals(period-1,gate.remaining(true,choice,afterSlowFrame+1),"No catch-up burst");
        }
    }
    @Test void uncappedUiAndLiveChoiceChangesDoNotWaitOnAnOldLimit() {
        var gate=new LyricRefresh.Gate();gate.remaining(true,0,1);gate.presented(1);
        assertEquals(0,gate.remaining(true,5,2));assertEquals(0,gate.remaining(false,0,3));
        assertEquals(0,gate.remaining(true,0,4));gate.presented(4);
        assertEquals(0,gate.remaining(true,4,5));
    }
}
