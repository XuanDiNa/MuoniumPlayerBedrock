package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class VoiceBroadcastTest {
    @TempDir Path root;
    static void until(BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+Duration.ofSeconds(3).toNanos();
        while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
    static float receiveFresh(VoiceBroadcast voice,double[] pcm,BufferedWriter commands,BufferedReader reader)throws Exception{
        // Both sides deliberately drop a contended non-waiting attempt. A single
        // read is not guaranteed to succeed while the owner's heartbeat runs.
        for(int i=0;i<75;i++){
            voice.onOutput(pcm,2,48000);Thread.sleep(20);
            commands.write("read\n");commands.flush();String[] result=reader.readLine().split(" ");
            if(result[0].equals("1"))return Float.parseFloat(result[1]);
        }
        fail("No fresh PCM arrived within 1.5 seconds; status="+voice.status());return 0;
    }
    @Test void defaultsAreOffAndUnknownPluginDoesNotStartAnything()throws Exception{
        try(var voice=new VoiceBroadcast(root,()->true)){
            assertFalse(voice.enabled());assertEquals(VoiceBroadcast.Status.UNAVAILABLE,voice.status());
            voice.onOutput(new double[1920],2,48000);
            assertFalse(Files.exists(root.resolve("config/muonium/voice-broadcast.json")));
            voice.bind(ProcessHandle.current().pid());Thread.sleep(80);
            assertEquals(VoiceBroadcast.Status.UNAVAILABLE,voice.status());assertFalse(voice.requestSettings());
        }
    }
    @Test void actualNativeMailboxMusicSettingsAndDisconnect()throws Exception{
        Path exe=Path.of(System.getProperty("muonium.bschat.fixture"));assertTrue(Files.isRegularFile(exe));
        var process=new ProcessBuilder(exe.toString(),"--serve").redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try(var reader=process.inputReader();var commands=process.outputWriter();var voice=new VoiceBroadcast(root,()->true)){
            long pid=Long.parseLong(reader.readLine());voice.bind(pid);voice.setEnabled(true);
            until(()->voice.status()==VoiceBroadcast.Status.READY);
            double[] pcm=new double[1920];java.util.Arrays.fill(pcm,.25);
            assertEquals(.25f,receiveFresh(voice,pcm,commands,reader),1e-5);
            voice.setEnabled(false);Thread.sleep(80);commands.write("read\n");commands.flush();assertTrue(reader.readLine().startsWith("0 "));
            assertTrue(voice.requestSettings());Thread.sleep(80);commands.write("settings\n");commands.flush();assertEquals("1",reader.readLine());
            commands.write("busy\n");commands.flush();assertEquals("ok",reader.readLine());Thread.sleep(60);
            assertEquals(VoiceBroadcast.Status.READY,voice.status());Thread.sleep(100);
            commands.write("off\n");commands.flush();assertEquals("ok",reader.readLine());
            until(()->voice.status()==VoiceBroadcast.Status.NOT_READY);voice.setEnabled(true);voice.onOutput(pcm,2,48000);
            commands.write("on\n");commands.flush();assertEquals("ok",reader.readLine());until(()->voice.status()==VoiceBroadcast.Status.READY);
            commands.write("read\n");commands.flush();assertTrue(reader.readLine().startsWith("0 ")); // old session PCM never replays
            commands.write("quit\n");commands.flush();
        }finally{process.destroy();assertTrue(process.waitFor(3,java.util.concurrent.TimeUnit.SECONDS));}
    }
    @Test void pausedMutedAndPreferenceChangesNeverRestartLocalPlayback()throws Exception{
        Path exe=Path.of(System.getProperty("muonium.bschat.fixture"));assertTrue(Files.isRegularFile(exe));
        var process=new ProcessBuilder(exe.toString(),"--serve").redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var playing=new java.util.concurrent.atomic.AtomicBoolean(true);
        try(var reader=process.inputReader();var commands=process.outputWriter();var voice=new VoiceBroadcast(root,playing::get)){
            voice.bind(Long.parseLong(reader.readLine()));voice.setGain(.5f);voice.setEnabled(true);
            until(()->voice.status()==VoiceBroadcast.Status.READY);
            double[] pcm=new double[1920];java.util.Arrays.fill(pcm,.4);
            assertEquals(.2f,receiveFresh(voice,pcm,commands,reader),1e-5);
            playing.set(false);Thread.sleep(80);voice.onOutput(pcm,2,48000);
            commands.write("read\n");commands.flush();assertTrue(reader.readLine().startsWith("0 "));
            playing.set(true);voice.setGain(0);Thread.sleep(60);voice.onOutput(pcm,2,48000);Thread.sleep(40);
            commands.write("read\n");commands.flush();assertTrue(reader.readLine().startsWith("0 "));
            commands.write("quit\n");commands.flush();
        }finally{process.destroy();process.waitFor(3,java.util.concurrent.TimeUnit.SECONDS);}
        try(var restored=new VoiceBroadcast(root,playing::get)){assertTrue(restored.enabled());assertEquals(0,restored.gain());}
    }
}
