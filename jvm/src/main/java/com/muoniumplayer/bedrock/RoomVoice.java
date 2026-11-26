package com.muoniumplayer.bedrock;

import com.google.gson.*;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.EngineOutputTap;
import com.muoniumplayer.party.MpPartyClient;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** Client-only, explicit room audio. Hardware and codecs belong to the audio worker. */
public final class RoomVoice implements AutoCloseable {
    public interface Device extends AutoCloseable { void play(float[] pcm); void close(); }
    public record DeviceChoice(String id,String label){}
    public record DeviceList(List<DeviceChoice> inputs,List<DeviceChoice> outputs){
        static DeviceList defaults(){var defaults=List.of(new DeviceChoice("","系统默认"));return new DeviceList(defaults,defaults);}
    }
    public interface Devices {
        Device open(boolean microphone, Consumer<float[]> capture) throws Exception;
        default Device open(boolean microphone,String input,String output,Consumer<float[]> capture)throws Exception{return open(microphone,capture);}
        default DeviceList catalog(){return DeviceList.defaults();}
    }
    private static final class Holder {
        static final RoomVoice INSTANCE=new RoomVoice(Path.of("."),new RoomVoiceDevices.Factory(),
                ()->{var r=MpPartyClient.room();return r==null?"":r.id;},
                ()->{var p=CloudMusic.player;return p!=null&&p.isPlaying()&&CloudMusic.getVolume()>0;});
    }
    public static RoomVoice global(){return Holder.INSTANCE;}
    private record Connection(Object owner,UUID self,Consumer<JsonObject> send){}
    private record Session(String room,String epoch,boolean spatialCapable){}
    private record Packet(long at,byte[] data,RoomVoiceSpatial.Source spatial){}
    private record Observation(RoomVoiceWorld world,EnvironmentFrame environment,long at,String token){}
    private static final class Speaker {
        final String stream; long seq=-1,lastAudio; final ArrayDeque<Packet> packets=new ArrayDeque<>();
        Speaker(String stream){this.stream=stream;}
    }
    private final Path preferences;
    private final Devices devices; private final Supplier<String> currentRoom; private final BooleanSupplier playing;
    private volatile boolean enabled,microphone,music,deafened,closed,ready,accepting,dirty;
    private volatile boolean spatial;
    private volatile int spatialRange=32;
    private volatile Observation observation;
    private long armedEpoch;private int armedDimension=-1;
    private volatile String inputDevice="",outputDevice="";
    private volatile DeviceList deviceList=DeviceList.defaults();
    private final AtomicBoolean deviceListing=new AtomicBoolean();
    private volatile boolean devicesListed;
    private volatile float sendGain=1,playbackGain=1,microphoneGain=1;
    private volatile String error="";
    private Connection connection; private Session session; private long revision;
    private final Map<UUID,Speaker> incoming=new HashMap<>();
    private final ArrayBlockingQueue<float[]> captured=new ArrayBlockingQueue<>(8);
    private volatile BroadcastPcmQueue musicQueue=new BroadcastPcmQueue(4096);
    private final AtomicBoolean attached=new AtomicBoolean();
    private Thread worker;
    // A device failure can restart the worker without changing the host's session epoch.
    private long nextSequence,sentFrames,receivedFrames,spatialDrops;

