package com.muoniumplayer.bedrock;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.core.ncm.music.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real transport button and local decoder admission. */
class PlaybackRetryTest {
    static Object flag(String name) throws Exception {var f=CloudMusic.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    static void flag(String name,Object value) throws Exception {var f=CloudMusic.class.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
    Path wav;AudioPlayer original;boolean oldMix,oldBreak,oldDontAdd;CloudMusic.PlayMode oldMode;
    @BeforeEach void setup() throws Exception {
        if(!MuoniumPlayerClient.getInstance().isReady())MuoniumPlayerClient.getInstance().init();
        oldMix=AutomixSettings.isEnabled();oldMode=CloudMusic.playMode;
        oldBreak=(boolean)flag("doBreak");oldDontAdd=CloudMusic.dontAdd;AutomixSettings.setEnabled(false);
        CloudMusic.exitPersonalFmSession();CloudMusic.playMode=CloudMusic.PlayMode.Sequential;
        wav=Files.createTempFile(SessionMedia.directory().toPath(),"owned-retry-",".wav");
        int bytes=44100*2*4;var b=ByteBuffer.allocate(44+bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36+bytes).put("WAVEfmt ".getBytes()).putInt(16)
            .putShort((short)1).putShort((short)1).putInt(44100).putInt(88200).putShort((short)2).putShort((short)16)
            .put("data".getBytes()).putInt(bytes);Files.write(wav,b.array());
        var song=LocalMusicLibrary.fromLocalFile(wav.toFile());assertNotNull(song);
        CloudMusic.playList=new java.util.concurrent.CopyOnWriteArrayList<>(List.of(song));
        CloudMusic.currentlyPlaying=song;CloudMusic.curIdx=0;
        var clear=CloudMusic.class.getDeclaredMethod("clearQueuedNext");clear.setAccessible(true);clear.invoke(null);
        original=new AudioPlayer(wav.toFile());CloudMusic.player=original;
        CloudMusic.playThread=new Thread();CloudMusic.playThread.start();CloudMusic.playThread.join();
        flag("awaitingPlaybackAction",false);
        ((java.util.concurrent.atomic.AtomicBoolean)flag("playing")).set(false);
    }
    @AfterEach void cleanup() throws Exception {
        var m=CloudMusic.class.getDeclaredMethod("stopExistingPlayThread");m.setAccessible(true);m.invoke(null);
        if(original!=null)original.close();if(CloudMusic.player!=null)CloudMusic.player.close();
        CloudMusic.player=null;CloudMusic.playThread=null;CloudMusic.currentlyPlaying=null;
        CloudMusic.playList=new ArrayList<>();CloudMusic.curIdx=0;flag("awaitingPlaybackAction",false);
        flag("doBreak",oldBreak);CloudMusic.dontAdd=oldDontAdd;
        AutomixSettings.setEnabled(oldMix);CloudMusic.playMode=oldMode;Files.deleteIfExists(wav);
    }
    void assertNewPlayback() throws Exception {
        long deadline=System.nanoTime()+4_000_000_000L;
        while((CloudMusic.player==original||CloudMusic.player==null||CloudMusic.player.isPausing())&&System.nanoTime()<deadline)Thread.sleep(10);
        assertNotSame(original,CloudMusic.player,"The play button must admit a new decoder for the selected track");
        assertNotNull(CloudMusic.player);assertTrue(CloudMusic.player.isUsable());assertFalse(CloudMusic.player.isPausing());
        assertEquals(0,CloudMusic.curIdx);assertEquals(1,CloudMusic.playList.size());
        assertEquals(CloudMusic.playList.getFirst(),CloudMusic.currentlyPlaying);
    }
    @ParameterizedTest @EnumSource(CloudMusic.PlayMode.class)
    void playButtonRetriesTheSameFailedTrackWithoutSelectingNext(CloudMusic.PlayMode mode) throws Exception {
        CloudMusic.playMode=mode;var queue=CloudMusic.playList;
        original.close();flag("awaitingPlaybackAction",true);
        assertEquals(CloudMusic.PlayPauseResult.RESUMED,CloudMusic.togglePlayPause());
        assertNewPlayback();
        assertSame(queue,CloudMusic.playList,"Retry keeps queue ownership and must not reshuffle it");
        assertEquals(mode,CloudMusic.playMode);
    }
    @Test void playButtonRestartsTheLastTrackAfterSequentialQueueCompletion() throws Exception {
        var f=AudioPlayer.class.getDeclaredField("finished");f.setAccessible(true);f.setBoolean(original,true);
        CloudMusic.curIdx=CloudMusic.playList.size();
        assertEquals(CloudMusic.PlayPauseResult.RESUMED,CloudMusic.togglePlayPause());
        assertNewPlayback();
    }
    @Test void ordinaryPauseResumeKeepsTheExistingDecoder() {
        original.pause();assertEquals(CloudMusic.PlayPauseResult.RESUMED,CloudMusic.togglePlayPause());
        assertSame(original,CloudMusic.player);
        assertEquals(CloudMusic.PlayPauseResult.PAUSED,CloudMusic.togglePlayPause());
    }
}
