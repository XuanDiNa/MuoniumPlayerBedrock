package com.muoniumplayer.bedrock;
import com.google.gson.*;
import java.util.UUID;
/** Owned-position spatial DSP; only the dedicated room audio worker traces voxels. */
final class RoomVoiceSpatial {
 record Source(String world,int dimension,double x,double y,double z,int range){
  static Source decode(JsonObject p){
   String world=p.get("world").getAsString();if(world.length()!=36)throw new IllegalArgumentException("Spatial world");UUID.fromString(world);
   int d=p.get("dimension").getAsBigDecimal().intValueExact(),range=p.get("range").getAsBigDecimal().intValueExact();
   double x=p.get("x").getAsDouble(),y=p.get("y").getAsDouble(),z=p.get("z").getAsDouble();
   if(d<0||d>2||!validRange(range))throw new IllegalArgumentException("Spatial dimension/range");
   for(double v:new double[]{x,y,z})if(!Double.isFinite(v)||Math.abs(v)>32_000_000)throw new IllegalArgumentException("Spatial position");
   return new Source(world,d,x,y,z,range);
  }
  JsonObject json(){var p=new JsonObject();p.addProperty("world",world);p.addProperty("dimension",dimension);
   p.addProperty("x",x);p.addProperty("y",y);p.addProperty("z",z);p.addProperty("range",range);return p;}
 }
 static boolean validRange(int range){return range==8||range==16||range==32||range==64;}
 static double[] target(WorldFrame listener,EnvironmentFrame environment,JsonObject p){return target(listener,environment,Source.decode(p));}
 static double[] target(WorldFrame listener,EnvironmentFrame environment,Source s){
  return target(listener==null?null:new RoomVoiceWorld(listener.epoch(),listener.dimension(),listener.eyeX(),listener.eyeY(),listener.eyeZ(),listener.lookX(),listener.lookY(),listener.lookZ(),listener.epoch()),environment,s);
 }
 static double[] target(RoomVoiceWorld listener,EnvironmentFrame environment,Source s){
  if(listener==null||s==null||listener.dimension()!=s.dimension)return new double[]{0,0,500};
  double dx=s.x-listener.x(),dy=s.y-listener.y(),dz=s.z-listener.z(),distance=Math.sqrt(dx*dx+dy*dy+dz*dz);
  if(distance>=s.range)return new double[]{0,0,500};
  double gain=distance<=2?1:Math.pow(1-(distance-2)/(s.range-2),2);
  double horizontal=Math.hypot(dx,dz),look=Math.hypot(listener.lookX(),listener.lookZ());
  double pan=horizontal<1e-6||look<1e-6?0:Math.max(-1,Math.min(1,(-dx*listener.lookZ()+dz*listener.lookX())/(horizontal*look)));
  double obstruction=20;
  if(environment!=null&&environment.epoch==listener.geometryEpoch()&&environment.dimension==listener.dimension())
   obstruction=EnvironmentGeometry.occlusion(environment,listener.x(),listener.y(),listener.z(),s.x,s.y,s.z);
  double cutoff=18000;
  if(obstruction>=20){gain*=.08;cutoff=500;}
  else if(obstruction>0){gain*=Math.pow(.45,Math.min(4,obstruction));cutoff=Math.max(350,1600/(obstruction+.5));}
  return new double[]{gain*Math.sqrt((1-pan)/2),gain*Math.sqrt((1+pan)/2),cutoff};
 }
 static final class Filter {
  private double left,right,cutoff=18000,low;
  void mix(float[] mono,float[] stereo,double[] target,float volume){
   if(mono.length!=960||stereo.length!=1920||target.length!=3)throw new IllegalArgumentException("Spatial audio shape");
   final double smooth=1-Math.exp(-1/(48000*.03));
   for(int i=0;i<960;i++){
    left+=(target[0]-left)*smooth;right+=(target[1]-right)*smooth;cutoff+=(target[2]-cutoff)*smooth;
    double sample=Float.isFinite(mono[i])?Math.max(-1,Math.min(1,mono[i])):0;
    double alpha=1-Math.exp(-2*Math.PI*Math.max(60,Math.min(18000,cutoff))/48000);low+=(sample-low)*alpha;
    stereo[i*2]+=(float)(low*left*volume);stereo[i*2+1]+=(float)(low*right*volume);
   }
  }
 }
}
