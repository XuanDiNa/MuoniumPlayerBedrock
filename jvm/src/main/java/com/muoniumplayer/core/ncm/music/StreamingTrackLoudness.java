package com.muoniumplayer.core.ncm.music;

/** Optional running estimate over sequentially decoded PCM. No I/O or extra transfer.
 * The upstream integrated meter runs on the decoder worker, never the audio callback.
 * A prefix estimate can change as more of a track arrives; it is never persisted. */
public final class StreamingTrackLoudness {
    private final int rate,channels;
    private IntegratedLoudnessMeter meter;
    private long frames,nextUpdate;
    private volatile float[] measurement;
    public StreamingTrackLoudness(int rate,int channels) {this.rate=rate;this.channels=channels;}
    public void accept(float[] pcm) {
        if(!TrackLoudnessNormalization.isEnabled()) {meter=null;measurement=null;frames=0;nextUpdate=0;return;}
        if(meter==null) {meter=new IntegratedLoudnessMeter(rate);nextUpdate=rate*5L;}
        for(int i=0;i+channels<=pcm.length;i+=channels)meter.acceptFrame(pcm[i],channels==2?pcm[i+1]:0,channels);
        frames+=pcm.length/channels;
        if(frames<nextUpdate)return;
        nextUpdate=frames+rate*5L;
        var result=meter.finish();
        if(result!=null)measurement=new float[]{result.integratedLufs,result.samplePeak};
    }
    public float[] measurement() {return TrackLoudnessNormalization.isEnabled()?measurement:null;}
    public static void apply(AudioPlayer player,java.io.File token,float[] result) {
        if(result!=null&&result.length==2&&TrackLoudnessNormalization.isEnabled())
            player.applyLoudnessMeasurement(token,new TrackLoudnessNormalization.Measurement(result[0],result[1]));
    }
}
