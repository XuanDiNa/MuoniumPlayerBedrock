package com.muoniumplayer.bedrock;
import java.nio.*;
/** Owned player-head pose. Its context lifetime is independent of UI/camera eligibility. */
record RoomVoiceWorld(long epoch,int dimension,double x,double y,double z,double lookX,double lookY,double lookZ,long geometryEpoch) {
 static final int BYTES=96;
 static RoomVoiceWorld decode(byte[] bytes,long now){
  if(bytes==null||bytes.length!=BYTES)return null;
  var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);long at=b.getLong(16);
  if(b.getInt(0)!=0x5657504d||b.getInt(4)!=1||b.getLong(8)<=0||b.getLong(24)<=0||b.getInt(32)!=1||at<=0||at>now||now-at>350)return null;
  var p=new RoomVoiceWorld(b.getLong(24),b.getInt(36),b.getDouble(40),b.getDouble(48),b.getDouble(56),b.getDouble(64),b.getDouble(72),b.getDouble(80),b.getLong(88));
  if(p.dimension<0||p.dimension>2||p.geometryEpoch<0)return null;
  for(double v:new double[]{p.x,p.y,p.z,p.lookX,p.lookY,p.lookZ})if(!Double.isFinite(v))return null;
  if(Math.abs(p.x)>32_000_000||Math.abs(p.y)>32_000_000||Math.abs(p.z)>32_000_000||Math.abs(p.lookX*p.lookX+p.lookY*p.lookY+p.lookZ*p.lookZ-1)>.02)return null;
  return p;
 }
}
