package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.ncm.music.*;
import org.junit.jupiter.api.*;
import java.io.File;
import java.util.Arrays;
import java.nio.file.Path;
import java.util.concurrent.*;
import com.muoniumplayer.core.utils.other.multithreading.BackgroundWork;
import static com.muoniumplayer.bedrock.StreamingTransitionTest.*;
import static org.junit.jupiter.api.Assertions.*;

class StreamingAutomixTest {
    boolean enabled,advanced,performance;
    AutomixSettings.Engine engine;
    String modelDirectory;
    @org.junit.jupiter.api.io.TempDir Path models;
    @BeforeEach void settings() {
        enabled=AutomixSettings.isEnabled();advanced=AutomixSettings.isAdvancedModeEnabled();
        performance=AutomixSettings.isPerformanceModeEnabled();engine=AutomixSettings.getEngine();
        modelDirectory=AutomixSettings.getModelsDirectory();AutomixSettings.setModelsDirectory(models.toString());
        AutomixSettings.setEnabled(true);AutomixSettings.setAdvancedModeEnabled(false);
        AutomixSettings.setPerformanceModeEnabled(true);AutomixSettings.setEngine(AutomixSettings.Engine.FOLIA);
        PlaybackFiles.global().setPrefetch(true);
    }
    @AfterEach void restore() {
        ProgressiveAudio.closePending();PlaybackFiles.global().setPrefetch(false);
        AutomixSettings.setEnabled(enabled);AutomixSettings.setAdvancedModeEnabled(advanced);
        AutomixSettings.setPerformanceModeEnabled(performance);AutomixSettings.setEngine(engine);
        AutomixSettings.setModelsDirectory(modelDirectory);
        CloudMusic.player=null;CloudMusic.currentlyPlaying=null;
    }
    static void tick(Object owner,AudioPlayer active)throws Exception {
        assertTrue(Arrays.stream(owner.getClass().getDeclaredMethods()).anyMatch(m->m.getName().equals("bedrockStreamingMixTick")),
                "Original owner must reconnect completed streams to background automix analysis");
        call(owner,"bedrockStreamingMixTick",new Class[]{AudioPlayer.class},active);
    }
    @Test void currentStreamCompletesOnlyWhenBothOptionsAreEnabled()throws Exception {
        try(var remote=new StreamingSeekTest.Remote(StreamingSeekTest.audio(500))) {
            remote.release.countDown();var song=new Track(remote.url(),"current");var owner=owner(song,song);
            File token=ProgressiveAudio.prepare(remote.url());var active=new AudioPlayer(token);set(owner,"ownedPlayer",active);
            try {
                PlaybackFiles.global().setPrefetch(false);tick(owner,active);Thread.sleep(180);
                assertNull(active.getSourceFile(),"Disabled predownload must preserve bounded current-stream buffering");
                PlaybackFiles.global().setPrefetch(true);AutomixSettings.setEnabled(false);tick(owner,active);Thread.sleep(180);
                assertNull(active.getSourceFile(),"Automix off must not force the current song to download in full");
                AutomixSettings.setEnabled(true);tick(owner,active);
                long until=System.nanoTime()+5_000_000_000L;
                while(active.getSourceFile()==null&&System.nanoTime()<until)Thread.sleep(10);
                assertNotNull(active.getSourceFile(),"Opted-in mixing needs the complete current analysis source before natural EOF");
            } finally {call(owner,"cancelPlayback",new Class[]{});active.close();}
        }
    }
    @Test void ordinaryStreamingPlanUpgradesThroughTheOriginalFoliaEngine()throws Exception {
        exerciseUpgrade(false,false);
    }
    @Test void lateCompletedAnalysisNeverChangesAnArmedSeam()throws Exception {
        exerciseUpgrade(true,false);
    }
    @Test void changedQueueCannotInstallCompletedAnalysis()throws Exception {
        exerciseUpgrade(false,true);
    }
    @Test void completionBudgetAndOptOutPreserveBoundedBuffering()throws Exception {
        try(var remote=new StreamingSeekTest.Remote(StreamingSeekTest.audio(500))) {
            File file=ProgressiveAudio.prepare(remote.url());assertNotNull(file);
            try {
                assertFalse(ProgressiveAudio.completeForAnalysis(file,true,1024*1024));
                assertTrue(ProgressiveAudio.completeForAnalysis(file,true,100*1024*1024));
                assertFalse(ProgressiveAudio.completeForAnalysis(file,false,100*1024*1024));
                remote.release.countDown();Thread.sleep(300);
                assertNull(ProgressiveAudio.analysisFile(file),"Disabling completion must restore the existing 12-second lookahead");
            } finally {ProgressiveAudio.discard(file);}
        }
    }
    @Test void queuedAnalysisIsCancelledWithoutTouchingEitherDeck()throws Exception {
        try(var out=new StreamingSeekTest.Remote(StreamingSeekTest.audio(500));var in=new StreamingSeekTest.Remote(StreamingSeekTest.audio(500))) {
            var current=new Track(out.url(),"out");var next=new Track(in.url(),"in");var owner=owner(current,next);
            File token=ProgressiveAudio.prepare(out.url());var active=new AudioPlayer(token);set(owner,"ownedPlayer",active);
            var release=new CountDownLatch(1);var started=new CountDownLatch(BackgroundWork.maxConcurrency());
            var blockers=new java.util.ArrayList<CompletableFuture<Void>>();
            try {
                call(owner,"armTrack",new Class[]{com.muoniumplayer.core.ncm.music.dto.Music.class,int.class,long.class,long.class},
                        next,1,CloudMusic.generation.get(),revision());
                Object track=get(owner,"armed");assertNotNull(track);Object initial=get(track,"plan");
                for(int i=0;i<BackgroundWork.maxConcurrency();i++)blockers.add(BackgroundWork.submit(()->{
                    started.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}
                }));
                assertTrue(started.await(3,TimeUnit.SECONDS));out.release.countDown();in.release.countDown();
                Object preparation=get(owner,"bedrockMix");long until=System.nanoTime()+5_000_000_000L;
                while(get(preparation,"job")==null&&System.nanoTime()<until){tick(owner,active);Thread.sleep(10);}
                Object job=get(preparation,"job");assertNotNull(job,"Completed sources should queue exactly one background job");
                tick(owner,active);assertSame(job,get(preparation,"job"));
                PlaybackFiles.global().setPrefetch(false);tick(owner,active);
                assertTrue((Boolean)get(job,"cancelled"));release.countDown();
                until=System.nanoTime()+4_000_000_000L;
                while(!((Boolean)get(job,"done"))&&System.nanoTime()<until)Thread.sleep(10);
                assertTrue((Boolean)get(job,"done"));assertNull(get(job,"result"));
                assertSame(initial,get(track,"plan"));assertTrue(active.isUsable());assertTrue(((AudioPlayer)get(track,"deck")).isUsable());
            } finally {release.countDown();for(var f:blockers)f.get(5,TimeUnit.SECONDS);call(owner,"cancelPlayback",new Class[]{});active.close();}
        }
    }
    private void exerciseUpgrade(boolean late,boolean stale)throws Exception {
        try(var out=new StreamingSeekTest.Remote(StreamingSeekTest.audio(500));var in=new StreamingSeekTest.Remote(StreamingSeekTest.audio(500))) {
            var current=new Track(out.url(),"out");var next=new Track(in.url(),"in");var owner=owner(current,next);
            File token=ProgressiveAudio.prepare(out.url());var active=new AudioPlayer(token);set(owner,"ownedPlayer",active);
            try {
                call(owner,"armTrack",new Class[]{com.muoniumplayer.core.ncm.music.dto.Music.class,int.class,long.class,long.class},
                        next,1,CloudMusic.generation.get(),revision());
                Object track=get(owner,"armed");assertNotNull(track);Object initial=get(track,"plan");
                assertNull(get(initial,"style"),"Before whole sources exist, keep the ordinary fallback immediately usable");
                if(late)active.setPlaybackTime(((Long)get(initial,"fireMillis"))-500);
                if(stale)revision(revision()+1);
                out.release.countDown();in.release.countDown();
                long until=System.nanoTime()+20_000_000_000L;
                do {tick(owner,active);Thread.sleep(20);}
                while(!((Boolean)get(track,"bedrockMixDone"))&&System.nanoTime()<until&&!late&&!stale);
                if(late||stale) {
                    Thread.sleep(100);tick(owner,active);assertSame(initial,get(track,"plan"));
                    assertFalse((Boolean)get(track,"bedrockMixApplied"));
                } else {
                    assertTrue((Boolean)get(track,"bedrockMixApplied"),"Complete files must upgrade the original armed track asynchronously");
                    assertNotNull(get(get(track,"plan"),"style"),"Folia plan must contain the original transition style, not another classic plan");
                    assertNotNull(active.getSourceFile());assertNotNull(((AudioPlayer)get(track,"deck")).getSourceFile());
                }
            } finally {call(owner,"cancelPlayback",new Class[]{});active.close();}
        }
    }
}
