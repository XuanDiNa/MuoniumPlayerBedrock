package com.muoniumplayer.bedrock;

import com.google.gson.*;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.EngineOutputTap;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Local PCM publisher. Audio callback performs only bounded queue writes. */
public final class VoiceBroadcast implements AutoCloseable {
    public enum Status {UNAVAILABLE,NOT_READY,READY,BROADCASTING}
    private static final class Holder{
        static final VoiceBroadcast INSTANCE=new VoiceBroadcast(Path.of("."),()->{
            var player=CloudMusic.player;return player!=null&&player.isPlaying()&&CloudMusic.getVolume()>0;
        });
    }
    public static VoiceBroadcast global(){return Holder.INSTANCE;}
    private final Path preferences;
    private final BooleanSupplier playing;
    private volatile boolean enabled,accepting,closed,attached,dirty;
    private volatile float gain=1;
    private volatile BroadcastPcmQueue queue=new BroadcastPcmQueue(4096);
    private volatile Status status=Status.UNAVAILABLE;
    private volatile VoiceMailbox mailbox;
    private final AtomicBoolean openSettings=new AtomicBoolean();
    private Thread worker;
    private long gamePid;
    VoiceBroadcast(Path root,BooleanSupplier playing){
        this.playing=playing;preferences=root.toAbsolutePath().normalize().resolve("config/muonium/voice-broadcast.json");
        try{
            SessionMedia.requirePlainRoot(preferences.getParent());
            if(Files.isRegularFile(preferences,LinkOption.NOFOLLOW_LINKS)&&!SessionMedia.isLink(preferences)&&Files.size(preferences)<65536){
                var o=JsonParser.parseString(Files.readString(preferences)).getAsJsonObject();
                enabled=o.has("enabled")&&o.get("enabled").getAsBoolean();
                float value=o.has("gain")?o.get("gain").getAsFloat():1;
                if(Float.isFinite(value)&&value>=0&&value<=1)gain=value;
            }
        }catch(Exception invalid){enabled=false;gain=1;}
    }
    public synchronized void bind(long pid){
        if(worker!=null||closed)return;
        gamePid=pid;worker=new Thread(this::run,"Muonium-BSChat-publisher");worker.setDaemon(true);worker.start();
    }
    /** Called only from AudioPlayer's existing post-engine initialization point. */
    public synchronized void ensureInstalled(){
        if(closed)return;
        EngineOutputTap.install();
        if(!attached){EngineOutputTap.addListener(this::onOutput);attached=true;}
    }
    public boolean enabled(){return enabled;}
    public void setEnabled(boolean value){enabled=value;accepting=false;dirty=true;}
    public float gain(){return gain;}
    public void setGain(float value){if(!Float.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("Broadcast gain");gain=value;dirty=true;}
    public Status status(){return status;}
    public String statusText(){return switch(status){case UNAVAILABLE->"未安装 BSChat／不支持当前平台";case NOT_READY->"等待支持 BSChat 的服务器";case READY->enabled?"已开启，等待歌曲音频":"已连接，广播关闭";case BROADCASTING->"正在广播当前音乐";};}
    public boolean requestSettings(){
        if(status==Status.UNAVAILABLE||status==Status.NOT_READY)return false;
        openSettings.set(true);return true;
    }
    void onOutput(double[] pcm,int channels,int rate){
        if(accepting&&!closed)queue.offer(pcm,channels,rate,gain);
    }
    private void freshQueue(){accepting=false;queue=new BroadcastPcmQueue(4096);}
    private void run(){
        float[] frame=new float[960];long session=-1,lastAudio=0,nextOpen=0;boolean active=false;
        try{
            while(!closed){
                if(dirty)save();
                long now=System.nanoTime();
                var ipc=mailbox;
                if(ipc==null&&now>=nextOpen){ipc=new VoiceMailbox(gamePid);nextOpen=now+500_000_000L;if(ipc.available())mailbox=ipc;else ipc.close();}
                if(ipc==null||!ipc.available()){status=Status.UNAVAILABLE;freshIfActive(active);active=false;Thread.sleep(20);continue;}
                var snapshot=ipc.snapshot();
                if(snapshot==null&&ipc.contended()){accepting=false;Thread.sleep(5);continue;}
                if(snapshot==null){freshIfActive(active);active=false;status=Status.UNAVAILABLE;ipc.close();mailbox=null;Thread.sleep(20);continue;}
                if(snapshot.session()!=session){freshQueue();active=false;session=snapshot.session();lastAudio=0;}
                if(openSettings.getAndSet(false))ipc.requestSettings();
                boolean wanted=enabled&&gain>0&&snapshot.ready()&&playing.getAsBoolean();
                if(!wanted){
                    if(active)freshQueue();active=false;accepting=false;
                    ipc.publish(session,null,false);status=snapshot.ready()?Status.READY:Status.NOT_READY;
                }else{
                    if(!active){freshQueue();active=true;}accepting=true;
                    if(queue.pollFrame(frame)==960){
                        if(ipc.publish(session,frame,true)){lastAudio=now;status=Status.BROADCASTING;}
                    }else if(now-lastAudio>100_000_000L){ipc.publish(session,null,false);status=Status.READY;}
                }
                Thread.sleep(20);
            }
        }catch(InterruptedException stopped){Thread.currentThread().interrupt();}
        catch(Throwable failure){System.err.println("BSCHAT publisher unavailable: "+failure.getClass().getSimpleName());}
        finally{
            accepting=false;var ipc=mailbox;mailbox=null;if(ipc!=null){ipc.publish(session,null,false);ipc.close();}
            status=Status.UNAVAILABLE;if(dirty)save();
        }
    }
    private void freshIfActive(boolean active){if(active)freshQueue();else accepting=false;}
    private void save(){
        dirty=false;Path temporary=null;
        try{
            SessionMedia.requirePlainRoot(preferences.getParent());Files.createDirectories(preferences.getParent());
            if(SessionMedia.isLink(preferences))throw new java.io.IOException("Voice preference is a link");
            var o=new JsonObject();o.addProperty("enabled",enabled);o.addProperty("gain",gain);
            temporary=Files.createTempFile(preferences.getParent(),".voice-",".tmp");Files.writeString(temporary,new Gson().toJson(o));
            Files.move(temporary,preferences,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception failed){System.err.println("BSCHAT could not save broadcast settings: "+failed.getClass().getSimpleName());}
        finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(Exception ignored){}}
    }
    public void close(){
        closed=true;accepting=false;Thread w;
        synchronized(this){w=worker;}
        if(w!=null){w.interrupt();try{w.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
        else if(dirty)save();
    }
}
