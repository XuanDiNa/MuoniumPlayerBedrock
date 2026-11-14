package com.muoniumplayer.bedrock;

import repackage.com.jsyn.data.FloatSample;
import repackage.javazoom.jl.decoder.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Incremental MP3/FLAC decode into owned, session-only PCM work files.
 * Original SoundFile/DSP consumes the sample; the audio thread never waits on HTTP. */
public final class ProgressiveAudio {
    static final Set<AutoCloseable> transports=ConcurrentHashMap.newKeySet();
    private static final Map<String, Sample> pending=new ConcurrentHashMap<>();
    private ProgressiveAudio() {}
    public static File prepare(String url) {
        return prepare(url, () -> Thread.currentThread().isInterrupted());
    }
    public static File prepare(String url, BooleanSupplier cancelled) {
        return prepare(url,cancelled,null,false);
    }
    public static File prepare(String url,BooleanSupplier cancelled,String retentionKey,boolean downloadAll) {
        InputStream stream=null;Sample sample=null;AudioRanges remote=null;
        try {
            if(url==null||!url.matches("(?i)^https?://.*"))return null;
            remote=new AudioRanges(url,cancelled);stream=remote.initial;
            int length=(int)remote.length;
            var sniff=new BufferedInputStream(stream,32768);stream=sniff;sniff.mark(64);
            byte[] prefix=sniff.readNBytes(42);sniff.reset();
            byte[] head=Arrays.copyOf(prefix,Math.min(4,prefix.length));
            FrameSource source;
            if(Arrays.equals(head,new byte[]{'f','L','a','C'}))source=new FlacSource(sniff);
            else if(head.length==4&&((head[0]=='I'&&head[1]=='D'&&head[2]=='3')
                ||(head[0]==(byte)255&&(head[1]&224)==224)))source=new Mp3Source(sniff,length);
            else {stream.close();remote.close();return null;}
            if(source.frames()<=source.rate()||(long)source.frames()*source.channels()>Integer.MAX_VALUE/8L){stream.close();remote.close();return null;}
            byte[] flacHeader=null;
            if(source instanceof FlacSource){
                if(prefix.length!=42||(prefix[4]&127)!=0||prefix[5]!=0||prefix[6]!=0||prefix[7]!=34)throw new IOException("Invalid FLAC metadata");
                flacHeader=prefix.clone();flacHeader[4]=(byte)128;
            }
            sample=new Sample(source,remote,flacHeader,retentionKey,downloadAll);
            stream=null;sample.start();sample.awaitReady(cancelled);remote.prepared();
            pending.put(sample.token.getAbsolutePath(),sample);
            System.out.println("PROGRESSIVE_AUDIO ready codec="+source.codec()+" storage=session-only prebufferMs=1000");
            return sample.token;
        } catch(Exception e) {
            if(sample!=null)sample.cleanUp();
            if(stream!=null)try{stream.close();}catch(IOException ignored){}
            if(remote!=null)remote.close();
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Progressive audio cancelled");
            // Safe fallback happens before SoundFile owns any sample. No signed URL.
            System.out.println("PROGRESSIVE_AUDIO fallback reason="+e.getClass().getSimpleName());
            return null;
        }
    }
    public static FloatSample claim(File file) {
        Sample sample=file==null?null:pending.get(file.getAbsolutePath());
        return sample!=null&&sample.claimed.compareAndSet(false,true)?sample:null;
    }
    public static boolean isProgressive(File file) {return file!=null&&pending.containsKey(file.getAbsolutePath());}
    public static File analysisFile(File file) {
        Sample s=file==null?null:pending.get(file.getAbsolutePath());
        return s==null?file:s.eof&&s.failure==null&&!s.closed?s.pcm.toFile():null;
    }
    /** Reversible completion of the existing decoder, never a second transfer. */
    public static boolean completeForAnalysis(File file,boolean enabled,long maxBytes) {
        Sample s=file==null?null:pending.get(file.getAbsolutePath());
        if(s==null||s.closed)return false;
        boolean desired=enabled && 44L+(long)s.getNumFrames()*s.channels*4<=maxBytes;
        if(s.analysisAll==desired)return desired;
        s.analysisAll=desired;
        synchronized(s.changed){s.changed.notifyAll();}
        return s.analysisAll;
    }
    public static void discard(File file) {if(file!=null){Sample s=pending.remove(file.getAbsolutePath());if(s!=null)s.cleanUp();}}
    public static void closePending() {for(AutoCloseable transport:transports)try{transport.close();}catch(Exception ignored){}for(Sample s:pending.values())s.cleanUp();pending.clear();}
    // 0 ready, 1 buffering, 2 ended, 3 failed. Called by the original playback
    // supervisor, not the shared JSyn synthesis callback.
    public static int state(File file,float millis) {
        Sample s=file==null?null:pending.get(file.getAbsolutePath());
        if(s==null)return 0;
        if(s.closed||s.failure!=null)return 3;
        long index=Math.max(0,(long)(millis*s.rate/1000)*s.channels);
        long end=(long)s.getNumFrames()*s.channels;
        s.requested=Math.min(index,end-1);
        if(index>=end)return s.available(end-1)>0?2:1;
        long ahead=s.available(index);
        long threshold=(long)s.rate*s.channels*(s.buffering?1000:350)/1000;
        threshold=Math.min(threshold,(long)s.getNumFrames()*s.channels-index);
        s.buffering=ahead<threshold;
        return s.buffering?1:0;
    }
    public static float durationMillis(File file) {
        Sample s=file==null?null:pending.get(file.getAbsolutePath());
        return s==null?0:s.getNumFrames()*1000f/s.rate;
    }
    /** Bounded diagnostics: no source URL, path, credentials or remote message. */
    public static String failureKind(File file) {
        Sample s=file==null?null:pending.get(file.getAbsolutePath());
        return s==null?"unavailable":s.closed?"closed":s.failure==null?"unknown":s.failure.getClass().getSimpleName();
    }

