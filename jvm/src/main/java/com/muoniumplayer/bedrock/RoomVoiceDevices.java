package com.muoniumplayer.bedrock;

import javax.sound.sampled.*;
import java.util.function.Consumer;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Microphone only, never system-loopback capture. Called off the UI/render threads. */
final class RoomVoiceDevices implements RoomVoice.Device {
    static final AudioFormat FORMAT=new AudioFormat(48000,16,1,true,false);
    static final AudioFormat OUTPUT_FORMAT=new AudioFormat(48000,16,2,true,false);
    static final class Factory implements RoomVoice.Devices {
        public RoomVoice.Device open(boolean mic,Consumer<float[]> sink)throws Exception{return open(mic,"","",sink);}
        public RoomVoice.Device open(boolean mic,String input,String output,Consumer<float[]> sink)throws Exception{return RoomVoiceDevices.open(mic,input,output,sink);}
        public RoomVoice.DeviceList catalog(){return new RoomVoice.DeviceList(choices(true),choices(false));}
    }
    private record Endpoint(String id,Mixer.Info info){}
    private static List<Endpoint> endpoints(boolean input){
        var result=new ArrayList<Endpoint>();var seen=new HashMap<String,Integer>();
        var kind=input?TargetDataLine.class:SourceDataLine.class;
        for(var info:AudioSystem.getMixerInfo()){
            var mixer=AudioSystem.getMixer(info);if(!mixer.isLineSupported(new DataLine.Info(kind,input?FORMAT:OUTPUT_FORMAT)))continue;
            String base=UUID.nameUUIDFromBytes((info.getName()+"/"+info.getVendor()+"/"+info.getVersion()+"/"+info.getDescription()).getBytes(StandardCharsets.UTF_8)).toString();
            result.add(new Endpoint(base+"-"+seen.merge(base,1,Integer::sum),info));
        }return result;
    }
    private static List<RoomVoice.DeviceChoice> choices(boolean input){
        var result=new ArrayList<RoomVoice.DeviceChoice>();result.add(new RoomVoice.DeviceChoice("","系统默认"));
        for(var endpoint:endpoints(input))result.add(new RoomVoice.DeviceChoice(endpoint.id,endpoint.info.getName()+" · "+result.size()));return List.copyOf(result);
    }
    private static DataLine line(String id,boolean input)throws LineUnavailableException {
        var kind=input?TargetDataLine.class:SourceDataLine.class;
        var format=input?FORMAT:OUTPUT_FORMAT;
        if(id.isEmpty())return (DataLine)AudioSystem.getLine(new DataLine.Info(kind,format));
        for(var endpoint:endpoints(input))if(endpoint.id.equals(id))return (DataLine)AudioSystem.getMixer(endpoint.info).getLine(new DataLine.Info(kind,format));
        throw new LineUnavailableException("Selected audio device unavailable");
    }
    private final SourceDataLine output;
    private TargetDataLine input;
    private volatile boolean closed;
    private Thread capture;
    private final byte[] bytes=new byte[3840];
    static RoomVoice.Device open(boolean microphone,String selectedInput,String selectedOutput,Consumer<float[]> sink)throws Exception {
        var format=FORMAT;
        SourceDataLine out=(SourceDataLine)line(selectedOutput,false);RoomVoiceDevices result=new RoomVoiceDevices(out);
        try {
            out.open(OUTPUT_FORMAT,15360);out.start();
            if(microphone){
                TargetDataLine in=(TargetDataLine)line(selectedInput,true);result.input=in;in.open(format,7680);in.start();
                result.capture=Thread.ofPlatform().daemon().name("muonium-room-microphone").start(()->{
                    byte[] raw=new byte[1920];int filled=0;
                    while(!result.closed){try{
                        int n=in.read(raw,filled,raw.length-filled);if(n<=0)return;filled+=n;if(filled<raw.length)continue;filled=0;
                        float[] pcm=new float[960];for(int i=0;i<960;i++)pcm[i]=(short)((raw[2*i]&255)|(raw[2*i+1]<<8))/32768f;sink.accept(pcm);
                    }catch(RuntimeException stopped){return;}}
                });
            }
            return result;
        }catch(Exception failure){result.close();throw failure;}
    }
    private RoomVoiceDevices(SourceDataLine output){this.output=output;}
    public void play(float[] pcm){
        if(closed||pcm.length!=1920)return;
        for(int i=0;i<1920;i++){float v=Float.isFinite(pcm[i])?Math.max(-1,Math.min(1,pcm[i])):0;int x=Math.round(v*32767);bytes[i*2]=(byte)x;bytes[i*2+1]=(byte)(x>>8);}
        if(output.available()>=bytes.length)output.write(bytes,0,bytes.length);
    }
    public void close(){closed=true;if(input!=null){input.stop();input.close();}output.stop();output.flush();output.close();if(capture!=null)capture.interrupt();}
}
