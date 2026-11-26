package com.muoniumplayer.bedrock;
import com.google.gson.*;import java.util.*;import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RoomVoiceSpatialTest {
 static JsonObject source(double x,double y,double z,int dimension,int range){
  var p=new JsonObject();p.addProperty("world",UUID.randomUUID().toString());p.addProperty("dimension",dimension);
  p.addProperty("x",x);p.addProperty("y",y);p.addProperty("z",z);p.addProperty("range",range);return p;
 }
 static double[] target(EnvironmentFrame env,JsonObject source)throws Exception{
  Class<?> type;try{type=Class.forName("com.muoniumplayer.bedrock.RoomVoiceSpatial");}catch(ClassNotFoundException e){fail("Spatial room audio is not implemented");return null;}
  return (double[])type.getDeclaredMethod("target",WorldFrame.class,EnvironmentFrame.class,JsonObject.class)
    .invoke(null,WorldSpeakerTest.camera(7,0),env,source);
 }
 @Test void stereoDirectionDistanceRangeAndDimensionAreAudibleRules()throws Exception{
  var env=EnvironmentFrame.decode(EnvironmentFrameTest.packet(),1001,7);
  var right=target(env,source(-7.5,.5,.5,0,32));var left=target(env,source(8.5,.5,.5,0,32));
  assertTrue(right[1]>right[0]*3);assertTrue(left[0]>left[1]*3);
  var near=target(env,source(.5,.5,2.5,0,32));var far=target(env,source(.5,.5,12.5,0,32));assertTrue(near[0]>far[0]);
  assertEquals(0,target(env,source(.5,.5,32.5,0,32))[0]);assertEquals(0,target(env,source(.5,.5,2.5,1,32))[1]);
 }
 @Test void actualVoxelWallAndUnknownPathMuffleInsteadOfClaimingClearAir()throws Exception{
  var raw=EnvironmentFrameTest.packet();var source=source(.5,.5,8.5,0,32);
  var clear=target(EnvironmentFrame.decode(raw,1001,7),source);WorldSpeakerTest.cell(raw,0,0,4,1);
  var wall=target(EnvironmentFrame.decode(raw,1001,7),source);assertTrue(wall[0]>0&&wall[0]<clear[0]);assertTrue(wall[2]<2000&&clear[2]>10000);
  WorldSpeakerTest.cell(raw,0,0,4,255);var unknown=target(EnvironmentFrame.decode(raw,1001,7),source);assertTrue(unknown[0]<=wall[0]);
 }
 @Test void filterProducesFiniteStereoAndSmoothsSuddenGainChanges()throws Exception{
  Class<?> type;try{type=Class.forName("com.muoniumplayer.bedrock.RoomVoiceSpatial$Filter");}catch(ClassNotFoundException e){fail("Spatial DSP is missing");return;}
  var filter=type.getDeclaredConstructor().newInstance();var mix=type.getDeclaredMethod("mix",float[].class,float[].class,double[].class,float.class);
  float[] mono=new float[960];Arrays.fill(mono,.2f);float[] stereo=new float[1920];
  for(int n=0;n<15;n++){Arrays.fill(stereo,0);mix.invoke(filter,mono,stereo,new double[]{1,0,18000},1f);}
  assertTrue(stereo[1918]>.15f);assertEquals(0,stereo[1919],1e-6);
  Arrays.fill(stereo,0);mix.invoke(filter,mono,stereo,new double[]{0,0,800},1f);
  assertTrue(stereo[0]>.1f);assertTrue(stereo[1918]<stereo[0]);for(float f:stereo)assertTrue(Float.isFinite(f));
 }
 @Test void lowPassActuallyRemovesHighFrequencyAudioEnergy(){
  double[] energy=new double[2];int side=0;
  for(double cutoff:new double[]{18000,800}){
   var filter=new RoomVoiceSpatial.Filter();float[] tone=new float[960],out=new float[1920];
   for(int i=0;i<tone.length;i++)tone[i]=(float)(.2*Math.sin(2*Math.PI*8000*i/48000));
   for(int frame=0;frame<25;frame++){Arrays.fill(out,0);filter.mix(tone,out,new double[]{1,0,cutoff},1);}
   for(int i=0;i<960;i++)energy[side]+=out[2*i]*out[2*i];side++;
  }
  assertTrue(energy[0]>.1);assertTrue(energy[1]<energy[0]*.05,"Wall low-pass must change real audio, not only its plan values");
 }
}