    interface FrameSource extends AutoCloseable {
        int frames();int channels();int rate();String codec();float[] next()throws Exception;
        void close()throws IOException;
    }
    static final class Mp3Source implements FrameSource {
        final Bitstream bits;final Decoder decoder=new Decoder();Header header;
        final int frames,channels,rate,samplesPerFrame;float[] first;
        Mp3Source(InputStream input,int length)throws Exception {
            bits=new Bitstream(input);header=bits.readFrame();
            if(header==null||header.layer()!=3||length<=0)throw new IOException("MP3 length unavailable");
            var b=(SampleBuffer)decoder.decodeFrame(header,bits);
            channels=b.getChannelCount();rate=b.getSampleFrequency();
            samplesPerFrame=header.version()==Header.MPEG1?1152:576;
            frames=Math.multiplyExact(header.maxNumberOfFrames(length),samplesPerFrame);
            first=convert(b);bits.closeFrame();
        }
        float[] convert(SampleBuffer b){
            // A range starts without the preceding Layer III bit reservoir.
            // JLayer emits zero samples until it is primed; that encoded frame
            // still occupies its exact place on the timeline during preroll.
            float[] f=new float[samplesPerFrame*channels];
            if(b.getBufferLength()!=0&&b.getBufferLength()!=f.length)throw new IllegalStateException("MP3 frame layout changed");
            for(int i=0;i<b.getBufferLength();i++)f[i]=b.getBuffer()[i]/32768f;return f;
        }
        public float[] next()throws Exception {if(first!=null){float[] f=first;first=null;return f;}
            header=bits.readFrame();if(header==null)return null;var b=(SampleBuffer)decoder.decodeFrame(header,bits);bits.closeFrame();
            if(b.getChannelCount()!=channels||b.getSampleFrequency()!=rate)throw new IOException("MP3 layout changed");return convert(b);}
        public int frames(){return frames;}public int channels(){return channels;}public int rate(){return rate;}public String codec(){return "mp3";}
        public void close()throws IOException {try{bits.close();}catch(BitstreamException e){throw new IOException(e);}}
    }
    static final class FlacSource implements FrameSource {
        final InputStream input;final repackage.org.kc7bfi.jflac.FLACDecoder decoder;
        final repackage.org.kc7bfi.jflac.metadata.StreamInfo info;long position;boolean corrupt;
        FlacSource(InputStream in)throws IOException {input=in;decoder=new repackage.org.kc7bfi.jflac.FLACDecoder(in);
            decoder.addFrameListener(new repackage.org.kc7bfi.jflac.FrameListener(){
                public void processMetadata(repackage.org.kc7bfi.jflac.metadata.Metadata m){}
                public void processFrame(repackage.org.kc7bfi.jflac.frame.Frame f){}
                public void processError(String message){if(message.startsWith("CRC Error:"))corrupt=true;}
            });
            info=decoder.readStreamInfo();decoder.readMetadata(info);
            if(info.getChannels()<1||info.getChannels()>2||info.getBitsPerSample()<8||info.getBitsPerSample()>24||info.getTotalSamples()>Integer.MAX_VALUE)
                throw new IOException("Unsupported FLAC layout");}
        public float[] next()throws IOException {var frame=decoder.readNextFrame();if(frame==null)return null;
            if(corrupt||frame.header.sampleNumber<0||frame.header.sampleNumber+frame.header.blockSize>info.getTotalSamples()
                ||frame.header.channels!=channels()||frame.header.sampleRate!=rate())throw new IOException("Invalid FLAC frame");
            position=frame.header.sampleNumber;int n=frame.header.blockSize;var data=decoder.getChannelData();float[] out=new float[n*channels()];
            double scale=Math.scalb(1.0,1-info.getBitsPerSample());
            for(int i=0;i<n;i++)for(int c=0;c<channels();c++)out[i*channels()+c]=(float)(data[c].getOutput()[i]*scale);return out;}
        public int frames(){return (int)info.getTotalSamples();}public int channels(){return info.getChannels();}public int rate(){return info.getSampleRate();}public String codec(){return "flac";}
        public void close()throws IOException {input.close();}
    }

