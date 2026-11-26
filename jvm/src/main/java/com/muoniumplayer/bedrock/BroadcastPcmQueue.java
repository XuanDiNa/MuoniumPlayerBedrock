package com.muoniumplayer.bedrock;

import java.util.concurrent.atomic.AtomicLong;

/** One audio producer, one IPC consumer. Fixed memory; whole-buffer overflow drops. */
final class BroadcastPcmQueue {
    private final float[] ring;
    private final int mask;
    private final AtomicLong written=new AtomicLong(),read=new AtomicLong();
    BroadcastPcmQueue(int capacity) {
        if(capacity<1024 || Integer.bitCount(capacity)!=1)throw new IllegalArgumentException("PCM capacity");
        ring=new float[capacity];mask=capacity-1;
    }
    void offer(double[] data,int channels,int rate,float gain) {
        if(data==null || channels<1 || channels>8 || rate!=48000 || data.length%channels!=0
                || !Float.isFinite(gain) || gain<0 || gain>1)return;
        int frames=data.length/channels;long w=written.get();
        if(frames>ring.length-(w-read.get()))return;
        for(int i=0;i<frames;i++) {
            double sum=0;
            for(int c=0;c<channels;c++){double x=data[i*channels+c];if(Double.isFinite(x))sum+=x/channels;}
            ring[(int)(w+i)&mask]=(float)Math.max(-1,Math.min(1,sum*gain));
        }
        written.lazySet(w+frames);
    }
    int poll(float[] out){return poll(out,false);}
    int pollFrame(float[] out){return poll(out,true);}
    private int poll(float[] out,boolean whole) {
        long r=read.get(),available=written.get()-r;
        if(whole && available<out.length)return 0;
        int count=(int)Math.min(out.length,available);
        for(int i=0;i<count;i++)out[i]=ring[(int)(r+i)&mask];
        read.lazySet(r+count);return count;
    }
    /** Consumer-only. Already-running producer may publish once; session gating
     * drops that buffer before a newly-ready session starts accepting samples. */
    void clear(){read.set(written.get());}
}