    RoomVoice(Path root,Devices devices,Supplier<String> currentRoom,BooleanSupplier playing){
        this.devices=devices;this.currentRoom=currentRoom;this.playing=playing;
        preferences=root.toAbsolutePath().normalize().resolve("config/muonium/room-voice.json");
        try {
            SessionMedia.requirePlainRoot(preferences.getParent());
            if(Files.isRegularFile(preferences,LinkOption.NOFOLLOW_LINKS)&&!SessionMedia.isLink(preferences)&&Files.size(preferences)<65536){
                var o=JsonParser.parseString(Files.readString(preferences)).getAsJsonObject();
                enabled=flag(o,"enabled");microphone=flag(o,"microphone");music=flag(o,"music");
                deafened=flag(o,"deafened");inputDevice=deviceId(o,"inputDevice");outputDevice=deviceId(o,"outputDevice");
                spatial=flag(o,"spatial");int range=o.has("spatialRange")?o.get("spatialRange").getAsInt():32;
                if(RoomVoiceSpatial.validRange(range))spatialRange=range;
                sendGain=gain(o,"sendGain",1);playbackGain=gain(o,"playbackGain",1);microphoneGain=gain(o,"microphoneGain",4);
            }
        }catch(Exception ignored){enabled=microphone=music=false;}
    }
    private static boolean flag(JsonObject o,String key){return o.has(key)&&o.get(key).getAsBoolean();}
    private static String deviceId(JsonObject o,String key){String v=o.has(key)?o.get(key).getAsString():"";if(v.length()>512)throw new IllegalArgumentException("Device id");return v;}
    private static float gain(JsonObject o,String key,float max){float v=o.has(key)?o.get(key).getAsFloat():1;return Float.isFinite(v)&&v>=0&&v<=max?v:1;}
    public boolean enabled(){return enabled;} public boolean microphone(){return microphone;} public boolean music(){return music;}
    public float microphoneGain(){return microphoneGain;}
    public float sendGain(){return sendGain;} public float playbackGain(){return playbackGain;} public boolean ready(){return ready;}
    public boolean deafened(){return deafened;}public String inputDevice(){return inputDevice;}public String outputDevice(){return outputDevice;}
    public boolean spatial(){return spatial;}public int spatialRange(){return spatialRange;}
    public synchronized void setSpatial(boolean value){if(spatial!=value){spatial=value;armCurrentWorld();changed();}}
    public synchronized void confirmSpatialWorld(){if(spatial){armCurrentWorld();changed();}}
    public synchronized void setSpatialRange(int range){if(!RoomVoiceSpatial.validRange(range))throw new IllegalArgumentException("Spatial range");spatialRange=range;dirty=true;start();}
    private Observation currentObservation(){var o=observation;return o!=null&&System.nanoTime()-o.at<=350_000_000L?o:null;}
    private void armCurrentWorld(){var o=currentObservation();armedEpoch=spatial&&o!=null?o.world.epoch():0;armedDimension=o==null?-1:o.world.dimension();}
    private boolean spatialArmed(){var o=currentObservation();return o!=null&&armedEpoch!=0&&armedEpoch==o.world.epoch()&&armedDimension==o.world.dimension();}
    public synchronized boolean needsGeometry(){return enabled&&spatial&&!deafened&&spatialArmed();}
    /** Tick publishes owned snapshots only. No tracing, IO or device operations here. */
    public synchronized void updatePose(RoomVoiceWorld world,EnvironmentFrame environment){
        var old=observation;boolean changedWorld=old!=null&&(world==null||old.world.epoch()!=world.epoch()||old.world.dimension()!=world.dimension());
        if(changedWorld&&armedEpoch!=0){armedEpoch=0;armedDimension=-1;if(spatial)changed();}
        String token=old!=null&&!changedWorld?old.token:UUID.randomUUID().toString();
        observation=world==null?null:new Observation(world,environment,System.nanoTime(),token);
    }
    public void toggleMicrophone(){setMicrophone(!microphone);}
    public synchronized void toggleDeafened(){deafened=!deafened;changed();}
    public synchronized void setInputDevice(String id){if(id==null||id.length()>512)throw new IllegalArgumentException("Device id");if(!id.equals(inputDevice)){inputDevice=id;changed();}}
    public synchronized void setOutputDevice(String id){if(id==null||id.length()>512)throw new IllegalArgumentException("Device id");if(!id.equals(outputDevice)){outputDevice=id;changed();}}
    public void refreshDevices(){if(closed||!deviceListing.compareAndSet(false,true))return;Thread.ofVirtual().name("muonium-voice-devices").start(()->{try{deviceList=devices.catalog();}catch(Exception failed){error="无法列出音频设备";}finally{devicesListed=true;deviceListing.set(false);}});}
    private List<DeviceChoice> choices(boolean input){if(!devicesListed)refreshDevices();return input?deviceList.inputs:deviceList.outputs;}
    public String deviceLabel(boolean input){String id=input?inputDevice:outputDevice;return choices(input).stream().filter(c->c.id.equals(id)).map(DeviceChoice::label).findFirst().orElse("装置不可用（请重新选择）");}
    public String[] deviceOptions(boolean input){return choices(input).stream().map(DeviceChoice::label).toArray(String[]::new);}
    public void selectDevice(boolean input,String label){choices(input).stream().filter(c->c.label.equals(label)).findFirst().ifPresent(c->{if(input)setInputDevice(c.id);else setOutputDevice(c.id);});}
    public void nextDevice(boolean input){var options=deviceOptions(input);String current=deviceLabel(input);int i=Arrays.asList(options).indexOf(current);selectDevice(input,options[(i+1)%options.length]);}
    public synchronized void setEnabled(boolean v){if(enabled!=v){enabled=v;changed();}}
    public synchronized void setMicrophone(boolean v){if(microphone!=v){microphone=v;changed();}}
    public synchronized void setMusic(boolean v){if(music!=v){music=v;accepting=false;musicQueue=new BroadcastPcmQueue(4096);dirty=true;start();}}
    public void setMicrophoneGain(float v){if(!Float.isFinite(v)||v<0||v>4)throw new IllegalArgumentException("Microphone gain");microphoneGain=v;dirty=true;start();}
    public void setSendGain(float v){checkGain(v);sendGain=v;dirty=true;start();}
    public void setPlaybackGain(float v){checkGain(v);playbackGain=v;dirty=true;start();}
    private static void checkGain(float v){if(!Float.isFinite(v)||v<0||v>1)throw new IllegalArgumentException("Voice gain");}
    private void changed(){revision++;ready=accepting=false;error="";incoming.clear();captured.clear();musicQueue=new BroadcastPcmQueue(4096);dirty=true;start();}
    private synchronized void start(){if(worker==null&&!closed){worker=Thread.ofVirtual().name("muonium-room-audio").start(this::run);}}
    public synchronized void bind(Object owner,UUID self,Consumer<JsonObject> send){
        if(closed)return;connection=new Connection(Objects.requireNonNull(owner),Objects.requireNonNull(self),Objects.requireNonNull(send));session=null;changed();
    }
    public synchronized void unbind(Object owner){if(connection!=null&&connection.owner==owner){connection=null;session=null;changed();}}
    public synchronized void receive(Object owner,JsonObject m){
        if(closed||connection==null||connection.owner!=owner)return;
        try {
            String op=m.get("op").getAsString();
            if(RoomVoiceRouter.SESSION.equals(op)){
                if(m.get("version").getAsInt()!=1)return;
                String room=m.get("room").getAsString(),epoch=m.get("epoch").getAsString();
                if(room.length()>128)return;if(!room.isEmpty())UUID.fromString(epoch);else if(!epoch.isEmpty())return;
                boolean supportsSpatial=m.has("spatialVersion")&&m.get("spatialVersion").getAsInt()==1;
                Session next=new Session(room,epoch,supportsSpatial);if(!next.equals(session)){session=next;changed();}return;
            }
            if(!enabled||deafened||!ready||session==null||!RoomVoiceRouter.AUDIO.equals(op)||!session.epoch.equals(m.get("epoch").getAsString()))return;
            UUID sender=UUID.fromString(m.get("sender").getAsString());if(sender.equals(connection.self))return;
            String stream=m.has("stream")?m.get("stream").getAsString():session.epoch;UUID.fromString(stream);
            long seq=m.get("seq").getAsBigDecimal().longValueExact();if(seq<0)return;
            String data=m.get("data").getAsString();if(data.length()>536)return;
            byte[] packet=Base64.getDecoder().decode(data);if(packet.length<1||packet.length>400)return;
            var position=m.has("spatial")?RoomVoiceSpatial.Source.decode(m.getAsJsonObject("spatial")):null;
            if(position!=null&&(!spatial||!session.spatialCapable||!spatialArmed())){spatialDrops++;return;}
            stream+=position==null?":room":":"+position.world();
            Speaker s=incoming.get(sender);
            if(s==null||!s.stream.equals(stream)){if(s==null&&incoming.size()>=7)return;s=new Speaker(stream);incoming.put(sender,s);}
            if(seq<=s.seq)return;s.seq=seq;s.lastAudio=System.nanoTime();if(s.packets.size()==8)s.packets.removeFirst();s.packets.addLast(new Packet(s.lastAudio,packet,position));receivedFrames++;
        }catch(RuntimeException invalid){/* Untrusted room frame. */}
    }
    /** Existing final-engine hook; installation does not activate any audio endpoint. */
    public void ensureInstalled(){if(closed)return;EngineOutputTap.install();if(attached.compareAndSet(false,true))EngineOutputTap.addListener(this::onOutput);}
    void onOutput(double[] pcm,int channels,int rate){if(accepting&&!closed&&music&&!deafened)musicQueue.offer(pcm,channels,rate,1);}
    public String statusText(){
        if(!error.isEmpty())return error;
        if(!enabled)return "房间语音已关闭";
        if(deafened)return "已拒听 · 麦克风及音乐送出已暂停";
        if(spatial){synchronized(this){if(!spatialArmed())return "距离模式已暂停：进入同一世界后点击重新启用";
            if(session!=null&&!session.spatialCapable)return "房主版本不支持距离语音，请升级房主";}}
        if(!ready)return "等待支持语音的一起听房间";
        return "已连接"+(microphone?" · 麦克风开启":" · 麦克风关闭")+(music?" · 音乐广播开启":"");
    }
    private void run(){
        Device device=null;RoomVoiceCodec encoder=null;Map<UUID,RoomVoiceCodec> decoders=new HashMap<>();Map<UUID,String> streams=new HashMap<>();
        Map<UUID,RoomVoiceSpatial.Filter> filters=new HashMap<>();
        long activeRevision=-1,retryAt=0,diagnosticAt=0,deadline=System.nanoTime();boolean wasMusic=false,wasWanted=false;
        try {
            while(!closed){
                if(dirty)save();
                Connection c;Session s;long r;boolean wanted;
                synchronized(this){c=connection;s=session;r=revision;wanted=enabled&&!deafened&&c!=null&&s!=null&&!s.epoch.isEmpty()&&s.room.equals(currentRoom.get())&&(!spatial||s.spatialCapable&&spatialArmed());}
                if(r!=activeRevision||wanted!=wasWanted){
                    ready=accepting=false;
                    if(device!=null){device.close();device=null;}if(encoder!=null){encoder.close();encoder=null;}
                    decoders.values().forEach(RoomVoiceCodec::close);decoders.clear();streams.clear();filters.clear();captured.clear();musicQueue=new BroadcastPcmQueue(4096);wasMusic=false;
                    activeRevision=r;wasWanted=wanted;retryAt=0;
                }
                long now=System.nanoTime();
                if(enabled&&c!=null&&now-diagnosticAt>5_000_000_000L){diagnosticAt=now;synchronized(this){
                    System.out.println("ROOM_VOICE mode="+(spatial?"distance":"room")+" poseFresh="+(currentObservation()!=null)
                        +" armed="+spatialArmed()+" hostCapable="+(s!=null&&s.spatialCapable)+" ready="+ready
                        +" tx="+sentFrames+" rx="+receivedFrames+" spatialDrops="+spatialDrops);
                }}
                if(wanted&&device==null&&now>=retryAt){
                    try {
                        encoder=new RoomVoiceCodec();final long token=r;
                        device=devices.open(microphone,inputDevice,outputDevice,pcm->{synchronized(this){if(closed||deafened||!ready||!microphone||revision!=token||pcm.length!=960)return;float[] copy=pcm.clone();if(!captured.offer(copy)){captured.poll();captured.offer(copy);}}});
                        synchronized(this){ready=revision==r&&!closed;}error="";
                    }catch(Throwable unavailable){if(encoder!=null){encoder.close();encoder=null;}error="语音设备或编解码器不可用，请检查设备后重新开启";retryAt=now+5_000_000_000L;}
                }
                if(wanted&&device!=null&&ready){
                    float[] mix=new float[1920];Map<UUID,Packet> frames=new HashMap<>();
                    synchronized(this){
                        incoming.values().removeIf(speaker->now-speaker.lastAudio>1_000_000_000L);
                        decoders.keySet().removeIf(id->{if(incoming.containsKey(id))return false;decoders.get(id).close();streams.remove(id);filters.remove(id);return true;});
                        for(var entry:incoming.entrySet()){
                            Speaker speaker=entry.getValue();while(!speaker.packets.isEmpty()&&now-speaker.packets.peekFirst().at>200_000_000L)speaker.packets.removeFirst();
                            if(!Objects.equals(streams.get(entry.getKey()),speaker.stream)){var old=decoders.remove(entry.getKey());if(old!=null)old.close();filters.remove(entry.getKey());streams.put(entry.getKey(),speaker.stream);}
                            Packet p=speaker.packets.pollFirst();if(p!=null)frames.put(entry.getKey(),p);
                        }
                    }
                    for(var entry:frames.entrySet())try{
                        float[] pcm=decoders.computeIfAbsent(entry.getKey(),id->new RoomVoiceCodec()).decode(entry.getValue().data);
                        var o=currentObservation();var position=entry.getValue().spatial;
                        double[] target=position==null?new double[]{Math.sqrt(.5),Math.sqrt(.5),18000}:
                            RoomVoiceSpatial.target(o==null?null:o.world,o==null?null:o.environment,position);
                        filters.computeIfAbsent(entry.getKey(),id->new RoomVoiceSpatial.Filter()).mix(pcm,mix,target,playbackGain);
                    }catch(IllegalArgumentException malformed){/* Isolated malformed speaker packet. */}
                    for(int i=0;i<mix.length;i++)mix[i]=Math.max(-1,Math.min(1,mix[i]));device.play(mix);
                    boolean musicActive=music&&playing.getAsBoolean();
                    if(musicActive!=wasMusic){accepting=false;musicQueue=new BroadcastPcmQueue(4096);wasMusic=musicActive;}accepting=musicActive;
                    float[] send=captured.poll();boolean has=send!=null;if(!has)send=new float[960];
                    // Amplify only the captured microphone before mixing music. One
                    // frame-wide peak limit preserves the waveform if boost exceeds full scale.
                    if(has){float boost=microphoneGain;double peak=0;
                        for(int i=0;i<960;i++){send[i]=Float.isFinite(send[i])?send[i]*boost:0;peak=Math.max(peak,Math.abs(send[i]));}
                        if(peak>1)for(int i=0;i<960;i++)send[i]/=(float)peak;
                    }
                    float[] song=new float[960];if(musicActive&&musicQueue.pollFrame(song)==960){has=true;for(int i=0;i<960;i++)send[i]+=song[i];}
                    if(has&&sendGain>0){
                        for(int i=0;i<960;i++)send[i]=Math.max(-1,Math.min(1,send[i]*sendGain));
                        byte[] packet=encoder.encode(send);var msg=new JsonObject();msg.addProperty("op",RoomVoiceRouter.AUDIO);msg.addProperty("epoch",s.epoch);msg.addProperty("seq",nextSequence++);msg.addProperty("data",Base64.getEncoder().encodeToString(packet));
                        synchronized(this){if(connection==c&&session==s&&revision==r&&ready){
                            if(spatial){var o=currentObservation();if(o==null||!spatialArmed())continue;var w=o.world;
                                msg.add("spatial",new RoomVoiceSpatial.Source(o.token,w.dimension(),w.x(),w.y(),w.z(),spatialRange).json());}
                            c.send.accept(msg);sentFrames++;
                        }}
                    }
                }
                deadline+=20_000_000L;long wait=deadline-System.nanoTime();if(wait>0)TimeUnit.NANOSECONDS.sleep(wait);else deadline=System.nanoTime();
            }
        }catch(InterruptedException stopped){Thread.currentThread().interrupt();}
        catch(Throwable failure){error="房间语音已停止，请重新开启";}
        finally {
            ready=accepting=false;if(device!=null)device.close();if(encoder!=null)encoder.close();decoders.values().forEach(RoomVoiceCodec::close);if(dirty)save();
            synchronized(this){worker=null;}
        }
    }
    private void save(){
        dirty=false;Path temp=null;
        try {
            SessionMedia.requirePlainRoot(preferences.getParent());Files.createDirectories(preferences.getParent());if(SessionMedia.isLink(preferences))throw new java.io.IOException("Voice preference link");
            var o=new JsonObject();o.addProperty("enabled",enabled);o.addProperty("microphone",microphone);o.addProperty("music",music);o.addProperty("sendGain",sendGain);o.addProperty("playbackGain",playbackGain);o.addProperty("microphoneGain",microphoneGain);
            o.addProperty("deafened",deafened);o.addProperty("inputDevice",inputDevice);o.addProperty("outputDevice",outputDevice);
            o.addProperty("spatial",spatial);o.addProperty("spatialRange",spatialRange);
            temp=Files.createTempFile(preferences.getParent(),".room-voice-",".tmp");Files.writeString(temp,new Gson().toJson(o));Files.move(temp,preferences,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception ignored){System.err.println("ROOM_VOICE settings save unavailable");}
        finally {if(temp!=null)try{Files.deleteIfExists(temp);}catch(Exception ignored){}}
    }
    public void close(){Thread w;synchronized(this){closed=true;ready=accepting=false;revision++;incoming.clear();captured.clear();w=worker;}if(w!=null){w.interrupt();try{w.join(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();}}else if(dirty)save();}
}

