package com.muoniumplayer.bedrock;
import java.nio.*;
record CinematicState(long sequence,long at,long epoch,long input,boolean applied,
    double x,double y,double z,double eyeX,double eyeY,double eyeZ,float yaw,float pitch,float fov,float health){
    static CinematicState decode(byte[] raw,long now){
        if(raw==null||raw.length!=112)return null;var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        long at=b.getLong(16);
        if(b.getInt(0)!=0x5350434d||b.getInt(4)!=1||b.getLong(8)<=0||b.getLong(24)<=0
            ||b.getInt(40)!=1||b.getInt(44)<0||b.getInt(44)>1||at<=0||at>now||now-at>250)return null;
        var s=new CinematicState(b.getLong(8),at,b.getLong(24),b.getLong(32),b.getInt(44)==1,
            b.getDouble(48),b.getDouble(56),b.getDouble(64),b.getDouble(72),b.getDouble(80),b.getDouble(88),
            b.getFloat(96),b.getFloat(100),b.getFloat(104),b.getFloat(108));
        for(double n:new double[]{s.x,s.y,s.z,s.eyeX,s.eyeY,s.eyeZ})if(!Double.isFinite(n)||Math.abs(n)>32_000_000)return null;
        if(!Float.isFinite(s.yaw)||!Float.isFinite(s.pitch)||Math.abs(s.pitch)>90||!Float.isFinite(s.fov)||s.fov<10||s.fov>170
            ||!Float.isFinite(s.health)||s.health<=0||s.health>1)return null;
        return s;
    }
    static final class Session {
        private CinematicState previous;
        boolean changed(CinematicState next){
            boolean result=(previous==null)!=(next==null)||previous!=null&&next!=null
                &&(previous.epoch!=next.epoch||previous.input!=next.input||next.health<previous.health
                    ||distance(previous,next)>.0001);
            previous=next;return result;
        }
        private static double distance(CinematicState a,CinematicState b){
            return Math.pow(a.x-b.x,2)+Math.pow(a.y-b.y,2)+Math.pow(a.z-b.z,2);
        }
    }
}
