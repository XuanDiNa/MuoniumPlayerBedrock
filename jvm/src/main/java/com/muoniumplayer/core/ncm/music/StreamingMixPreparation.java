package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.utils.other.multithreading.BackgroundWork;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** One background whole-source upgrade; it never seeks or mutates either deck. */
final class StreamingMixPreparation {
    private static final AtomicBoolean BUSY=new AtomicBoolean();
    static final class Result {
        final AutomixPlan plan;
        final FoliaVocalStem vocal;
        Result(AutomixPlan plan,FoliaVocalStem vocal){this.plan=plan;this.vocal=vocal;}
    }
    private static final class Job {
        final BooleanSupplier valid;
        final String settings=settingsKey();
        volatile boolean cancelled,done;
        volatile Result result;
        Thread worker;
        Job(BooleanSupplier valid){this.valid=valid;}
        boolean current(){return !cancelled&&!Thread.currentThread().isInterrupted()&&valid.getAsBoolean()&&settings.equals(settingsKey());}
        synchronized void cancel(){cancelled=true;if(worker!=null)worker.interrupt();}
    }
    private volatile Job job;
    static String settingsKey() {
        return AutomixSettings.getEngine()+","+AutomixSettings.getOverlapMillis()+","+AutomixSettings.isAdvancedModeEnabled()
            +","+AutomixSettings.isPerformanceModeEnabled()+","+AutomixSettings.isBeatAlignEnabled()+","+AutomixSettings.isBassSwapEnabled()
            +","+AutomixSettings.isTempoLockEnabled()+","+AutomixSettings.isVocalDuckEnabled()+","+AutomixSettings.isPitchMatchEnabled()
            +","+AutomixSettings.isSkipIntroEnabled()+","+AutomixSettings.getModelsDirectory();
    }
    synchronized boolean start(AudioPlayer outgoing,AudioPlayer incoming,long lastVocal,BooleanSupplier valid) {
        if(job!=null||outgoing.getSourceFile()==null||incoming.getSourceFile()==null||!BUSY.compareAndSet(false,true))return false;
        Job next=new Job(valid);job=next;
        BackgroundWork.submit(()->{
            Thread.interrupted();
            synchronized(next){if(!next.cancelled)next.worker=Thread.currentThread();}
            try {
                if(!next.current())return;
                if(outgoing.getSourceFile().length()>AutomixSettings.maxDeckBytes()
                        ||incoming.getSourceFile().length()>AutomixSettings.maxDeckBytes())return;
                CloudMusic.warmAdvancedEnvelopeNow(outgoing.getSourceFile(),(long)outgoing.getTotalTimeMillis());
                if(!next.current())return;
                CloudMusic.warmAdvancedEnvelopeNow(incoming.getSourceFile(),(long)incoming.getTotalTimeMillis());
                if(!next.current())return;
                AutomixPlan plan=StreamingMixPlanner.plan(outgoing,incoming,lastVocal);
                if(plan==null||!next.current())return;
                FoliaVocalStem vocal=null;
                if(AutomixSettings.isAdvancedModeEnabled()) {
                    vocal=FoliaVocalStem.load(incoming.getSourceFile(),incoming.sampleRate());
                    if(vocal!=null&&!vocal.covers(plan.incomingCueMillis,plan.overlapMillis,incoming.sampleRate()))vocal=null;
                }
                if(next.current())next.result=new Result(plan,vocal);
            } catch(Throwable failure) {
                System.out.println("STREAMING_MIX analysis=unavailable reason="+failure.getClass().getSimpleName());
            } finally {
                synchronized(next){next.worker=null;}
                next.done=true;BUSY.set(false);Thread.interrupted();
            }
        }).whenComplete((ignored,failure)->{if(failure!=null){next.done=true;BUSY.set(false);}});
        return true;
    }
    boolean finished(){Job current=job;return current!=null&&current.done;}
    Result result(){Job current=job;return current!=null&&current.done&&current.current()?current.result:null;}
    synchronized void cancel(){Job old=job;job=null;if(old!=null)old.cancel();}
}
