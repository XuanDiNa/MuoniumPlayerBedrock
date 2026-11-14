package com.muoniumplayer.bedrock;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded ISO-BMFF audio adapter. Emits owned AAC-LC ADTS, never video or DRM. */
final class FragmentedAac {
    static final int LIMIT=8*1024*1024,MAX_SAMPLES=4096;
    private static final int[] RATES={96000,88200,64000,48000,44100,32000,24000,22050,16000,12000,11025,8000,7350};
    private record Box(int start,int payload,int end,String type){}
    private record Sample(int start,int size){}
    private record Descriptor(int payload,int end,int tag){}
    private record Config(long track,int rate,int channels,int defaultSize){}
    private final Config config;
    private FragmentedAac(Config config){this.config=config;}
    boolean sameAudio(FragmentedAac other){return config.rate==other.config.rate&&config.channels==other.config.channels;}

    static FragmentedAac initialization(byte[] data)throws IOException {
        bounded(data);Box moov=one(boxes(data,0,data.length),"moov");Config selected=null;
        for(Box trak:boxes(data,moov.payload,moov.end))if(trak.type.equals("trak")) {
            var track=boxes(data,trak.payload,trak.end);Box mdia=one(track,"mdia");var media=boxes(data,mdia.payload,mdia.end);
            Box handler=one(media,"hdlr");need(handler.payload,12,handler.end);
            if(!word(data,handler.payload+8).equals("soun"))continue;
            if(selected!=null)throw bad("Multiple audio tracks");
            Box tkhd=one(track,"tkhd");need(tkhd.payload,4,tkhd.end);int version=data[tkhd.payload]&255;
            if(version>1)throw bad("Unsupported track header");
            long id=u32(data,tkhd.payload+(version==0?12:20),tkhd.end);if(id==0)throw bad("Invalid audio track");
            Box minf=one(media,"minf"),stbl=one(boxes(data,minf.payload,minf.end),"stbl");
            Box stsd=one(boxes(data,stbl.payload,stbl.end),"stsd");
            if(u32(data,stsd.payload+4,stsd.end)!=1)throw bad("Ambiguous audio descriptions");
            Box mp4a=one(boxes(data,stsd.payload+8,stsd.end),"mp4a");need(mp4a.payload,28,mp4a.end);
            if(u16(data,mp4a.payload+8,mp4a.end)!=0)throw bad("Unsupported audio entry version");
            var entries=boxes(data,mp4a.payload+28,mp4a.end);if(has(entries,"sinf"))throw bad("Encrypted audio entry");
            Box esds=one(entries,"esds");need(esds.payload,4,esds.end);
            Descriptor es=descriptor(data,esds.payload+4,esds.end,3);int at=es.payload;need(at,3,es.end);
            int flags=data[at+2]&255;at+=3;
            if((flags&128)!=0)at+=2;
            if((flags&64)!=0){need(at,1,es.end);at+=1+(data[at]&255);}
            if((flags&32)!=0)at+=2;
            Descriptor decoder=descriptor(data,at,es.end,4);need(decoder.payload,13,decoder.end);
            if((data[decoder.payload]&255)!=0x40||((data[decoder.payload+1]&255)>>2)!=5)throw bad("Unsupported audio codec");
            Descriptor info=descriptor(data,decoder.payload+13,decoder.end,5);need(info.payload,2,info.end);
            if(info.end-info.payload>64)throw bad("Excessive decoder configuration");
            int first=data[info.payload]&255,second=data[info.payload+1]&255;
            int profile=first>>3,rate=(first&7)*2+(second>>7),channels=(second>>3)&15;
            if(profile!=2||rate>=RATES.length||(channels!=1&&channels!=2)||(second&7)!=0)throw bad("Only 1024-frame mono/stereo AAC-LC is supported");
            if(info.end-info.payload>2) {
                int bit=(info.payload+2)*8,endBit=info.end*8;
                if(bits(data,bit,11,endBit)!=0x2b7||bits(data,bit+11,5,endBit)!=5||bits(data,bit+16,1,endBit)!=0)
                    throw bad("Unsupported AAC extension");
                for(bit+=17;bit<endBit;bit++)if(bits(data,bit,1,endBit)!=0)throw bad("Unsupported AAC extension padding");
            }
            if(u16(data,mp4a.payload+16,mp4a.end)!=channels||(u32(data,mp4a.payload+24,mp4a.end)>>>16)!=RATES[rate])throw bad("Conflicting audio configuration");
            Box mvex=one(boxes(data,moov.payload,moov.end),"mvex");int defaults=0,matches=0;
            for(Box trex:boxes(data,mvex.payload,mvex.end))if(trex.type.equals("trex")&&u32(data,trex.payload+4,trex.end)==id) {
                need(trex.payload,24,trex.end);if(++matches>1||u32(data,trex.payload+8,trex.end)!=1)throw bad("Ambiguous fragment defaults");
                defaults=size(u32(data,trex.payload+16,trex.end),true);
            }
            if(matches!=1)throw bad("Missing audio fragment defaults");selected=new Config(id,rate,channels,defaults);
        }
        if(selected==null)throw bad("No AAC-LC audio track");return new FragmentedAac(selected);
    }

