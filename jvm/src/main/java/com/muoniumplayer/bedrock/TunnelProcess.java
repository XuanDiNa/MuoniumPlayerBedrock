package com.muoniumplayer.bedrock;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Owned, non-service cloudflared process. No account or global config is loaded. */
final class TunnelProcess implements AutoCloseable {
    static final String SHA256="2493dbc168923bb3e59e5e7716f71deced92992eea9acd6e9d74f84ac7751343";
    private static final Pattern URL=Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com\\b");
    private final Process process;
    private final Path workspace;
    private final CompletableFuture<String> ready=new CompletableFuture<>();
    private final AtomicBoolean closed=new AtomicBoolean();
    private volatile String url;
    private volatile boolean registered;
    private volatile String failureClass="unavailable";
    TunnelProcess(Process process,Path workspace) {
        this.process=process;this.workspace=workspace;
        Thread.ofVirtual().name("muonium-cloudflared-output").start(()->{
            try(var reader=new BufferedReader(new InputStreamReader(process.getInputStream(),java.nio.charset.StandardCharsets.UTF_8))) {
                String line;while((line=reader.readLine())!=null)observe(line);
            }catch(IOException ignored){}
            finally{ready.completeExceptionally(new IOException("Tunnel helper exited before connection"));}
        });
        process.onExit().thenRun(()->{ready.completeExceptionally(new IOException("Tunnel helper exited"));cleanup();});
    }
    static List<String> command(Path executable,Path config,int port) {
        if(port<1||port>65535)throw new IllegalArgumentException("Tunnel local port");
        return List.of(executable.toAbsolutePath().toString(),"tunnel","--no-autoupdate","--config",config.toAbsolutePath().toString(),
            "--url","http://127.0.0.1:"+port,"--metrics","127.0.0.1:0","--protocol","auto",
            "--edge-ip-version","4","--loglevel","info","--management-diagnostics=false");
    }
    static Path executable()throws Exception {
        Path jar=Path.of(TunnelProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if(!Files.isRegularFile(jar)||!jar.getFileName().toString().equals("MuoniumBedrockHost.jar"))
            throw new IOException("Use the complete installed package");
        return jar.getParent().getParent().getParent().resolve("tunnel/cloudflared.exe");
    }
    static void verifyExecutable(Path exe)throws Exception {
        if(!Files.isRegularFile(exe)||Files.size(exe)!=55390512)throw new IOException("Bundled tunnel helper missing; update the complete package");
        var hash=MessageDigest.getInstance("SHA-256");
        try(var in=new DigestInputStream(Files.newInputStream(exe),hash)){in.transferTo(OutputStream.nullOutputStream());}
        if(!HexFormat.of().formatHex(hash.digest()).equals(SHA256))throw new IOException("Bundled tunnel helper checksum mismatch");
    }
    static TunnelProcess start(int port)throws Exception {
        if(Boolean.getBoolean("muonium.bedrock.offlineTest"))throw new IOException("Public tunnel disabled in offline fixtures");
        return startVerified(executable(),port);
    }
    /** Also used by the explicit disposable public-transport probe, never game state. */
    static TunnelProcess startVerified(Path exe,int port)throws Exception {
        verifyExecutable(exe);Path work=Files.createTempDirectory("muonium-room-");
        try {
            Path config=work.resolve("config.yml");Files.writeString(config,"{}\n");
            var builder=new ProcessBuilder(command(exe,config,port)).directory(work.toFile()).redirectErrorStream(true);
            // Ambient cloudflared variables can override explicit mode or load credentials.
            builder.environment().keySet().removeIf(k->k.startsWith("TUNNEL_")||k.equals("NO_TLS_VERIFY")||k.equals("NO_AUTOUPDATE"));
            return new TunnelProcess(builder.start(),work);
        }catch(Exception e){Files.deleteIfExists(work.resolve("config.yml"));Files.deleteIfExists(work);throw e;}
    }
    void observe(String line) {
        if(line.length()>8192||closed.get())return;
        if(line.contains("TLS handshake")||line.contains("tls handshake"))failureClass="edge-tls";
        else if(line.contains("timeout")||line.contains("timed out"))failureClass="edge-timeout";
        else if(line.contains("HTTP/2 connection is blocked"))failureClass="edge-http2-blocked";
        else if(line.contains("no such host"))failureClass="dns";
        var match=URL.matcher(line);if(match.find())url=match.group();
        if(line.contains("Registered tunnel connection"))registered=true;
        if(url!=null&&registered)ready.complete(url);
    }
    String awaitReady(Duration timeout)throws Exception {
        try{return ready.get(timeout.toMillis(),TimeUnit.MILLISECONDS);}
        catch(Exception e){
            System.err.println("ROOM_TUNNEL connect-failed class="+failureClass+" urlReceived="+(url!=null)+" edgeRegistered="+registered);
            close();throw e;
        }
    }
    boolean isAlive(){return process.isAlive()&&!closed.get();}
    public void close() {
        if(!closed.compareAndSet(false,true))return;
        ready.completeExceptionally(new CancellationException("Tunnel stopped"));process.destroy();
        Thread.ofVirtual().name("muonium-cloudflared-stop").start(()->{
            try{if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly().waitFor(2,TimeUnit.SECONDS);}
            catch(InterruptedException e){Thread.currentThread().interrupt();}finally{cleanup();}
        });
    }
    private void cleanup(){try{Files.deleteIfExists(workspace.resolve("config.yml"));Files.deleteIfExists(workspace);}catch(IOException ignored){}}
}
