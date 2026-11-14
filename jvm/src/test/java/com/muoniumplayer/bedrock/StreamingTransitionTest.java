package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.ncm.music.*;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.utils.Tuple;
import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.Files;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class StreamingTransitionTest {
    @BeforeEach void optInToExistingPrefetchCases(){PlaybackFiles.global().setPrefetch(true);}
    static class Track extends Music {
        final String url;final String key;
        Track(String url,String key){super("Owned fixture","Owned fixture","",key.hashCode(),List.of(),List.of(),null,500*4096*1000L/44100,0,0,List.of());this.url=url;this.key=key;}
        @Override public Tuple<String,String> getPlayUrl(){return new Tuple<>(url,"flac");}
        @Override public String getStableKey(){return key;}
        @Override public String getName(){return "Owned fixture";}
        @Override public boolean isNetease(){return true;}
        @Override public long getDuration(){return 500*4096*1000L/44100;}
    }
    static Object call(Object o,String name,Class<?>[] types,Object... args)throws Exception {
        Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);
    }
    static Object get(Object o,String name)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String name,Object value)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static Object owner(Music current,Music next)throws Exception{
        CloudMusic.playList=new CopyOnWriteArrayList<>(List.of(current,next));CloudMusic.curIdx=0;CloudMusic.currentlyPlaying=current;
        var c=Class.forName("com.muoniumplayer.core.ncm.music.CloudMusic$PlayThread").getDeclaredConstructor(List.class,int.class);c.setAccessible(true);
        Object owner=c.newInstance(CloudMusic.playList,0);CloudMusic.playThread=(Thread)owner;return owner;
    }
    static long revision()throws Exception{var f=CloudMusic.class.getDeclaredField("queueRevision");f.setAccessible(true);return f.getLong(null);}
    static void revision(long value)throws Exception{var f=CloudMusic.class.getDeclaredField("queueRevision");f.setAccessible(true);f.setLong(null,value);}
    @AfterEach void cleanup(){ProgressiveAudio.closePending();CloudMusic.playThread=null;CloudMusic.playList=new ArrayList<>();CloudMusic.currentlyPlaying=null;CloudMusic.player=null;PlaybackFiles.global().setPrefetch(false);PlaybackFiles.global().setRetain(false);PlaybackFiles.global().clearRetained();}
    @Test void prefetchOffDoesNotEvenResolveTheNextAudioSource()throws Exception {
        PlaybackFiles.global().setPrefetch(false);var resolutions=new java.util.concurrent.atomic.AtomicInteger();
        Music current=new Track("http://127.0.0.1:1/unused","current"),next=new Track("http://127.0.0.1:1/unused","next") {
            @Override public Tuple<String,String> getPlayUrl(){resolutions.incrementAndGet();return null;}
        };
        var owner=owner(current,next);
        call(owner,"prefetchTrackAudio",new Class[]{Music.class,long.class,long.class},next,CloudMusic.generation.get(),revision());
        assertEquals(0,resolutions.get(),"Opt-out must gate source resolution/download, not just hide the next item");
        assertNull(get(owner,"bedrockUpcoming"));
        assertFalse(CloudMusic.prefetchRoomTrackAudio("http://127.0.0.1:1/unused","flac",next));
    }
    @Test @Timeout(15) void midSongStreamFailureStopsOnCurrentTrackInsteadOfAdvancingPlaylist()throws Exception {
        PlaybackFiles.global().setPrefetch(false);
        var nextRequests=new java.util.concurrent.atomic.AtomicInteger();
        try(var remote=new StreamingSeekTest.Remote(StreamingSeekTest.audio(500))){
            Music current=new Track(remote.url(),"interrupted-current"),next=new Track(remote.url(),"must-not-start"){
                @Override public Tuple<String,String> getPlayUrl(){nextRequests.incrementAndGet();return null;}
            };
            Object owner=owner(current,next);File token=ProgressiveAudio.prepare(remote.url());assertNotNull(token);
            var active=new AudioPlayer(token){@Override public float getCurrentTimeMillis(){return 37000;}};
            CloudMusic.player=active;remote.mode="changed";
            try{
                long until=System.nanoTime()+4_000_000_000L;
                while(ProgressiveAudio.state(token,37000)!=3&&System.nanoTime()<until)Thread.sleep(10);
                assertEquals(3,ProgressiveAudio.state(token,37000),"Fixture must interrupt a real progressive source halfway through");
                set(owner,"ownedPlayer",active);set(owner,"session",new PlaybackSession(CloudMusic.generation.get(),current.getStableKey(),current.getId()));
                set(owner,"automixHandover",true); // enter supervision of the already admitted deck
                var flag=CloudMusic.class.getDeclaredField("playing");flag.setAccessible(true);
                var awaiting=CloudMusic.class.getDeclaredField("awaitingPlaybackAction");awaiting.setAccessible(true);
                boolean oldAwaiting=awaiting.getBoolean(null);
                var playing=(java.util.concurrent.atomic.AtomicBoolean)flag.get(null);boolean old=playing.get();playing.set(true);
                try{
                    ((Thread)owner).run();
                    assertEquals(0,CloudMusic.curIdx,"Failed audio is not natural completion and must not consume the next song");
                    assertSame(current,CloudMusic.currentlyPlaying);assertEquals(0,nextRequests.get());
                    assertTrue(awaiting.getBoolean(null),"Keep retry and manual song selection available");
                    assertFalse(playing.get());
                }finally{playing.set(old);awaiting.setBoolean(null,oldAwaiting);}
            }finally{call(owner,"cancelPlayback",new Class[]{});active.close();}
        }
    }
    @Test void completedProgressiveMusicIsReusedByTheActualPlaybackAdmission()throws Exception {
        PlaybackFiles.global().setRetain(true);PlaybackFiles.global().clearRetained();
        try(var remote=new StreamingSeekTest.Remote(StreamingSeekTest.audio(30))) {
            remote.release.countDown();Music song=new Track(remote.url(),"retention-"+UUID.randomUUID());var owner=owner(song,song);
            File first=(File)call(owner,"getMusicFile",new Class[]{Tuple.class,Music.class},song.getPlayUrl(),song);assertNotNull(first);
            long until=System.nanoTime()+4_000_000_000L;
            while(ProgressiveAudio.analysisFile(first)==null&&System.nanoTime()<until)Thread.sleep(10);
            assertNotNull(ProgressiveAudio.analysisFile(first));ProgressiveAudio.discard(first);
            long transferred=remote.transferred.get();
            File replay=(File)call(owner,"getMusicFile",new Class[]{Tuple.class,Music.class},song.getPlayUrl(),song);
            assertNotNull(replay);assertFalse(ProgressiveAudio.isProgressive(replay),"Complete retained audio must skip HTTP progressive preparation");
            assertEquals(transferred,remote.transferred.get());
            var deck=new AudioPlayer(replay);try{deck.prepareStart(0);assertTrue(deck.isPrepared());assertTrue(deck.getTotalTimeMillis()>2000);}finally{deck.close();Files.deleteIfExists(replay.toPath());}
        }
    }
    @Test void nextPrefixIsClaimedOnceAndQueueChangeDiscardsIt()throws Exception{
        assertTrue(Arrays.stream(Class.forName("com.muoniumplayer.core.ncm.music.CloudMusic$PlayThread").getDeclaredMethods()).anyMatch(m->m.getName().equals("bedrockTakeUpcoming")),"Missing upcoming-source ownership/admission in the original playback thread");
        try(var remote=new StreamingSeekTest.Remote(StreamingSeekTest.audio(500))){
            Music current=new Track(remote.url(),"current"),next=new Track(remote.url(),"next");var owner=owner(current,next);
            long generation=CloudMusic.generation.get(),revision=revision();
            call(owner,"prefetchTrackAudio",new Class[]{Music.class,long.class,long.class},next,generation,revision);
            File token=(File)call(owner,"bedrockTakeUpcoming",new Class[]{Music.class},next);
            assertNotNull(token);assertTrue(ProgressiveAudio.isProgressive(token));
            assertNull(call(owner,"bedrockTakeUpcoming",new Class[]{Music.class},next));ProgressiveAudio.discard(token);
            call(owner,"prefetchTrackAudio",new Class[]{Music.class,long.class,long.class},next,generation,revision);
            revision(revision()+1);
            assertNull(call(owner,"bedrockTakeUpcoming",new Class[]{Music.class},next),"Changed queue must not consume a stale prediction");
            assertEquals(1,remote.release.getCount(),"Prefetch must not wait for the whole song");
            call(owner,"cancelPlayback",new Class[]{});
        }
    }
    @Test void enabledPrefetchDownloadsBeyondTheNormalTwelveSecondWindowAndOptOutReleasesIt()throws Exception {
        try(var remote=new StreamingSeekTest.Remote(StreamingSeekTest.audio(500))) {
            remote.release.countDown();Music current=new Track(remote.url(),"current"),next=new Track(remote.url(),"next");var owner=owner(current,next);
            call(owner,"prefetchTrackAudio",new Class[]{Music.class,long.class,long.class},next,CloudMusic.generation.get(),revision());
            File file=(File)get(owner,"bedrockUpcoming");assertNotNull(file);
            long until=System.nanoTime()+4_000_000_000L;
            while(ProgressiveAudio.analysisFile(file)==null&&System.nanoTime()<until)Thread.sleep(10);
            assertNotNull(ProgressiveAudio.analysisFile(file),"Opted-in next-track download must reach the complete song without a moving playhead");
            PlaybackFiles.global().setPrefetch(false);call(owner,"bedrockDropStaleUpcoming",new Class[]{});
            assertNull(get(owner,"bedrockUpcoming"));assertFalse(file.exists());
        }
    }
    @Test void originalArmPublishesStreamingDeckAndOrdinaryMixBeforeEof()throws Exception{
        try(var outgoing=new StreamingSeekTest.Remote(StreamingSeekTest.audio(500));var incoming=new StreamingSeekTest.Remote(StreamingSeekTest.audio(500))){
            Music current=new Track(outgoing.url(),"current"),next=new Track(incoming.url(),"next");var owner=owner(current,next);
            File token=ProgressiveAudio.prepare(outgoing.url());assertNotNull(token);var active=new AudioPlayer(token);
            boolean enabled=AutomixSettings.isEnabled();AutomixSettings.setEnabled(true);
            set(owner,"ownedPlayer",active);
            try{
                call(owner,"armTrack",new Class[]{Music.class,int.class,long.class,long.class},next,1,CloudMusic.generation.get(),revision());
                Object track=get(owner,"armed");assertNotNull(track,"Partial streams should use original ordinary crossfade, without waiting for EOF/models");
                assertNotNull(get(track,"plan"));var deck=(AudioPlayer)get(track,"deck");assertTrue(deck.isPrepared());assertNull(deck.getSourceFile());
                assertEquals(1,incoming.release.getCount());assertEquals(1,outgoing.release.getCount());
                deck.setPlaybackTime(30000);
                boolean result=(boolean)call(owner,"performAutomixHandover",new Class[]{AudioPlayer.class,track.getClass()},active,track);
                assertFalse(result,"Handover must wait when the prepared cue is not buffered");
                assertSame(track,get(owner,"armed"),"Waiting for a stream must retain ownership for retry/cancel");
            }finally{call(owner,"cancelArmedTrack",new Class[]{});active.close();AutomixSettings.setEnabled(enabled);}
        }
    }
    @Test void manualSelectionPreparesPrefixBeforeDelayedTailAndCancellationClosesDeck()throws Exception{
        try(var outgoing=new StreamingSeekTest.Remote(StreamingSeekTest.audio(500));var incoming=new StreamingSeekTest.Remote(StreamingSeekTest.audio(500))){
            Music current=new Track(outgoing.url(),"current"),next=new Track(incoming.url(),"next");var owner=owner(current,next);
            File token=ProgressiveAudio.prepare(outgoing.url());assertNotNull(token);var active=new AudioPlayer(token);
            long generation=CloudMusic.generation.get();var session=new PlaybackSession(generation,current.getStableKey(),current.getId());
            boolean enabled=AutomixSettings.isEnabled();AutomixSettings.setEnabled(true);
            set(owner,"ownedPlayer",active);set(owner,"session",session);CloudMusic.player=active;
            Class<?> type=Class.forName("com.muoniumplayer.core.ncm.music.CloudMusic$PlayThread$ManualSelection");
            var ctor=type.getDeclaredConstructor(List.class,int.class,boolean.class,PlaybackSession.class);ctor.setAccessible(true);
            Object selection=ctor.newInstance(CloudMusic.playList,1,false,session);set(owner,"manualSelection",selection);
            try{
                call(owner,"prepareManualSelection",new Class[]{type},selection);
                Object ready=get(selection,"ready");assertNotNull(ready,"Manual selection must use progressive preparation too");
                File file=(File)get(ready,"file");assertTrue(ProgressiveAudio.isProgressive(file));
                assertEquals(1,incoming.release.getCount());assertEquals(0,CloudMusic.curIdx,"Preparation must not prematurely replace the current track");
                call(owner,"cancelManualSelection",new Class[]{});
                var released=new java.util.concurrent.CountDownLatch(1);
                var later=AudioPlayer.class.getDeclaredMethod("releaseLater",Runnable.class);later.setAccessible(true);
                later.invoke(null,(Runnable)released::countDown);
                assertTrue(released.await(2,java.util.concurrent.TimeUnit.SECONDS),"Asynchronous decoder release must complete");
                assertFalse(file.exists());
            }finally{call(owner,"cancelPlayback",new Class[]{});active.close();AutomixSettings.setEnabled(enabled);}
        }
    }
    @Test void ordinaryModeActuallySchedulesOneUpcomingPrefix()throws Exception{
        try(var outgoing=new StreamingSeekTest.Remote(StreamingSeekTest.audio(500));var incoming=new StreamingSeekTest.Remote(StreamingSeekTest.audio(500))){
            Music current=new Track(outgoing.url(),"current"),next=new Track(incoming.url(),"next");var owner=owner(current,next);
            File token=ProgressiveAudio.prepare(outgoing.url());var active=new AudioPlayer(token);
            long generation=CloudMusic.generation.get();set(owner,"session",new PlaybackSession(generation,current.getStableKey(),current.getId()));
            boolean enabled=AutomixSettings.isEnabled();AutomixSettings.setEnabled(false);active.setPlaybackTime(3500);
            try{
                call(owner,"maybePrefetchNextTrackAudio",new Class[]{AudioPlayer.class},active);
                long until=System.nanoTime()+3_000_000_000L;
                while(get(owner,"bedrockUpcoming")==null&&System.nanoTime()<until)Thread.sleep(10);
                File file=(File)get(owner,"bedrockUpcoming");assertNotNull(file,"Automix disabled must still prepare the next NetEase prefix");
                int before=incoming.starts.size();call(owner,"maybePrefetchNextTrackAudio",new Class[]{AudioPlayer.class},active);
                Thread.sleep(80);assertEquals(before,incoming.starts.size());
                // A normal track start advances exactly one playback generation.
                CloudMusic.generation.incrementAndGet();CloudMusic.currentlyPlaying=next;
                assertEquals(file,call(owner,"bedrockTakeUpcoming",new Class[]{Music.class},next));ProgressiveAudio.discard(file);
            }finally{call(owner,"cancelPlayback",new Class[]{});active.close();AutomixSettings.setEnabled(enabled);}
        }
    }
}