    byte[] fragment(byte[] data)throws IOException {
        bounded(data);var top=boxes(data,0,data.length);var samples=new ArrayList<Sample>();boolean found=false;
        for(Box moof:top)if(moof.type.equals("moof")) {
            found=true;int trafIndex=0,matches=0;
            for(Box traf:boxes(data,moof.payload,moof.end))if(traf.type.equals("traf")) {
                var children=boxes(data,traf.payload,traf.end);Box tfhd=one(children,"tfhd");
                if(u32(data,tfhd.payload+4,tfhd.end)!=config.track){trafIndex++;continue;}
                if(++matches>1)throw bad("Duplicate audio fragment track");
                if(has(children,"senc")||has(children,"saiz")||has(children,"saio"))throw bad("Encrypted fragment");
                int flags=fullFlags(data,tfhd,0x03003b,false),at=tfhd.payload+8;long base=moof.start;
                if((flags&1)!=0){base=u64(data,at,tfhd.end);at+=8;}
                else if((flags&0x020000)==0&&trafIndex!=0)throw bad("Ambiguous implicit fragment base");
                if((flags&2)!=0){if(u32(data,at,tfhd.end)!=1)throw bad("Unknown sample description");at+=4;}
                if((flags&8)!=0){u32(data,at,tfhd.end);at+=4;}
                int defaultSize=config.defaultSize;
                if((flags&16)!=0){defaultSize=size(u32(data,at,tfhd.end),true);at+=4;}
                if((flags&32)!=0){u32(data,at,tfhd.end);at+=4;}
                if(at!=tfhd.end)throw bad("Malformed fragment header");
                long cursor=base;
                for(Box trun:children)if(trun.type.equals("trun")) {
                    int run=fullFlags(data,trun,0x000f05,true);long count=u32(data,trun.payload+4,trun.end);at=trun.payload+8;
                    if(count>MAX_SAMPLES-samples.size()||((flags&0x010000)!=0&&count!=0))throw bad("Excessive or empty fragment samples");
                    if((run&1)!=0){cursor=base+(int)u32(data,at,trun.end);at+=4;}
                    if((run&4)!=0){u32(data,at,trun.end);at+=4;}
                    for(int i=0;i<count;i++) {
                        if((run&0x100)!=0){u32(data,at,trun.end);at+=4;}
                        int length=defaultSize;if((run&0x200)!=0){length=size(u32(data,at,trun.end),false);at+=4;}
                        if((run&0x400)!=0){u32(data,at,trun.end);at+=4;}
                        if((run&0x800)!=0){u32(data,at,trun.end);at+=4;}
                        if(length==0||cursor<0||cursor>data.length-length||!inside(top,cursor,length))throw bad("Audio sample outside media payload");
                        samples.add(new Sample((int)cursor,length));cursor+=length;
                    }
                    if(at!=trun.end)throw bad("Malformed audio sample table");
                }
                trafIndex++;
            }
        }
        if(!found)throw bad("Missing movie fragment");
        var order=new ArrayList<>(samples);order.sort(Comparator.comparingInt(Sample::start));int end=-1;
        for(var sample:order){if(sample.start<end)throw bad("Overlapping audio samples");end=sample.start+sample.size;}
        var audio=new ByteArrayOutputStream();
        for(var sample:samples) {
            int n=sample.size+7;if(audio.size()>LIMIT-n)throw bad("Excessive decoded transport");
            audio.write(255);audio.write(241);audio.write(0x40|(config.rate<<2)|(config.channels>>2));
            audio.write((config.channels&3)<<6|(n>>11));audio.write(n>>3);audio.write((n&7)<<5|31);audio.write(252);
            audio.write(data,sample.start,sample.size);
        }
        return audio.toByteArray();
    }
    private static boolean inside(List<Box> boxes,long start,int n){for(var b:boxes)if(b.type.equals("mdat")&&start>=b.payload&&start+n<=b.end)return true;return false;}
    private static int fullFlags(byte[] data,Box box,int allowed,boolean version1)throws IOException {
        long full=u32(data,box.payload,box.end);if((full>>>24)>(version1?1:0)||(full&0xffffff&~allowed)!=0)throw bad("Unsupported fragment flags");return (int)full&0xffffff;
    }
    private static int size(long n,boolean zero)throws IOException{if(n<(zero?0:1)||n>8184)throw bad("Unsupported AAC sample size");return (int)n;}
    private static boolean has(List<Box> boxes,String type){return boxes.stream().anyMatch(b->b.type.equals(type));}
    private static Box one(List<Box> boxes,String type)throws IOException{Box found=null;for(var box:boxes)if(box.type.equals(type)){if(found!=null)throw bad("Duplicate "+type);found=box;}if(found==null)throw bad("Missing "+type);return found;}
    private static List<Box> boxes(byte[] data,int start,int end)throws IOException {
        if(start<0||end>data.length||start>end)throw bad("Invalid container bounds");var boxes=new ArrayList<Box>();
        for(int p=start;p<end;) {
            need(p,8,end);long size=u32(data,p,end);String type=word(data,p+4);int header=8;
            if(size==1){size=u64(data,p+8,end);header=16;}else if(size==0)size=end-p;
            if(size<header||size>end-p||boxes.size()>=4096)throw bad("Invalid box length/count");
            boxes.add(new Box(p,p+header,p+(int)size,type));p+=(int)size;
        }return boxes;
    }
    private static Descriptor descriptor(byte[] data,int start,int end,int wanted)throws IOException {
        for(int p=start,items=0;p<end&&items++<64;) {
            need(p,2,end);int tag=data[p++]&255,length=0,bytes=0,next;
            do{need(p,1,end);next=data[p++]&255;if(++bytes>4)throw bad("Invalid descriptor length");length=(length<<7)|(next&127);}while((next&128)!=0);
            need(p,length,end);if(tag==wanted)return new Descriptor(p,p+length,tag);p+=length;
        }throw bad("Missing audio descriptor");
    }
    private static String word(byte[] data,int p){return new String(data,p,4,StandardCharsets.ISO_8859_1);}
    private static int bits(byte[] data,int bit,int n,int end)throws IOException {
        need(bit,n,end);int value=0;for(int i=0;i<n;i++)value=(value<<1)|((data[(bit+i)/8]>>(7-(bit+i)%8))&1);return value;
    }
    private static int u16(byte[] data,int p,int end)throws IOException{need(p,2,end);return (data[p]&255)*256+(data[p+1]&255);}
    private static long u32(byte[] data,int p,int end)throws IOException{need(p,4,end);return ((long)(data[p]&255)<<24)|((long)(data[p+1]&255)<<16)|((data[p+2]&255)<<8)|(data[p+3]&255);}
    private static long u64(byte[] data,int p,int end)throws IOException{long high=u32(data,p,end),low=u32(data,p+4,end);if(high>0x7fffffffL)throw bad("Excessive box offset");return high<<32|low;}
    private static void need(int at,int n,int end)throws IOException{if(at<0||n<0||at>end-n)throw bad("Truncated audio container");}
    private static void bounded(byte[] data)throws IOException{if(data==null||data.length>LIMIT)throw bad("Excessive audio container");}
    private static IOException bad(String detail){return new IOException("fMP4 radio: "+detail);}
}
