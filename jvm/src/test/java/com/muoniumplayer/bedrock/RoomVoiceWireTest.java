package com.muoniumplayer.bedrock;
import java.util.*;import java.util.concurrent.*;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class RoomVoiceWireTest {
 @Test void boundedVoiceDropsStaleFramesAndPrioritizesControls()throws Exception{
  var first=new CountDownLatch(1);var release=new CountDownLatch(1);var seen=new CopyOnWriteArrayList<Integer>();long[] now={0};
  try(var out=new RoomVoiceWire.Outbound(bytes->{if(bytes[0]==0){first.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}seen.add((int)bytes[0]);},()->{},()->now[0])){
   out.control(new byte[]{0});assertTrue(first.await(2,TimeUnit.SECONDS));
   for(int i=1;i<=40;i++)out.audio(new byte[]{(byte)i});assertTrue(out.audioPending()<=8);
   out.control(new byte[]{99});now[0]=300_000_000L;release.countDown();
   for(int i=0;i<100&&seen.size()<2;i++)Thread.sleep(10);
   assertEquals(List.of(0,99),seen);
  }
 }
 @Test void stalledWriterClosesWithoutBlockingTheRoomCaller()throws Exception{
  var blocked=new CountDownLatch(1);var broken=new CountDownLatch(1);var hold=new CountDownLatch(1);
  try(var out=new RoomVoiceWire.Outbound(bytes->{blocked.countDown();try{hold.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}},broken::countDown)){
   out.control(new byte[]{1});assertTrue(blocked.await(1,TimeUnit.SECONDS));long start=System.nanoTime();
   for(int i=0;i<100;i++)out.audio(new byte[]{1});assertTrue(System.nanoTime()-start<500_000_000L);
   assertTrue(broken.await(3,TimeUnit.SECONDS));assertEquals(0,out.audioPending());
  }finally{hold.countDown();}
 }
}
