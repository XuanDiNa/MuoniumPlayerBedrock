package com.muoniumplayer.core.ncm.music;

/** Reuse upstream equal-power planning until whole-track analysis is available. */
public final class StreamingMixPlanner {
    private StreamingMixPlanner(){}
    static AutomixPlan plan(AudioPlayer outgoing,AudioPlayer incoming,long lastVocalEnd){
        if(outgoing==null||incoming==null)return null;
        if(outgoing.getSourceFile()==null||incoming.getSourceFile()==null)
            return AutomixPlanner.planClassic(outgoing,incoming,lastVocalEnd);
        return AutomixPlanner.plan(outgoing,incoming,lastVocalEnd);
    }
}