    private static final class Sample extends FloatSample {
        final File token;final Path pcm;final FrameSource source;final int rate,channels;
        final String retentionKey;final boolean downloadAll;
        final AudioRanges remote;final AudioSeek seek;final FileChannel output,input;
        volatile boolean closed,eof,buffering,rangeUnsupported,analysisAll;volatile Throwable failure;
        final java.util.concurrent.atomic.AtomicBoolean claimed=new java.util.concurrent.atomic.AtomicBoolean();
        volatile long written,requested;volatile long[][] spans=new long[0][];
        final Object changed=new Object();Thread worker,seeker;volatile FrameSource seekingSource;
        long cacheOffset=-1;final ByteBuffer cache=ByteBuffer.allocate(16384).order(ByteOrder.LITTLE_ENDIAN);
        Sample(FrameSource source,AudioRanges remote,byte[] header,String retentionKey,boolean downloadAll)throws IOException {
            this.retentionKey=retentionKey;this.downloadAll=downloadAll;
            this.source=source;this.remote=remote;this.seek=new AudioSeek(remote,header,source);channels=source.channels();rate=source.rate();
            numFrames=source.frames();maxFrames=numFrames;channelsPerFrame=channels;setFrameRate(rate);
            Path base=SessionMedia.directory().toPath();token=Files.createTempFile(base,"progressive-","."+source.codec()).toFile();
            Files.write(token.toPath(),new byte[]{(byte)255,(byte)251,(byte)144,0});pcm=Path.of(token.getAbsolutePath()+".pcm.wav");
            FileChannel writer=null;
            try{writer=FileChannel.open(pcm,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);input=FileChannel.open(pcm,StandardOpenOption.READ);output=writer;}
            catch(IOException e){if(writer!=null)writer.close();Files.deleteIfExists(pcm);Files.deleteIfExists(token.toPath());throw e;}
            cache.limit(0);
        }
        void start(){worker=new Thread(this::decode,"Muonium-progressive-audio");worker.setDaemon(true);worker.start();
            seeker=new Thread(this::seekLoop,"Muonium-stream-seek");seeker.setDaemon(true);seeker.start();}
        void awaitReady(BooleanSupplier cancelled)throws Exception {
            long deadline=System.nanoTime()+20_000_000_000L;
            synchronized(changed){while(!closed&&failure==null&&!eof&&written<(long)rate*channels){
                if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedException();
                if(System.nanoTime()>deadline)throw new IOException("Initial audio buffer timed out");changed.wait(50);
            }}
            if(failure!=null||written==0)throw new IOException("Audio did not decode",failure);
        }
        long available(long index){for(long[] span:spans)if(index>=span[0]&&index<span[1])return span[1]-index;return 0;}
        synchronized void store(long start,float[] data)throws IOException {
            if(closed)return;long end=start+data.length;
            if(start<0||end>(long)numFrames*channels+rate*channels)throw new IOException("Audio length exceeded encoded bound");
            ByteBuffer block=ByteBuffer.allocate(data.length*4).order(ByteOrder.LITTLE_ENDIAN);for(float f:data)block.putFloat(f);block.flip();
            long offset=44+start*4;while(block.hasRemaining())offset+=output.write(block,offset);
            var list=new ArrayList<long[]>();boolean inserted=false;
            for(long[] span:spans){if(span[1]<start)list.add(span);else if(end<span[0]){if(!inserted){list.add(new long[]{start,end});inserted=true;}list.add(span);}else{start=Math.min(start,span[0]);end=Math.max(end,span[1]);}}
            if(!inserted)list.add(new long[]{start,end});spans=list.toArray(long[][]::new);
        }
        void decode(){try{float[] data;
            // STREAMINFO is exact. Once every FLAC sample exists, an extra
            // frame/HTTP read can only wait for irrelevant trailing bytes.
            // MP3 length is estimated and must still be resolved by real EOF.
            while(!closed&&(!(source instanceof FlacSource)||written<(long)source.frames()*channels)
                    &&(data=source.next())!=null){
                if(source instanceof FlacSource flac && flac.position!=written/channels)throw new IOException("Discontinuous FLAC frames");
                store(written,data);written+=data.length;
                synchronized(changed){changed.notifyAll();while(!closed&&!analysisAll&&((!downloadAll&&written-requested>(long)rate*channels*12)
                    ||(!rangeUnsupported&&requested-written>(long)rate*channels*2)))changed.wait(100);}}
            if(!closed&&(source instanceof FlacSource ? written!=(long)numFrames*channels
                    : written+2L*rate*channels<(long)numFrames*channels))
                throw new EOFException("Audio ended before its encoded length");
            // MPEG headers only estimate a CBR/VBR duration; final sequential
            // decode provides the exact frame count, never a sparse seek.
            if(!closed){numFrames=(int)(written/channels);
                ByteBuffer h=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
                h.put(new byte[]{'R','I','F','F'}).putInt((int)(36+written*4)).put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(16)
                    .putShort((short)3).putShort((short)channels).putInt(rate).putInt(rate*channels*4).putShort((short)(channels*4)).putShort((short)32)
                    .put(new byte[]{'d','a','t','a'}).putInt((int)(written*4)).flip();
                long offset=0;while(h.hasRemaining())offset+=output.write(h,offset);
                if(retentionKey!=null){output.force(false);PlaybackFiles.global().retain(retentionKey,pcm.toFile());}
            }
        }catch(Throwable e){if(!closed)failure=e;}
        finally{eof=true;try{source.close();}catch(Exception ignored){}synchronized(changed){changed.notifyAll();}}}
        void seekLoop(){while(!closed){try{
            long target=requested/channels;
            if(rangeUnsupported||requested>=(long)numFrames*channels||available(requested)>=Math.min((long)rate*channels,(long)numFrames*channels-requested)||target<written/channels+rate*2L){Thread.sleep(40);continue;}
            var located=seek.open(target);seekingSource=located.source();
            try{float[] data=located.first();long at=located.frame();
                while(!closed&&data!=null&&at<target+rate*4L){
                    if(Math.abs(requested/channels-target)>rate*2L)break;
                    if(at+data.length/channels>target)store(at*channels,data);
                    at+=data.length/channels;data=seekingSource.next();
                }
            }finally{seekingSource.close();seekingSource=null;}
        }catch(AudioRanges.Unsupported unsupported){rangeUnsupported=true;System.out.println("PROGRESSIVE_AUDIO seek=sequential range=unsupported");}
        catch(InterruptedException e){break;}
        catch(Throwable e){if(!closed){failure=e;System.out.println("PROGRESSIVE_AUDIO seek=rejected reason="+e.getClass().getSimpleName());}break;}}}
        @Override public double readDouble(int index){
            if(closed||index<0||index>=(long)numFrames*channels)return 0;
            try{long ready=available(index);if(ready==0){requested=index;return 0;}
                long position=44+(long)index*4;
                if(cacheOffset<0||position<cacheOffset||position+4>cacheOffset+cache.limit()){
                    cache.clear();cache.limit((int)Math.min(cache.capacity(),ready*4));int read=input.read(cache,position);cache.flip();cacheOffset=position;if(read<4)return 0;}
                return cache.getFloat((int)(position-cacheOffset));
            }catch(Exception e){if(!closed&&failure==null)failure=e;return 0;}
        }
        @Override public void read(int startFrame,float[] data,int startIndex,int frames){for(int i=0;i<frames*channels;i++)data[startIndex+i]=(float)readDouble(startFrame*channels+i);}
        @Override public void allocate(int f,int c){}
        @Override public boolean isIndexOverflow(int index){return index<0||index>=(long)numFrames*channels;}
        @Override public synchronized void cleanUp(){if(closed)return;closed=true;pending.remove(token.getAbsolutePath(),this);
            synchronized(changed){changed.notifyAll();}remote.close();try{source.close();}catch(Exception ignored){}
            if(worker!=null)worker.interrupt();if(seeker!=null)seeker.interrupt();
            try{output.close();}catch(Exception ignored){}try{input.close();}catch(Exception ignored){}
            try{Files.deleteIfExists(pcm);}catch(IOException ignored){}token.delete();
        }
    }
}
