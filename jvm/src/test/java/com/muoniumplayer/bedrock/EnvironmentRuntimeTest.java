package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import java.nio.*;
import java.util.concurrent.atomic.AtomicReference;
import com.muoniumplayer.core.ncm.music.ambient.*;
import com.muoniumplayer.core.ncm.music.acoustics.*;
import static org.junit.jupiter.api.Assertions.*;

class EnvironmentRuntimeTest {
    @Test void acousticContextStaysStableUntilWorldOrSourceChanges() {
        var input=new AtomicReference<AcousticsRuntime.ListenerSampler>();
        var runtime=new EnvironmentRuntime(ignored->{},input::set);
        var raw=EnvironmentFrameTest.packet();
        runtime.tick(EnvironmentFrame.decode(raw,1001,7),true,false,true);
        Object first=input.get().contextKey();assertNotNull(first);
        runtime.tick(EnvironmentFrame.decode(raw,1001,7),true,false,true);
        assertSame(first,input.get().contextKey(),"Fresh frame objects must not invalidate the same world");
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putLong(24,8);
        runtime.tick(EnvironmentFrame.decode(raw,1001,8),true,false,true);
        Object second=input.get().contextKey();assertNotSame(first,second);
        var source=SpeakerState.place(.5,.5,.5,"0");
        runtime.tick(EnvironmentFrame.decode(raw,1001,8),true,false,true,source);
        Object fixed=input.get().contextKey();assertNotSame(second,fixed);
        runtime.tick(EnvironmentFrame.decode(raw,1001,8),true,false,true,source);
        assertSame(fixed,input.get().contextKey());
        var sampler=input.get();runtime.tick(null,false,false,true);
        assertNull(input.get());assertNull(sampler.contextKey());assertNull(sampler.capture());
    }
    @Test void nearbyCountsReachOriginalCombatRuleAndClearWithoutStaleCombat() throws Exception {
        boolean before=AmbientSettings.isEnabled();
        var label=AmbientMusicController.class.getDeclaredField("lastSeenLabel");label.setAccessible(true);
        try {
            AmbientSettings.setEnabled(true);AmbientMusicController.reset();
            var raw=EnvironmentFrameTest.packet();var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(132,2).putInt(136,3).putInt(140,1);
            var frame=EnvironmentFrame.decode(raw,1001,7);assertNotNull(frame);
            var sample=frame.ambient();assertEquals(2,sample.enemyCount);assertEquals(3,sample.neutralCount);assertEquals(1,sample.villagerCount);
            var runtime=new EnvironmentRuntime();runtime.tick(frame,true,true,false);
            assertEquals(Mood.COMBAT.display,label.get(null));
            b.putInt(132,0);frame=EnvironmentFrame.decode(raw,1001,7);
            runtime.tick(frame,true,true,false);assertEquals(Mood.CALM.display,label.get(null));
            b.putInt(132,2).putInt(144,b.getInt(144)&~16);
            frame=EnvironmentFrame.decode(raw,1001,7);assertEquals(0,frame.ambient().enemyCount);
            runtime.tick(frame,true,true,false);assertEquals(Mood.CALM.display,label.get(null));
            runtime.tick(EnvironmentFrame.decode(raw,3001,7),true,true,false);assertNull(label.get(null));
            runtime.tick(EnvironmentFrame.decode(raw,1001,8),true,true,false);assertNull(label.get(null));
            runtime.tick(frame,false,true,false);assertNull(label.get(null));
        }finally {AmbientSettings.setEnabled(before);AmbientMusicController.reset();}
    }
    @Test void speakerCaptureKeepsMatchingFrameAndMutesDuringMissingGeometry() throws Exception {
        var input=new AtomicReference<AcousticsRuntime.ListenerSampler>();
        var runtime=new EnvironmentRuntime(ignored->{},input::set);
        var f=EnvironmentFrame.decode(EnvironmentFrameTest.packet(),1001,7);
        var source=SpeakerState.place(-7.5,.5,.5,"0");
        runtime.tick(f,true,false,true,source);
        var pending=input.get();assertNotNull(pending);
        assertTrue(pending.capture().hasDirectSource());assertEquals(-7.5,pending.capture().speakerX());
        runtime.tick(null,true,false,true,source);
        assertNotNull(input.get(),"A missing scan must not switch the fixed speaker back to headset audio");
        assertTrue(SceneResponse.respond(pending.capture(),1).directGain<.0001);
        runtime.tick(f,true,false,true,null);assertFalse(pending.capture().hasDirectSource());
        runtime.tick(null,false,false,true,null);assertNull(input.get());assertNull(pending.capture());
    }
    @Test void productionAdapterReachesOriginalMoodStateAndAsyncDspTarget() throws Exception {
        boolean ambientBefore=AmbientSettings.isEnabled(),soundBefore=AcousticsSettings.isEnabled();
        try {
            AmbientSettings.setEnabled(true);AcousticsSettings.setEnabled(true);AmbientMusicController.reset();
            var raw=EnvironmentFrameTest.packet();ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(148,3);
            var runtime=new EnvironmentRuntime();runtime.tick(EnvironmentFrame.decode(raw,1001,7),true,true,true);
            var label=AmbientMusicController.class.getDeclaredField("lastSeenLabel");label.setAccessible(true);
            assertEquals(Mood.UNDERWATER.display,label.get(null));
            var plan=SceneAcoustics.class.getDeclaredField("targetPlan");plan.setAccessible(true);
            long until=System.nanoTime()+2_000_000_000L;
            while(((RoomPlan)plan.get(null)).bandHz>=2000&&System.nanoTime()<until)Thread.sleep(10);
            assertTrue(((RoomPlan)plan.get(null)).bandHz<2000);
            runtime.tick(null,true,true,true);assertNull(label.get(null));
        }finally{AmbientSettings.setEnabled(ambientBefore);AcousticsSettings.setEnabled(soundBefore);AcousticsRuntime.onClientTick(null);AmbientMusicController.reset();}
    }
    @Test void hostTicksReachBothControllersAndPendingSamplerAlwaysUsesLatestFrame() throws Exception {
        Class<?> type;
        try{type=Class.forName("com.muoniumplayer.bedrock.EnvironmentRuntime");}
        catch(ClassNotFoundException e){fail("Environment controllers are not wired to the Bedrock host tick");return;}
        var ambient=new AtomicReference<EnvSample>();
        var acoustics=new AtomicReference<AcousticsRuntime.ListenerSampler>();
        var constructor=type.getDeclaredConstructor(java.util.function.Consumer.class,java.util.function.Consumer.class);
        Object runtime=constructor.newInstance((java.util.function.Consumer<EnvSample>)ambient::set,
            (java.util.function.Consumer<AcousticsRuntime.ListenerSampler>)acoustics::set);
        var tick=type.getDeclaredMethod("tick",EnvironmentFrame.class,boolean.class,boolean.class,boolean.class);
        var first=EnvironmentFrame.decode(EnvironmentFrameTest.packet(),1001,7);
        tick.invoke(runtime,first,true,true,true);
        assertNotNull(ambient.get());assertNotNull(acoustics.get());
        var pending=acoustics.get();assertFalse(pending.capture().submerged());
        var bytes=EnvironmentFrameTest.packet();ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(148,3);
        tick.invoke(runtime,EnvironmentFrame.decode(bytes,1001,7),true,true,true);
        assertTrue(pending.capture().submerged(),"In-flight latest-wins sampler must not retain the old environment");
        tick.invoke(runtime,null,true,true,true);
        assertNull(ambient.get());assertNull(acoustics.get());assertNull(pending.capture());
        tick.invoke(runtime,first,false,true,true);assertNull(ambient.get());assertNull(acoustics.get());
        tick.invoke(runtime,first,true,false,false);assertNull(ambient.get());assertNull(acoustics.get());
    }
    @Test void actualOriginalDspAttenuatesHighFrequencyUnderwater() {
        var bytes=EnvironmentFrameTest.packet();ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(148,3);
        var frame=EnvironmentFrame.decode(bytes,1001,7);
        SceneAcoustics.applyTarget(SceneResponse.respond(frame.listener(),1));SceneAcoustics.flush();
        double input=0,output=0;
        for(int b=0;b<150;b++) {
            double[] signal=new double[1024];
            for(int n=0;n<signal.length;n++){signal[n]=Math.sin((b*1024+n)*Math.PI*.75)*.2;if(b>100)input+=signal[n]*signal[n];}
            SceneAcoustics.render(signal,1,48000);
            if(b>100)for(double n:signal){assertTrue(Double.isFinite(n));output+=n*n;}
        }
        assertTrue(output<input*.3,"The original output DSP must change the real signal, not just log a plan");
        SceneAcoustics.clearTarget();SceneAcoustics.flush();
    }
}
