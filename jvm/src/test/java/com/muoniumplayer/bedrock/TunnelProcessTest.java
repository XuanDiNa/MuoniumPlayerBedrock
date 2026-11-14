package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class TunnelProcessTest {
    static final class FakeProcess extends Process {
        private volatile boolean alive=true;
        private final CompletableFuture<Process> exit=new CompletableFuture<>();
        final PipedInputStream input=new PipedInputStream();
        final PipedOutputStream output;
        FakeProcess()throws Exception{output=new PipedOutputStream(input);}
        public InputStream getInputStream(){return input;}
        public InputStream getErrorStream(){return InputStream.nullInputStream();}
        public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}
        public boolean isAlive(){return alive;}
        public CompletableFuture<Process> onExit(){return exit;}
        public int exitValue(){if(alive)throw new IllegalThreadStateException();return 0;}
        public int waitFor(){destroy();return 0;}
        public boolean waitFor(long time,TimeUnit unit){return !alive;}
        public void destroy(){alive=false;try{output.close();}catch(Exception ignored){}exit.complete(this);}
        public Process destroyForcibly(){destroy();return this;}
    }
    @Test void urlIsNotReadyUntilEdgeRegisteredAndCloseKillsOnlyOwnedHelper()throws Exception {
        Path work=Files.createTempDirectory("room-helper-test");Files.writeString(work.resolve("config.yml"),"{}");
        var process=new FakeProcess();
        try(var helper=new TunnelProcess(process,work)) {
            helper.observe("INF https://fixture-test.trycloudflare.com");
            helper.observe("INF Registered tunnel connection connIndex=0");
            assertEquals(TunnelInviteTest.URL,helper.awaitReady(Duration.ofSeconds(1)));assertTrue(helper.isAlive());
        }
        assertFalse(process.isAlive());assertFalse(Files.exists(work));
    }
    @Test void helperCommandUsesOwnedConfigLoopbackAndNoAutoUpdater()throws Exception {
        var args=TunnelProcess.command(Path.of("C:/fixture with space/cloudflared.exe"),Path.of("C:/private config/config.yml"),24501);
        assertTrue(args.contains("--no-autoupdate"));assertTrue(args.contains("http://127.0.0.1:24501"));
        assertTrue(args.contains("127.0.0.1:0"));assertTrue(args.contains("--config"));
        assertEquals("auto",args.get(args.indexOf("--protocol")+1),"Permit QUIC with HTTP2 fallback for different networks");
        assertFalse(args.contains("--token"));assertFalse(args.contains("service"));
        Path wrong=Files.createTempFile("wrong-helper",".exe");
        try{assertThrows(IOException.class,()->TunnelProcess.verifyExecutable(wrong));}finally{Files.delete(wrong);}
    }
    @Test void timeoutCancelsPendingHelper()throws Exception {
        Path work=Files.createTempDirectory("room-helper-timeout");var process=new FakeProcess();
        try(var helper=new TunnelProcess(process,work)) {
            assertThrows(TimeoutException.class,()->helper.awaitReady(Duration.ofMillis(40)));
            assertFalse(helper.isAlive());
        }
    }
}
