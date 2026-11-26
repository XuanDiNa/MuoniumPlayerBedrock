package com.muoniumplayer.bedrock;

import java.io.*;
import java.util.*;

/** Codec positions are established by frame headers, never by a byte/time ratio. */
final class AudioSeek {
    final AudioRanges remote;final byte[] flacHeader;final int rate,channels,frames;final ProgressiveAudio.FrameSource original;
    final List<Long> mp3Offsets=new ArrayList<>();int mp3Samples;long scanOffset;
    AudioSeek(AudioRanges remote,byte[] header,ProgressiveAudio.FrameSource source){this.remote=remote;flacHeader=header;original=source;rate=source.rate();channels=source.channels();frames=source.frames();}
    record Located(ProgressiveAudio.FrameSource source,long frame,float[] first){}
    Located open(long target)throws Exception {
        if(flacHeader!=null)return flac(target);
        if(original instanceof StreamingMp4 mp4)return mp4.locate(target);
        if(original instanceof StreamingFormats.Wav wav){var found=wav.locate(remote,target);try{return new Located(found.source(),found.frame(),found.source().next());}catch(Exception e){found.source().close();throw e;}}
        if(original instanceof ProgressiveAudio.Mp3Source)return mp3(target);
        throw new AudioRanges.Unsupported();
    }
    private Located flac(long target)throws Exception {
        long low=42,high=remote.length-1,lowSample=0,highSample=frames;
        for(int attempt=0;attempt<24;attempt++){
            long estimate=low+(long)((double)Math.max(0,target-lowSample)/Math.max(1,highSample-lowSample)*(high-low));
            // The ratio only locates a candidate. FLAC's CRC-checked absolute
            // sample number below decides where these PCM samples belong.
            long offset=Math.max(low,Math.min(high,estimate-16384));
            var stream=remote.at(offset);ProgressiveAudio.FlacSource source=null;boolean retained=false;
            try{
                source=new ProgressiveAudio.FlacSource(new SequenceInputStream(new ByteArrayInputStream(flacHeader),stream));
                float[] first=source.next();if(first==null){high=offset-1;continue;}
                long sample=source.position;
                if(sample>target){high=offset-1;highSample=sample;}
                else if(target-sample<=rate*2L||high-low<32768){retained=true;return new Located(source,sample,first);}
                else{low=offset+1;lowSample=sample;}
            }finally{if(!retained){if(source!=null)source.close();else stream.close();}}
            if(high<low)break;
        }throw new IOException("Could not locate FLAC frame");
    }
    private Located mp3(long target)throws Exception {
        // MP3 has no absolute sample number in each frame. Build an exact
        // header index on demand, retaining only offsets (no encoded cache).
        // This may still transfer the compressed prefix on the first seek.
        if(mp3Offsets.isEmpty()){
            try(var in=new DataInputStream(remote.at(0))){
                byte[] h=in.readNBytes(10);
                if(h.length==10&&h[0]=='I'&&h[1]=='D'&&h[2]=='3'){
                    for(int i=6;i<10;i++)if((h[i]&128)!=0)throw new IOException("Invalid ID3 size");
                    scanOffset=10+((h[6]&127L)<<21)+((h[7]&127L)<<14)+((h[8]&127L)<<7)+(h[9]&127L)+((h[5]&16)!=0?10:0);
                }
            }
        }
        if(mp3Samples==0||mp3Offsets.size()<=target/mp3Samples){
            try(var in=new BufferedInputStream(remote.at(scanOffset),65536)){
                while(mp3Samples==0||mp3Offsets.size()<=target/mp3Samples){
                    byte[] h=in.readNBytes(4);if(h.length!=4)throw new EOFException("MP3 index truncated");
                    int version=(h[1]>>3)&3,layer=(h[1]>>1)&3,bitrate=(h[2]>>4)&15,sr=(h[2]>>2)&3;
                    if((h[0]&255)!=255||(h[1]&224)!=224||version==1||layer!=1||bitrate==0||bitrate==15||sr==3)throw new IOException("Unsupported MP3 index header");
                    int hz=new int[]{44100,48000,32000}[sr]/(version==3?1:version==2?2:4),samples=version==3?1152:576;
                    int kbps=(version==3?new int[]{0,32,40,48,56,64,80,96,112,128,160,192,224,256,320}:new int[]{0,8,16,24,32,40,48,56,64,80,96,112,128,144,160})[bitrate];
                    int length=(version==3?144000:72000)*kbps/hz+((h[2]>>1)&1);
                    if(hz!=rate||length<4||(mp3Samples!=0&&samples!=mp3Samples))throw new IOException("MP3 index layout changed");
                    mp3Samples=samples;mp3Offsets.add(scanOffset);in.skipNBytes(length-4);scanOffset+=length;
                }
            }
        }
        int index=(int)Math.max(0,target/mp3Samples-12); // Decoder/reservoir preroll.
        var source=new ProgressiveAudio.Mp3Source(remote.at(mp3Offsets.get(index)),(int)(remote.length-mp3Offsets.get(index)));
        return new Located(source,(long)index*mp3Samples,source.next());
    }
}
