package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class BroadcastPcmQueueTest {
    @Test void stereoFoldDownAndFiniteSamples() {
        var q = new BroadcastPcmQueue(4096);
        q.offer(new double[]{.2,.6,Double.NaN,.4,2,2},2,48000,1);
        float[] dst=new float[3];
        assertEquals(3,q.poll(dst));
        assertArrayEquals(new float[]{.4f,.2f,1f},dst,1e-6f);
    }
    @Test void rejectsWrongFormatAndPartialStereo() {
        var q=new BroadcastPcmQueue(2048);
        q.offer(new double[]{1,1},1,44100,1);
        q.offer(new double[]{1,1,1},2,48000,1);
        assertEquals(0,q.poll(new float[960]));
    }
    @Test void overflowDropsWholeBufferWithoutOverwritingUnreadData() {
        var q=new BroadcastPcmQueue(2048);
        double[] pcm=new double[1800];Arrays.fill(pcm,.25);
        q.offer(pcm,1,48000,1);q.offer(pcm,1,48000,1);
        assertEquals(1800,q.poll(new float[2048]));
    }
    @Test void noPartialFrameIsConsumed() {
        var q=new BroadcastPcmQueue(2048);
        q.offer(new double[500],1,48000,1);
        assertEquals(0,q.pollFrame(new float[960]));
        q.offer(new double[460],1,48000,1);
        assertEquals(960,q.pollFrame(new float[960]));
    }
    @Test void clearPreventsOldSessionReplay() {
        var q=new BroadcastPcmQueue(2048);
        q.offer(new double[960],1,48000,1);q.clear();
        assertEquals(0,q.pollFrame(new float[960]));
    }
    @Test void producerAndConsumerNeverExposePartiallyWrittenFrames()throws Exception {
        var q=new BroadcastPcmQueue(4096);var done=new java.util.concurrent.atomic.AtomicBoolean();
        var producer=new Thread(()->{
            double[] block=new double[960];
            for(int i=1;i<=5000;i++){Arrays.fill(block,i/8192.0);q.offer(block,1,48000,1);if(i%8==0)Thread.yield();}
            done.set(true);
        });
        producer.start();float[] out=new float[960];float previous=0;int count=0;
        while(!done.get()||producer.isAlive()){
            if(q.pollFrame(out)==0){Thread.yield();continue;}
            assertTrue(out[0]>previous);previous=out[0];for(float x:out)assertEquals(previous,x);count++;
        }
        producer.join();assertTrue(count>0);
    }
}
