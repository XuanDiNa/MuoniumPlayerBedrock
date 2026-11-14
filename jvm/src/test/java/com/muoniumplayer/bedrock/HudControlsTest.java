package com.muoniumplayer.bedrock;
import com.muoniumplayer.core.settings.HudConfig;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HudControlsTest {
    private void cinematicDraws(String json,boolean inWorld,boolean hidden,java.util.List<String> expected)throws Exception{
        var draws=new ArrayList<String>();java.lang.reflect.Method dispatch;
        try{dispatch=BedrockOverlay.class.getDeclaredMethod("renderPassiveOverlays",HostSettings.class,boolean.class,boolean.class,Runnable.class,Runnable.class);}
        catch(NoSuchMethodException e){fail("Passive draw dispatch must distinguish world lyrics from cinematic HUD hiding");return;}
        dispatch.setAccessible(true);dispatch.invoke(null,HostSettings.parse(json),inWorld,hidden,(Runnable)()->draws.add("lyrics"),(Runnable)()->draws.add("hud"));
        assertEquals(expected,draws);
    }
    @Test void cinematicHidesHudButKeepsClientWorldLyricsInTheActualDrawDispatch()throws Exception{
        cinematicDraws("{\"lyricsMode\":2}",true,true,java.util.List.of("lyrics"));
        cinematicDraws("{\"lyricsMode\":2}",true,false,java.util.List.of("lyrics","hud"));
    }
    @Test void cinematicStillHonorsDisabledLyricsAndDoesNotRestoreFlatHudElements()throws Exception{
        cinematicDraws("{\"lyricsMode\":2,\"lyricsEnabled\":false}",true,true,java.util.List.of());
        cinematicDraws("{\"lyricsMode\":2,\"enabled\":false}",true,true,java.util.List.of());
        cinematicDraws("{\"lyricsMode\":2}",false,true,java.util.List.of());
        cinematicDraws("{\"lyricsMode\":1}",true,true,java.util.List.of());
        cinematicDraws("{\"lyricsMode\":0}",true,true,java.util.List.of());
    }
    @Test void liveHudEditsGateActualDrawDispatchWithoutDisablingLyrics() {
        var draws=new ArrayList<String>();
        Runnable lyric=()->draws.add("lyrics"),hud=()->draws.add("hud");
        BedrockOverlay.renderPassiveOverlays(HostSettings.parse("{}"),true,lyric,hud);
        assertEquals(java.util.List.of("lyrics","hud"),draws);draws.clear();
        BedrockOverlay.renderPassiveOverlays(HostSettings.parse("{\"hudEnabled\":false}"),true,lyric,hud);
        assertEquals(java.util.List.of("lyrics"),draws);draws.clear();
        BedrockOverlay.renderPassiveOverlays(HostSettings.parse("{\"lyricsEnabled\":false}"),true,lyric,hud);
        assertEquals(java.util.List.of("hud"),draws);draws.clear();
        BedrockOverlay.renderPassiveOverlays(HostSettings.parse("{\"enabled\":false}"),true,lyric,hud);
        assertTrue(draws.isEmpty());
        BedrockOverlay.renderPassiveOverlays(HostSettings.parse("{\"lyricsEnabled\":false}"),false,lyric,hud);
        assertTrue(draws.isEmpty());
    }
    @Test void islandAppliesExplicitEditsButPreservesOriginalUiChangesOnOtherEdits() {
        boolean old=HudConfig.dynamicIslandEnabled;
        try {
            var on=HostSettings.parse("{}");var off=HostSettings.parse("{\"dynamicIslandEnabled\":false}");
            BedrockOverlay.applyHudSettings(on,null);assertTrue(HudConfig.dynamicIslandEnabled);
            BedrockOverlay.applyHudSettings(off,on);assertFalse(HudConfig.dynamicIslandEnabled);
            HudConfig.dynamicIslandEnabled=true; // original in-player preference
            BedrockOverlay.applyHudSettings(HostSettings.parse("{\"dynamicIslandEnabled\":false,\"volume\":50}"),off);
            assertTrue(HudConfig.dynamicIslandEnabled);
            BedrockOverlay.applyHudSettings(off,null);assertFalse(HudConfig.dynamicIslandEnabled,"Restart restores saved host setting");
        } finally {HudConfig.dynamicIslandEnabled=old;}
    }
}
