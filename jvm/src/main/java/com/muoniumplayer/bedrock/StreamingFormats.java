package com.muoniumplayer.bedrock;

import java.io.*;
import java.nio.*;
import repackage.com.jcraft.jogg.*;
import repackage.com.jcraft.jorbis.*;

/** Incremental decoders for the clear audio containers supported by the player. */
final class StreamingFormats {
    static int durationFrames(long millis,int rate)throws IOException {
        long n=(millis*rate+999)/1000;
        if(millis<=0||millis>24*3600_000L||n<=0||n>Integer.MAX_VALUE)throw new IOException("Audio duration unavailable or excessive");
        return (int)n;
    }
    static float[] pcm(byte[] bytes,int channels,int bits,boolean big)throws IOException {
        if(bits!=16||channels<1||channels>2||bytes.length%(channels*2)!=0)throw new IOException("Unsupported decoded PCM");
        float[] out=new float[bytes.length/2];var b=ByteBuffer.wrap(bytes).order(big?ByteOrder.BIG_ENDIAN:ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<out.length;i++)out[i]=b.getShort()/32768f;return out;
    }
    static final class Adts implements ProgressiveAudio.FrameSource {
        final InputStream input;byte[] config;
        final net.sourceforge.jaad.aac.Decoder decoder;
        int frames,rate,channels;float[] first;
        Adts(InputStream input,long duration)throws Exception {
            this.input=input;byte[] encoded=encoded();if(encoded==null)throw new IOException("Empty AAC audio");
            decoder=net.sourceforge.jaad.aac.Decoder.create(config);
            first=decode(encoded);for(int i=0;i<8&&first!=null&&first.length==0;i++)first=decode();
            if(first==null||first.length==0)throw new IOException("Empty AAC audio");frames=durationFrames(duration,rate);
        }
        byte[] encoded()throws IOException {
            byte[] h=input.readNBytes(7);if(h.length==0)return null;
            if(h.length!=7||(h[0]&255)!=255||(h[1]&246)!=240||(h[6]&3)!=0)throw new IOException("Invalid or truncated ADTS header");
            int profile=((h[2]&255)>>6)+1,freq=(h[2]>>2)&15,chan=((h[2]&1)<<2)|((h[3]&255)>>6),length=((h[3]&3)<<11)|((h[4]&255)<<3)|((h[5]&255)>>5),header=(h[1]&1)==0?9:7;
            byte[] asc={(byte)((profile<<3)|(freq>>1)),(byte)(((freq&1)<<7)|(chan<<3))};
            if(freq>=13||chan<1||chan>2||length<header||length>8191||config!=null&&!java.util.Arrays.equals(config,asc))throw new IOException("Unsupported or changing ADTS format");
            config=asc;if(header==9)read(input,2);return read(input,length-header);
        }
        float[] decode()throws Exception {byte[] bytes=encoded();return bytes==null?null:decode(bytes);}
        float[] decode(byte[] encoded)throws Exception {
            var b=new net.sourceforge.jaad.SampleBuffer();decoder.decodeFrame(encoded,b);
            if(b.getData().length==0)return new float[0];
            if(rate!=0&&(rate!=b.getSampleRate()||channels!=b.getChannels()))throw new IOException("AAC format changed");
            rate=b.getSampleRate();channels=b.getChannels();return pcm(b.getData(),channels,b.getBitsPerSample(),b.isBigEndian());
        }
        public float[] next()throws Exception {if(first!=null){var f=first;first=null;return f;}return decode();}
        public int frames(){return frames;}public int rate(){return rate;}public int channels(){return channels;}public String codec(){return "aac";}
        public void close()throws IOException{input.close();}
    }
    static final class Wav implements ProgressiveAudio.FrameSource {
        final InputStream input;final int rate,channels,frames,bits,encoding,align;long left,dataOffset;
        Wav(InputStream input)throws IOException {
            this.input=input;var header=read(input,12);if(!word(header,0).equals("RIFF")||!word(header,8).equals("WAVE"))throw new IOException("Invalid WAV");
            int r=0,c=0,b=0,e=0,a=0;long offset=12;
            for(int count=0;count<256;count++) {
                byte[] h=read(input,8);long size=Integer.toUnsignedLong(ByteBuffer.wrap(h,4,4).order(ByteOrder.LITTLE_ENDIAN).getInt());offset+=8;
                if(word(h,0).equals("data")) {
                    if(r<8000||r>192000||c<1||c>2||!(e==1&&(b==8||b==16||b==24||b==32)||e==3&&b==32)||a!=c*b/8||size%a!=0||size/a>Integer.MAX_VALUE)throw new IOException("Unsupported WAV format");
                    left=size;dataOffset=offset;frames=(int)(size/a);rate=r;channels=c;bits=b;encoding=e;align=a;return;
                }
                if(size>1024*1024)throw new IOException("Excessive WAV metadata");
                if(word(h,0).equals("fmt ")) {
                    byte[] f=read(input,(int)size);if(f.length<16)throw new IOException("Invalid WAV format");
                    var v=ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN);e=v.getShort()&65535;c=v.getShort()&65535;r=v.getInt();v.getInt();a=v.getShort()&65535;b=v.getShort()&65535;
                    if(e==65534){if(f.length<40||v.getShort(16)<22||v.getShort(18)!=b)throw new IOException("Invalid extensible WAV");
                        byte[] guid=java.util.Arrays.copyOfRange(f,24,40);byte[] suffix={0,0,16,0,(byte)128,0,0,(byte)170,0,56,(byte)155,113};
                        if(!java.util.Arrays.equals(java.util.Arrays.copyOfRange(guid,4,16),suffix))throw new IOException("Unsupported WAV subformat");e=v.getInt(24);}
                }else input.skipNBytes(size);
                offset+=size;if((size&1)!=0){read(input,1);offset++;}
            }throw new IOException("Missing WAV audio");
        }
        public float[] next()throws IOException {
            if(left==0)return null;int count=(int)Math.min(left,4096L*align);byte[] bytes=read(input,count);left-=count;
            var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);float[] out=new float[count/(bits/8)];
            for(int i=0;i<out.length;i++) {
                if(encoding==3){float f=b.getFloat();if(!Float.isFinite(f))throw new IOException("Invalid WAV float");out[i]=Math.max(-1,Math.min(1,f));}
                else out[i]=switch(bits){case 8 -> ((b.get()&255)-128)/128f;case 16 -> b.getShort()/32768f;case 24 -> ((b.get()&255)|((b.get()&255)<<8)|(b.get()<<16))/8388608f;default -> (float)(b.getInt()/2147483648.0);};
            }return out;
        }
        public int frames(){return frames;}public int rate(){return rate;}public int channels(){return channels;}public String codec(){return "wav";}
        private Wav(Wav original,InputStream input,int frame){this.input=input;rate=original.rate;channels=original.channels;frames=original.frames-frame;bits=original.bits;encoding=original.encoding;align=original.align;left=(long)frames*align;}
        AudioSeek.Located locate(AudioRanges remote,long target)throws IOException {
            int frame=(int)Math.min(frames-1,Math.max(0,target));return new AudioSeek.Located(new Wav(this,remote.from(dataOffset+(long)frame*align),frame),frame,null);
        }
        public boolean exact(){return true;}public void close()throws IOException{input.close();}
    }
    static final class Vorbis implements ProgressiveAudio.FrameSource {
        final InputStream input;final SyncState sync=new SyncState();final StreamState stream=new StreamState();
        final Info info=new Info();final Comment comment=new Comment();final DspState dsp=new DspState();final Block block=new Block(dsp);
        final float[][][] holder=new float[1][][];final int[] indexes=new int[2];
        int serial,frames;boolean initialized,ended,closed;long granule=-1,emitted;
        Vorbis(InputStream input,long duration)throws Exception {
            this.input=input;sync.init();info.init();comment.init();
            for(int i=0;i<3;i++)if(info.synthesis_headerin(comment,packet())!=0)throw new IOException("Unsupported OGG/Vorbis header");
            if(info.rate<8000||info.rate>192000||info.channels<1||info.channels>2)throw new IOException("Unsupported OGG layout");
            frames=durationFrames(duration,info.rate);if(dsp.synthesis_init(info)!=0)throw new IOException("Vorbis synthesis rejected");block.init(dsp);
        }
        Packet packet()throws IOException {
            while(true) {
                var packet=new Packet();int p=stream.packetout(packet);if(p==1)return packet;if(p<0)throw new IOException("OGG packet discontinuity");
                if(ended)return null;
                var page=new Page();int result=sync.pageout(page);if(result<0)throw new IOException("OGG checksum or page discontinuity");
                if(result==0){int at=sync.buffer(8192);int n=input.read(sync.data,at,8192);if(n<0)throw new EOFException("OGG missing end page");sync.wrote(n);continue;}
                if(!initialized){serial=page.serialno();stream.init(serial);initialized=true;}
                if(serial!=page.serialno()||stream.pagein(page)!=0)throw new IOException("Unsupported chained OGG");
                if(page.eos()!=0){ended=true;granule=page.granulepos();}
            }
        }
        public float[] next()throws Exception {
            while(true) {
                int n=dsp.synthesis_pcmout(holder,indexes);
                if(n>0){int count=n;if(granule>=0)count=(int)Math.max(0,Math.min(count,granule-emitted));
                    float[] out=new float[count*info.channels];for(int i=0;i<count;i++)for(int c=0;c<info.channels;c++)out[i*info.channels+c]=holder[0][c][indexes[c]+i];
                    dsp.synthesis_read(n);emitted+=count;if(count>0)return out;}
                var packet=packet();if(packet==null)return null;
                if(block.synthesis(packet)!=0||dsp.synthesis_blockin(block)!=0)throw new IOException("Invalid Vorbis audio packet");
            }
        }
        public int frames(){return frames;}public int rate(){return info.rate;}public int channels(){return info.channels;}public String codec(){return "ogg";}
        public void close()throws IOException{if(closed)return;closed=true;input.close();}
    }
    static byte[] read(InputStream in,int n)throws IOException {byte[] b=in.readNBytes(n);if(b.length!=n)throw new EOFException("Truncated audio container");return b;}
    static String word(byte[] b,int at){return new String(b,at,4,java.nio.charset.StandardCharsets.ISO_8859_1);}
}
