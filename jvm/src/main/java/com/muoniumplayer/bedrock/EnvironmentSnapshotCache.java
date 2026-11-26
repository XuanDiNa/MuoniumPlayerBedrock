package com.muoniumplayer.bedrock;
import java.util.function.Supplier;
/** Reuses only an immutable native sequence, never geometry across publications. */
final class EnvironmentSnapshotCache {
    private EnvironmentFrame frame;private long sequence,lastValidated;
    void clear(){frame=null;sequence=lastValidated=0;}
    EnvironmentFrame contended(long now,long expectedEpoch){
        if(frame==null||frame.epoch!=expectedEpoch||now<lastValidated||now-lastValidated>100
            ||frame.at>now||now-frame.at>2000){clear();return null;}
        return frame; // Never renew a lease without a successful synchronized read.
    }
    EnvironmentFrame read(long seq,long at,long epoch,int valid,long now,long expectedEpoch,Supplier<byte[]> copy){
        if(seq<=0||valid!=1||epoch!=expectedEpoch||at<=0||at>now||now-at>2000){clear();return null;}
        lastValidated=now;
        if(frame!=null&&seq==sequence&&frame.at==at&&frame.epoch==epoch)return frame;
        frame=EnvironmentFrame.decode(copy.get(),now,expectedEpoch);sequence=seq;return frame;
    }
}
