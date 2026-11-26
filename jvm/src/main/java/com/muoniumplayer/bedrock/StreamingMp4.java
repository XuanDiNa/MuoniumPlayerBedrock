package com.muoniumplayer.bedrock;

import java.io.*;
import java.nio.*;
import java.util.*;
import repackage.net.sourceforge.jaad.mp4.MP4Container;
import repackage.net.sourceforge.jaad.mp4.api.AudioTrack;

/** AAC samples are decoded as they arrive, even inside a single song-sized mdat. */
final class StreamingMp4 implements ProgressiveAudio.FrameSource {
    final SeekableAudioInput input;final AudioTrack track;
    final net.sourceforge.jaad.aac.Decoder decoder;
    final int frames,channels,rate,packetFrames;final boolean fragmented;
    final long trackId;int defaultSize,packetAt;long mdatEnd;
    final String playAuth;com.muoniumplayer.core.ncm.music.QishuiStreamingCipher cipher;
    float[] first;List<Sample> samples=List.of();int sampleAt;
    record Box(int start,int payload,int end,String type){}
    record Sample(long offset,int size){}
    record Top(long start,long payload,long end,String type){}
    StreamingMp4(AudioRanges remote,InputStream start,long duration)throws Exception {
        this(remote,start,duration,null);
    }
    StreamingMp4(AudioRanges remote,InputStream start,long duration,String playAuth)throws Exception {
        this.playAuth=playAuth;
        input=new SeekableAudioInput(remote,start);
        byte[] init=metadata();
        var movie=new MP4Container(new ByteArrayInputStream(init)).getMovie();if(movie==null)throw new IOException("Missing MP4 movie");
        var tracks=movie.getTracks(AudioTrack.AudioCodec.AAC);
        if(tracks.size()!=1||!(tracks.get(0) instanceof AudioTrack audio)||!audio.isInFile())throw new IOException("Unsupported MP4 track");
        if(playAuth!=null&&!playAuth.isEmpty()) {
            cipher=new com.muoniumplayer.core.ncm.music.QishuiStreamingCipher(playAuth);
            try{cipher.metadata(init,audio.bedrockFrameCount());}catch(Exception e){cipher.close();throw e;}
        }else if(audio.getProtection()!=null)throw new IOException("Protected MP4 track requires original authorization");
        track=audio;trackId=track.bedrockTrackId();decoder=net.sourceforge.jaad.aac.Decoder.create(track.getDecoderSpecificInfo());
        fragmented=track.bedrockFrameCount()==0;
        if(fragmented) {
            for(Box moov:boxes(init,0,init.length))if(moov.type.equals("moov"))for(Box mvex:boxes(init,moov.payload,moov.end))if(mvex.type.equals("mvex"))for(Box trex:boxes(init,mvex.payload,mvex.end))if(trex.type.equals("trex")&&u32(init,trex.payload+4,trex.end)==trackId){
                if(u32(init,trex.payload+8,trex.end)!=1)throw new IOException("Unsupported MP4 sample description");defaultSize=size(u32(init,trex.payload+16,trex.end),true);}
        }
        byte[] encoded=encoded();if(encoded==null)throw new IOException("Empty AAC track");
        var decoded=decode(encoded);channels=decoded.channels();rate=decoded.rate();first=decoded.pcm();packetFrames=first.length/channels;
        frames=fragmented?StreamingFormats.durationFrames(duration,rate):Math.multiplyExact(track.bedrockFrameCount(),packetFrames);
    }
    record Decoded(float[] pcm,int rate,int channels){}
    byte[] metadata()throws IOException {
        byte[] ftyp=null;
        for(int count=0;count<256;count++) {
            Top box=top();if(box==null)throw new IOException("Missing MP4 metadata");
            if(box.type.equals("mdat")) {
                if(!input.canSeek())throw new AudioRanges.Unsupported();input.seek(box.end);continue;
            }
            if(box.end-box.start>8*1024*1024||box.payload-box.start!=8)throw new IOException("Excessive MP4 metadata");
            int n=(int)(box.end-box.start);byte[] bytes=new byte[n];ByteBuffer.wrap(bytes).putInt(n).put(box.type.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            byte[] body=StreamingFormats.read(input,n-8);System.arraycopy(body,0,bytes,8,body.length);
            if(box.type.equals("ftyp")){if(ftyp!=null)throw new IOException("Duplicate MP4 signature");ftyp=bytes;}
            if(box.type.equals("moov")) {
                if(ftyp==null)throw new IOException("Missing MP4 signature");validate(bytes,8,bytes.length,0);
                var out=new ByteArrayOutputStream();out.write(ftyp);out.write(bytes);return out.toByteArray();
            }
        }throw new IOException("Excessive MP4 metadata boxes");
    }
    static void validate(byte[] data,int from,int end,int depth)throws IOException {
        if(depth>12)throw new IOException("Excessive MP4 metadata nesting");
        for(var box:boxes(data,from,end)) {
            if(Set.of("trak","mdia","minf","stbl","dinf","edts","mvex").contains(box.type))validate(data,box.payload,box.end,depth+1);
            int stride=switch(box.type){case "stts","ctts"->8;case "stsc"->12;case "stco","stss"->4;case "co64"->8;default->0;};
            if(stride>0){long count=u32(data,box.payload+4,box.end);if(count>262144||count*stride!=box.end-box.payload-8L)throw new IOException("Invalid MP4 table bound");}
            if(box.type.equals("stsz")){long constant=u32(data,box.payload+4,box.end),count=u32(data,box.payload+8,box.end);if(count>262144||constant>1024*1024||constant==0&&count*4!=box.end-box.payload-12L)throw new IOException("Invalid MP4 sample size bound");}
            // The pinned JAAD QuickTime tags handler cannot make progress on
            // some old metadata. Skip only that optional atom, preserving size.
            if(box.type.equals("udta")||box.type.equals("meta")||box.type.equals("tags"))System.arraycopy(new byte[]{'f','r','e','e'},0,data,box.start+4,4);
        }
    }
    Decoded decode(byte[] bytes)throws Exception {
        var buffer=new net.sourceforge.jaad.SampleBuffer();decoder.decodeFrame(bytes,buffer);
        return new Decoded(StreamingFormats.pcm(buffer.getData(),buffer.getChannels(),buffer.getBitsPerSample(),buffer.isBigEndian()),buffer.getSampleRate(),buffer.getChannels());
    }
    byte[] encoded()throws Exception {
        if(!fragmented){if(packetAt>=track.bedrockFrameCount())return null;int n=size(track.bedrockFrameSize(packetAt),false);long at=track.bedrockFrameOffset(packetAt++);
            if(at<0||input.remote.length>=0&&at>input.remote.length-n)throw new IOException("Invalid AAC sample offset");input.seek(at);byte[] bytes=StreamingFormats.read(input,n);return cipher==null?bytes:cipher.decode(bytes,packetAt-1);}
        while(sampleAt==samples.size())if(!fragment())return null;
        var sample=samples.get(sampleAt++);if(sample.offset<input.position)throw new IOException("Overlapping MP4 samples");
        input.skipNBytes(sample.offset-input.position);return StreamingFormats.read(input,sample.size);
    }
    boolean fragment()throws IOException {
        if(mdatEnd>0){input.skipNBytes(mdatEnd-input.position);mdatEnd=0;}
        byte[] moof=null;long moofAt=0;Top mdat=null;
        for(int count=0;count<4096;count++) {
            Top box=top();if(box==null){if(moof!=null)throw new EOFException("Missing MP4 media");return false;}
            if(box.type.equals("moof")){if(moof!=null)throw new IOException("MP4 fragment missing media");long n=box.end-box.start;if(n>1024*1024)throw new IOException("Excessive MP4 fragment metadata");
                byte[] body=StreamingFormats.read(input,(int)(box.end-box.payload));var out=new ByteArrayOutputStream();out.write(ByteBuffer.allocate(8).putInt((int)n).putInt(0x6d6f6f66).array());out.write(body);moof=out.toByteArray();moofAt=box.start;
            }else if(box.type.equals("mdat")){if(moof==null)throw new IOException("Media without MP4 fragment");mdat=box;break;}
            else {if(box.end-box.payload>1024*1024)throw new IOException("Excessive MP4 side data");input.skipNBytes(box.end-input.position);}
        }
        if(moof==null||mdat==null)throw new IOException("Missing MP4 fragment");
        samples=plan(moof,moofAt,mdat);sampleAt=0;mdatEnd=mdat.end;
        if(samples.isEmpty())throw new IOException("Empty MP4 audio fragment");return true;
    }
    List<Sample> plan(byte[] data,long moofAt,Top mdat)throws IOException {
        var result=new ArrayList<Sample>();Box root=boxes(data,0,data.length).get(0);int matches=0,trafIndex=0;
        for(Box traf:boxes(data,root.payload,root.end))if(traf.type.equals("traf")) {
            var children=boxes(data,traf.payload,traf.end);Box tfhd=one(children,"tfhd");
            if(u32(data,tfhd.payload+4,tfhd.end)!=trackId){trafIndex++;continue;}
            if(++matches>1)throw new IOException("Duplicate MP4 audio fragment");
            for(var b:children)if(Set.of("senc","saiz","saio").contains(b.type))throw new IOException("Protected MP4 fragment");
            int flags=flags(data,tfhd,0x03003b),at=tfhd.payload+8;long base=moofAt;
            if((flags&1)!=0){base=u64(data,at,tfhd.end);at+=8;}else if((flags&0x020000)==0&&trafIndex!=0)throw new IOException("Ambiguous MP4 fragment base");
            if((flags&2)!=0){if(u32(data,at,tfhd.end)!=1)throw new IOException("Unknown MP4 description");at+=4;}
            if((flags&8)!=0)at+=4;int size=defaultSize;
            if((flags&16)!=0){size=size(u32(data,at,tfhd.end),true);at+=4;}if((flags&32)!=0)at+=4;
            if(at!=tfhd.end)throw new IOException("Invalid MP4 fragment header");
            long cursor=mdat.payload;
            for(Box run:children)if(run.type.equals("trun")) {
                int f=flags(data,run,0x000f05);long count=u32(data,run.payload+4,run.end);at=run.payload+8;
                if(count>262144-result.size()||((flags&0x010000)!=0&&count!=0))throw new IOException("Excessive MP4 sample count");
                if((f&1)!=0){cursor=Math.addExact(base,(int)u32(data,at,run.end));at+=4;}if((f&4)!=0)at+=4;
                for(int i=0;i<count;i++) {
                    if((f&256)!=0)at+=4;int n=size;if((f&512)!=0){n=size(u32(data,at,run.end),false);at+=4;}
                    if((f&1024)!=0)at+=4;if((f&2048)!=0)at+=4;
                    if(n<=0||cursor<mdat.payload||cursor>mdat.end-n||!result.isEmpty()&&cursor<result.get(result.size()-1).offset+result.get(result.size()-1).size)throw new IOException("Invalid MP4 sample extent");
                    result.add(new Sample(cursor,n));cursor+=n;
                }
                if(at!=run.end)throw new IOException("Invalid MP4 sample table");
            }trafIndex++;
        }return result;
    }
    Top top()throws IOException {
        long start=input.position;byte[] h=input.readNBytes(8);if(h.length==0)return null;if(h.length!=8)throw new EOFException();
        long size=u32(h,0,8);String type=StreamingFormats.word(h,4);int header=8;
        if(size==1){size=u64(StreamingFormats.read(input,8),0,8);header=16;}else if(size==0){if(input.remote.length<0)throw new IOException("Unbounded MP4 box");size=input.remote.length-start;}
        if(size<header||start>Long.MAX_VALUE-size||input.remote.length>=0&&start+size>input.remote.length)throw new IOException("Invalid MP4 box");
        return new Top(start,start+header,start+size,type);
    }
    public float[] next()throws Exception {
        if(first!=null){var f=first;first=null;return f;}byte[] bytes=encoded();if(bytes==null)return null;var d=decode(bytes);
        if(d.rate!=rate||d.channels!=channels||d.pcm.length/channels!=packetFrames)throw new IOException("MP4 audio format changed");return d.pcm;
    }
    AudioSeek.Located locate(long target)throws Exception {
        if(fragmented||!input.canSeek())throw new AudioRanges.Unsupported();
        var other=new StreamingMp4(input.remote,new BufferedInputStream(input.remote.from(0),32768),0,playAuth);
        int packet=(int)Math.max(0,target/packetFrames-2);other.packetAt=packet;other.first=null;
        try{return new AudioSeek.Located(other,(long)packet*packetFrames,other.next());}catch(Exception e){other.close();throw e;}
    }
    public int frames(){return frames;}public int rate(){return rate;}public int channels(){return channels;}public String codec(){return fragmented?"fmp4":"m4a";}
    public boolean exact(){return !fragmented;}public void close()throws IOException{if(cipher!=null)cipher.close();input.close();}
    static List<Box> boxes(byte[] data,int from,int end)throws IOException {
        var list=new ArrayList<Box>();for(int at=from;at<end;){long n=u32(data,at,end);int header=8;String type=StreamingFormats.word(data,at+4);
            if(n==1){n=u64(data,at+8,end);header=16;}else if(n==0)n=end-at;
            if(n<header||n>end-at||list.size()>4096)throw new IOException("Invalid MP4 metadata box");list.add(new Box(at,at+header,at+(int)n,type));at+=(int)n;
        }return list;
    }
    static Box one(List<Box> boxes,String type)throws IOException {Box found=null;for(var box:boxes)if(box.type.equals(type)){if(found!=null)throw new IOException("Duplicate MP4 metadata");found=box;}if(found==null)throw new IOException("Missing MP4 metadata");return found;}
    static int flags(byte[] data,Box b,int allowed)throws IOException{long n=u32(data,b.payload,b.end);if(n>>>24>1||(n&0xffffff&~allowed)!=0)throw new IOException("Unsupported MP4 flags");return (int)n&0xffffff;}
    static int size(long n,boolean zero)throws IOException{if(n<(zero?0:1)||n>1024*1024)throw new IOException("Excessive AAC packet");return (int)n;}
    static long u32(byte[] b,int p,int end)throws IOException{if(p<0||p>end-4||end>b.length)throw new EOFException("Truncated MP4 metadata");return Integer.toUnsignedLong(ByteBuffer.wrap(b,p,4).getInt());}
    static long u64(byte[] b,int p,int end)throws IOException{long hi=u32(b,p,end),lo=u32(b,p+4,end);if(hi>0x7fffffffL)throw new IOException("Excessive MP4 offset");return hi<<32|lo;}
}
