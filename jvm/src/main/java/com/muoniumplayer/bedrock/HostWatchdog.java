package com.muoniumplayer.bedrock;

import java.io.*;
import java.lang.management.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Independent, bounded host diagnostics. Never interrupts or restarts playback. */
final class HostWatchdog implements AutoCloseable {
    enum Stage { STARTING, EVENTS, TASKS, SETTINGS, ENVIRONMENT, CONTROLS, TICK, DRAW, PRESENT, IDLE, CLEANUP }
    private final Thread host,monitor;
    private final Consumer<String> sink;
    private final long threshold,pollMillis;
    private volatile long heartbeat=System.nanoTime();
    private volatile Stage stage=Stage.STARTING;
    private volatile boolean active=true;
    private AutoCloseable destination;
    private int reports;
    private long reportedAt;
    private boolean stalled;

    static HostWatchdog open(Thread host,Path logDirectory) {
        PrintWriter writer=null;
        try {
            Files.createDirectories(logDirectory);
            Path path=logDirectory.resolve("jvm-health-"+ProcessHandle.current().pid()+"-"+System.currentTimeMillis()+".log");
            writer=new PrintWriter(Files.newBufferedWriter(path,java.nio.charset.StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW),true);
            writer.println("HOST_HEALTH version="+com.muoniumplayer.MuoniumPlayerClient.MOD_VERSION+" reportsMax=3 threadNames=omitted action=diagnostics-only");
            writer.println("HOST_IMAGE sha256="+imageHash());
        }catch(Exception unavailable){if(writer!=null)writer.close();writer=null;}
        HostWatchdog watcher=new HostWatchdog(host,writer==null?System.err::println:writer::println,8_000_000_000L,250);
        watcher.destination=writer;
        return watcher;
    }
    private static String imageHash() {
        try {
            Path image=Path.of(HostWatchdog.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if(!Files.isRegularFile(image))return "development";
            try(var stream=Files.newInputStream(image)) {
                return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
            }
        }catch(Exception unavailable){return "unavailable";}
    }
    HostWatchdog(Thread host,Consumer<String> sink,long threshold,long pollMillis) {
        if(threshold<=0||pollMillis<=0)throw new IllegalArgumentException("Watchdog interval");
        this.host=Objects.requireNonNull(host);this.sink=Objects.requireNonNull(sink);
        this.threshold=threshold;this.pollMillis=pollMillis;
        monitor=new Thread(this::watch,"Muonium-host-watchdog");monitor.setDaemon(true);monitor.start();
    }
    void phase(Stage next){stage=Objects.requireNonNull(next);heartbeat=System.nanoTime();}
    private void watch() {
        while(active&&host.isAlive()) {
            try {
                long now=System.nanoTime(),age=now-heartbeat;
                if(age>=threshold&&reports<3&&(reportedAt==0||now-reportedAt>=threshold)) {
                    reportedAt=now;stalled=true;
                    sink.accept("HOST_STALL report="+(++reports)+" phase="+stage+" ageMs="+age/1_000_000+" action=observe");
                    capture();
                }else if(age<threshold&&stalled) {
                    stalled=false;sink.accept("HOST_RECOVERED phase="+stage);
                }
                Thread.sleep(pollMillis);
            }catch(InterruptedException stopped){break;}
            catch(Throwable unavailable){active=false;}
        }
    }
    private void capture() {
        ThreadMXBean bean=ManagementFactory.getThreadMXBean();
        long[] all=bean.getAllThreadIds();
        var ids=new ArrayList<Long>();ids.add(host.threadId());
        for(long id:all)if(id!=host.threadId()&&ids.size()<32)ids.add(id);
        long[] selected=ids.stream().mapToLong(Long::longValue).toArray();
        for(ThreadInfo info:bean.getThreadInfo(selected,24))if(info!=null) {
            String lock=info.getLockInfo()==null?"none":info.getLockInfo().getClassName();
            sink.accept("HOST_THREAD id="+info.getThreadId()+" role="+(info.getThreadId()==host.threadId()?"host":"worker")
                +" state="+info.getThreadState()+" native="+info.isInNative()+" lock="+lock+" owner="+info.getLockOwnerId());
            // Names and exception messages can contain tracks, URLs or account data.
            // Only executable locations and numeric lock ownership are recorded.
            for(StackTraceElement frame:info.getStackTrace())
                sink.accept("  at "+frame.getClassName()+"."+frame.getMethodName()+":"+frame.getLineNumber());
        }
    }
    public void close() {
        active=false;monitor.interrupt();
        if(Thread.currentThread()!=monitor)try{monitor.join(500);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        if(!monitor.isAlive()&&destination!=null)try{destination.close();}catch(Exception ignored){}
    }
}
