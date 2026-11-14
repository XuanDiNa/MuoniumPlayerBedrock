package com.muoniumplayer.bedrock;
import java.util.function.Supplier;
/** Reuses only an immutable native sequence, never geometry across publications. */
final class EnvironmentSnapshotCache {
    private EnvironmentFrame frame;private long sequence;
    void clear(){frame=null;sequence=0;}
    EnvironmentFrame read(long seq,long at,long epoch,int valid,long now,long expectedEpoch,Supplier<byte[]> copy){
        if(seq<=0||valid!=1||epoch!=expectedEpoch||at<=0||at>now||now-at>2000){clear();return null;}
        if(frame!=null&&seq==sequence&&frame.at==at&&frame.epoch==epoch)return frame;
        frame=EnvironmentFrame.decode(copy.get(),now,expectedEpoch);sequence=seq;return frame;
    }
}
