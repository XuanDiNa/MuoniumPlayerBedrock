package com.muoniumplayer.bedrock;

import java.io.*;
import javax.sound.sampled.AudioFormat;
import repackage.javazoom.jl.decoder.*;

/** Frame-at-a-time decode for MP3 radio and HLS AAC/ADTS. No complete download. */
public final class RadioFrames implements AutoCloseable {
    private final InputStream input;
    private Bitstream mp3;private Decoder mp3Decoder;
    private net.sourceforge.jaad.adts.ADTSDemultiplexer aac;
    private net.sourceforge.jaad.aac.Decoder aacDecoder;
    public RadioFrames(InputStream in)throws IOException {
        var buffered=new BufferedInputStream(in,8192);input=buffered;buffered.mark(4);
        byte[] h=buffered.readNBytes(2);buffered.reset();
        try {
            if(h.length==2&&(h[0]&255)==255&&(h[1]&246)==240){
                aac=new net.sourceforge.jaad.adts.ADTSDemultiplexer(buffered);
                aacDecoder=net.sourceforge.jaad.aac.Decoder.create(aac.getDecoderInfo());
            }else{mp3=new Bitstream(buffered);mp3Decoder=new Decoder();}
        }catch(Exception e){throw new IOException("Radio decoder could not initialize",e);}
    }
    public record Frame(byte[] pcm,AudioFormat format){}
    public Frame next()throws IOException {
        try {
            if(mp3!=null){Header h=mp3.readFrame();if(h==null)return null;
                SampleBuffer b=(SampleBuffer)mp3Decoder.decodeFrame(h,mp3);mp3.closeFrame();
                byte[] pcm=new byte[b.getBufferLength()*2];short[] s=b.getBuffer();
                for(int i=0;i<b.getBufferLength();i++){pcm[i*2]=(byte)s[i];pcm[i*2+1]=(byte)(s[i]>>8);}
                return new Frame(pcm,new AudioFormat(b.getSampleFrequency(),16,b.getChannelCount(),true,false));
            }
            // Some SBR streams begin with a partial frame. Allow a bounded
            // resync, but never announce playback after zero PCM output.
            for(int i=0;i<8;i++) {
                var b=new net.sourceforge.jaad.SampleBuffer();aacDecoder.decodeFrame(aac.readNextFrame(),b);
                if(b.getData().length==0)continue;
                b.setBigEndian(false);return new Frame(b.getData(),new AudioFormat(b.getSampleRate(),b.getBitsPerSample(),b.getChannels(),true,false));
            }
            throw new IOException("Unsupported or damaged AAC frames");
        }catch(EOFException end){return null;}catch(Exception e){throw new IOException("Radio frame decode failed",e);}
    }
    @Override public void close()throws IOException{input.close();}
}
