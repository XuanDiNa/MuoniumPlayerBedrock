package com.muoniumplayer.bedrock;
import com.muoniumplayer.core.cine.CinePose;
import java.nio.*;
final class CinematicCommand {
    static byte[] encode(CinematicState s,CinePose p,long sequence,long now,boolean hideHud,boolean allowed){
        var b=ByteBuffer.allocate(88).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0,0x4350434d).putInt(4,1).putLong(8,sequence).putLong(16,now);
        if(!allowed||s==null||p==null||now<s.at()||now-s.at()>250)return b.array();
        for(double n:new double[]{p.x(),p.y(),p.z(),p.yawDeg(),p.pitchDeg(),p.rollDeg(),p.fovOffsetDeg()})
            if(!Double.isFinite(n))return b.array();
        if(Math.pow(p.x()-s.x(),2)+Math.pow(p.y()-s.y(),2)+Math.pow(p.z()-s.z(),2)>256
            ||Math.abs(p.yawDeg())>36000||Math.abs(p.pitchDeg())>90||Math.abs(p.rollDeg())>180||Math.abs(p.fovOffsetDeg())>20)return b.array();
        b.putLong(24,s.epoch()).putLong(32,s.input()).putInt(40,1).putInt(44,hideHud?1:0);
        b.putDouble(48,p.x()).putDouble(56,p.y()).putDouble(64,p.z());
        b.putFloat(72,p.yawDeg()).putFloat(76,p.pitchDeg()).putFloat(80,p.rollDeg()).putFloat(84,p.fovOffsetDeg());
        return b.array();
    }
}
