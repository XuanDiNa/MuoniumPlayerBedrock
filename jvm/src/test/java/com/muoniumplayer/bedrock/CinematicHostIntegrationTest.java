package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.cine.*;
import com.muoniumplayer.core.ncm.music.*;
import com.sun.jna.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import java.nio.*;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Complete owned camera mailbox -> runtime -> original director -> command. */
class CinematicHostIntegrationTest {
    @Test void actualHostStartsAfterIdleAndRevokesOnInputFocusAndWorldChange()throws Exception {
        var api=CinematicReaderTest.Api.I;
        String prefix="Local\\MuoniumBedrock-"+UUID.randomUUID().toString().replace("-","");
        var mapping=api.CreateFileMappingW(new HANDLE(Pointer.createConstant(-1L)),null,4,0,200,new WString(prefix+"-cinematic"));
        var mutex=api.CreateMutexW(null,false,new WString(prefix+"-cinematic-lock"));
        var event=api.CreateEventW(null,true,false,new WString(prefix+"-cinematic-active"));
        var view=api.MapViewOfFile(mapping,6,0,0,200);assertNotNull(view);view.clear(200);
        var hook=CineSettings.class.getDeclaredField("testIoHooked");hook.setAccessible(true);
        boolean oldHook=hook.getBoolean(null);hook.setBoolean(null,true);var settings=CineSettings.toJson();
        Path wav=Files.createTempFile("owned-cine-",".wav");AudioPlayer audio=null;
        var oldPlayer=CloudMusic.player;var oldMusic=CloudMusic.currentlyPlaying;
        try(var reader=new CinematicReader(prefix)) {
            int bytes=44100*2*30;var data=ByteBuffer.allocate(44+bytes).order(ByteOrder.LITTLE_ENDIAN);
            data.put("RIFF".getBytes()).putInt(36+bytes).put("WAVEfmt ".getBytes()).putInt(16)
                .putShort((short)1).putShort((short)1).putInt(44100).putInt(88200).putShort((short)2).putShort((short)16)
                .put("data".getBytes()).putInt(bytes);Files.write(wav,data.array());
            audio=new AudioPlayer(wav.toFile());CloudMusic.player=audio;
            CloudMusic.currentlyPlaying=LocalMusicLibrary.fromLocalFile(wav.toFile());audio.setVolume(0);audio.play();
            assertTrue(CloudMusic.getSnapshot().playing);
            CineSettings.applyJson(CineSettings.defaultsJson());CineSettings.setEnabled(true);CineSettings.setIdleSeconds(5);
            CineDirector.onClientTick(null);
            var client=BedrockClient.getInstance();client.bindWindow(0);client.gui.setScreen(null);
            var runtime=new CinematicRuntime();long clock=1_000_000_000L;
            long[] cadence={50_000_000L,16_666_667L,8_000_000L,6_944_444L,6_060_606L,4_166_667L,2_777_778L,13_000_000L};
            for(int phase=0;phase<cadence.length;phase++) {
                long input=77+phase,epoch=8+(phase==2?1:0);
                long step=cadence[phase];
                long phaseStart=clock;
                for(int i=0;clock-phaseStart<5_150_000_000L;i++) {
                    long at=api.GetTickCount64();
                    byte[] state=CinematicStateTest.packet();var b=ByteBuffer.wrap(state).order(ByteOrder.LITTLE_ENDIAN);
                    b.putLong(8,phase*10000L+i+1).putLong(16,at).putLong(24,epoch).putLong(32,input);
                    b.putDouble(48,.5).putDouble(56,.5).putDouble(64,.5);
                    b.putDouble(72,.5).putDouble(80,2.1).putDouble(88,.5);
                    view.write(88,state,0,state.length);
                    byte[] environment=EnvironmentFrameTest.packet();ByteBuffer.wrap(environment).order(ByteOrder.LITTLE_ENDIAN).putLong(16,at);
                    var env=EnvironmentFrame.decode(environment,at,7);assertNotNull(env);
                    var world=WorldFrame.decode(WorldFrameTest.packet(at),at);assertNotNull(world);
                    runtime.update(reader,env,world,true,clock+=step);
                    if(clock-phaseStart<4_900_000_000L)assertEquals(0,view.getInt(40),"The full configured idle period must be honored");
                }
                assertTrue(CineDirector.isActive(),"Idle duration must not grow with host frame cadence; step="+step);
                assertEquals(1,view.getInt(40));assertEquals(epoch,view.getLong(24));assertEquals(input,view.getLong(32));
                // A synchronized sample remains protocol-valid for 250ms.
                // A brief game publication stall must not reset an otherwise
                // unchanged active director or drop the cinematic bars.
                long sampledAt=api.GetTickCount64();
                var delayed=ByteBuffer.wrap(CinematicStateTest.packet()).order(ByteOrder.LITTLE_ENDIAN);
                delayed.putLong(16,sampledAt-140).putLong(24,epoch).putLong(32,input).putInt(44,1);
                delayed.putDouble(48,.5).putDouble(56,.5).putDouble(64,.5);
                delayed.putDouble(72,.5).putDouble(80,2.1).putDouble(88,.5);
                view.write(88,delayed.array(),0,112);
                var delayedEnv=EnvironmentFrameTest.packet();ByteBuffer.wrap(delayedEnv).order(ByteOrder.LITTLE_ENDIAN).putLong(16,sampledAt);
                runtime.update(reader,EnvironmentFrame.decode(delayedEnv,sampledAt,7),
                    WorldFrame.decode(WorldFrameTest.packet(sampledAt),sampledAt),true,clock+=step);
                assertTrue(CineDirector.isActive(),"A synchronized 140ms sample must preserve the active shot");
                assertTrue(runtime.active(),"Cinematic effects must remain active with the valid applied sample");
                assertEquals(1,view.getInt(40));
                // Input and a replaced native context both revoke an active
                // shot while the host/world/music remain otherwise eligible.
                long changedAt=api.GetTickCount64();
                var changed=ByteBuffer.wrap(CinematicStateTest.packet()).order(ByteOrder.LITTLE_ENDIAN);
                changed.putLong(16,phase==7?changedAt-251:changedAt)
                    .putLong(24,phase==1?epoch+1:epoch).putLong(32,phase==1||phase>=6?input:input+100);
                if(phase==6)changed.putInt(40,0); // Explicit invalid sample, never a contention lease.
                changed.putDouble(48,.5).putDouble(56,.5).putDouble(64,.5);
                view.write(88,changed.array(),0,112);
                var envBytes=EnvironmentFrameTest.packet();ByteBuffer.wrap(envBytes).order(ByteOrder.LITTLE_ENDIAN).putLong(16,changedAt);
                runtime.update(reader,EnvironmentFrame.decode(envBytes,changedAt,7),
                    WorldFrame.decode(WorldFrameTest.packet(changedAt),changedAt),true,clock+=50_000_000L);
                assertEquals(0,view.getInt(40));assertFalse(CineDirector.isActive());
                // Explicit focus/menu revocation immediately clears demand too.
                runtime.update(reader,null,null,false,clock+=50_000_000L);
                assertEquals(0,view.getInt(40));assertFalse(CineDirector.isActive());
                assertEquals(258,api.WaitForSingleObject(event,0));
            }
        }finally {
            CineDirector.onClientTick(null);CineSettings.applyJson(settings);hook.setBoolean(null,oldHook);
            if(audio!=null)audio.close();CloudMusic.player=oldPlayer;CloudMusic.currentlyPlaying=oldMusic;Files.deleteIfExists(wav);
            api.UnmapViewOfFile(view);api.CloseHandle(event);api.CloseHandle(mutex);api.CloseHandle(mapping);
        }
    }
}
