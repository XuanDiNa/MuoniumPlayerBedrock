package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import com.muoniumplayer.core.ncm.music.TrackLoudnessNormalization;
import static org.junit.jupiter.api.Assertions.*;

class StreamingLoudness261Test {
    @Test @org.junit.jupiter.api.Timeout(12)
    void liveProgressiveDeckReceivesMeasurementBeforeTheHttpTail() throws Exception {
        byte[] wav=AllSourceStreamingTest.fixture("wav");
        var b=java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int data=-1;
        for(int i=0;i<wav.length-4;i++)if(wav[i]=='d'&&wav[i+1]=='a'&&wav[i+2]=='t'&&wav[i+3]=='a'){data=i+8;break;}
        assertTrue(data>0);
        for(int i=data;i+1<wav.length;i+=2)b.putShort(i,(short)Math.max(-32768,Math.min(32767,b.getShort(i)*6)));
        com.muoniumplayer.core.ncm.music.AudioPlayer deck=null;
        TrackLoudnessNormalization.setEnabled(true);
        try(var remote=new AllSourceStreamingTest.Remote(wav,550000)) {
            var token=ProgressiveAudio.prepare(remote.url());assertNotNull(token);
            deck=new com.muoniumplayer.core.ncm.music.AudioPlayer(token);
            long until=System.nanoTime()+3_000_000_000L;
            while(ProgressiveAudio.loudness(token)==null&&System.nanoTime()<until)Thread.sleep(10);
            assertNotNull(ProgressiveAudio.loudness(token));
            assertNull(deck.getSourceFile(),"Complete-file analysis is unavailable while HTTP tail is held");
            deck.bedrockProgressiveTick();
            var gain=deck.getClass().getDeclaredField("measuredTrackGain");gain.setAccessible(true);
            assertTrue(gain.getFloat(deck)<.6f,"A measurement of the decoded prefix must reach the original deck");
            assertEquals(1,remote.release.getCount(),"Normalization must not wait for or request the remaining track");
        } finally {
            if(deck!=null)deck.close();ProgressiveAudio.closePending();TrackLoudnessNormalization.setEnabled(false);
        }
    }
    @Test void optionalMeterCanMeasureDecodedPrefixWithoutDownloadingTheTail() throws Exception {
        Class<?> type=Class.forName("com.muoniumplayer.core.ncm.music.StreamingTrackLoudness");
        Object meter=type.getConstructor(int.class,int.class).newInstance(8000,1);
        var accept=type.getMethod("accept",float[].class);
        var measurement=type.getMethod("measurement");
        float[] pcm=new float[8000*6];
        for(int i=0;i<pcm.length;i++)pcm[i]=(float)(.8*Math.sin(i*2*Math.PI*440/8000));
        try {
            TrackLoudnessNormalization.setEnabled(false);accept.invoke(meter,(Object)pcm);
            assertNull(measurement.invoke(meter),"Disabled normalization must not analyze PCM");
            TrackLoudnessNormalization.setEnabled(true);accept.invoke(meter,(Object)pcm);
            float[] result=(float[])measurement.invoke(meter);
            assertNotNull(result);assertTrue(result[0]>-12&&result[0]<-2);assertEquals(.8f,result[1],.01);
            TrackLoudnessNormalization.setEnabled(false);accept.invoke(meter,(Object)pcm);
            assertNull(measurement.invoke(meter));
        } finally {TrackLoudnessNormalization.setEnabled(false);}
    }
}
