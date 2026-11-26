package com.muoniumplayer.bedrock;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.*;
/** Bounded connection writer; neither voice nor room engine waits for socket IO. */
public final class RoomVoiceWire {
 private RoomVoiceWire(){}
 public static final class Outbound implements AutoCloseable {
  private record Frame(byte[] bytes,long at){}
  private final ArrayBlockingQueue<Frame> controls=new ArrayBlockingQueue<>(32),audio=new ArrayBlockingQueue<>(8);
  private final Consumer<byte[]> write;private final Runnable failure;private final LongSupplier clock;
  private final AtomicBoolean started=new AtomicBoolean(),closed=new AtomicBoolean();private volatile long writing;
  private volatile Thread worker,watchdog;
  public Outbound(Consumer<byte[]> write,Runnable failure){this(write,failure,System::nanoTime);}
  Outbound(Consumer<byte[]> write,Runnable failure,LongSupplier clock){this.write=write;this.failure=failure;this.clock=clock;}
  public int audioPending(){return audio.size();}
  public void control(byte[] bytes){if(closed.get())return;if(!controls.offer(new Frame(bytes.clone(),clock.getAsLong()))){fail();return;}start();LockSupport.unpark(worker);}
  public void audio(byte[] bytes){if(closed.get()||bytes.length>2048)return;Frame frame=new Frame(bytes.clone(),clock.getAsLong());if(!audio.offer(frame)){audio.poll();audio.offer(frame);}start();LockSupport.unpark(worker);}
  private void start(){if(!started.compareAndSet(false,true))return;
   worker=Thread.ofVirtual().name("muonium-room-writer").start(()->{
    try{while(!closed.get()){
     Frame frame=controls.poll();boolean voice=false;if(frame==null){frame=audio.poll();voice=true;}
     if(frame==null){LockSupport.parkNanos(100_000_000L);continue;}
     long now=clock.getAsLong();if(voice&&(now<frame.at||now-frame.at>200_000_000L))continue;
     writing=System.nanoTime();try{write.accept(frame.bytes);}finally{writing=0;}
    }}catch(Throwable broken){fail();}
   });
   watchdog=Thread.ofVirtual().name("muonium-room-write-watch").start(()->{try{while(!closed.get()){
    long since=writing;if(since!=0&&System.nanoTime()-since>2_000_000_000L){fail();return;}Thread.sleep(100);
   }}catch(InterruptedException stopped){Thread.currentThread().interrupt();}});
  }
  private void fail(){if(closed.compareAndSet(false,true)){controls.clear();audio.clear();failure.run();if(worker!=null)worker.interrupt();if(watchdog!=null)watchdog.interrupt();}}
  public void close(){closed.set(true);controls.clear();audio.clear();if(worker!=null)worker.interrupt();if(watchdog!=null)watchdog.interrupt();}
 }
}
