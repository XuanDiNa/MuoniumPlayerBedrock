package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class HostWatchdogTest {
    @Test void capturesBlockedHostWithoutLoggingDynamicThreadNamesAndStopsOnClose()throws Exception {
        Class<?> type;
        try{type=Class.forName("com.muoniumplayer.bedrock.HostWatchdog");}
        catch(ClassNotFoundException missing){fail("Unresponsive player has no independent host watchdog");return;}
        var released=new CountDownLatch(1);var entered=new CountDownLatch(1);
        Thread host=new Thread(()->{entered.countDown();try{released.await();}catch(InterruptedException ignored){}},
            "private-track https://private.invalid/?signed=secret");
        host.start();assertTrue(entered.await(1,TimeUnit.SECONDS));
        var lines=new CopyOnWriteArrayList<String>();
        var constructor=type.getDeclaredConstructor(Thread.class,Consumer.class,long.class,long.class);constructor.setAccessible(true);
        Object watchdog=constructor.newInstance(host,(Consumer<String>)lines::add,80_000_000L,10L);
        Method close=type.getDeclaredMethod("close");close.setAccessible(true);
        try {
            long until=System.nanoTime()+2_000_000_000L;
            while(lines.stream().noneMatch(s->s.contains("CountDownLatch.await"))&&System.nanoTime()<until)Thread.sleep(10);
            assertTrue(lines.stream().anyMatch(s->s.startsWith("HOST_STALL ")),"A stalled host must produce an independent report");
            assertTrue(lines.stream().anyMatch(s->s.contains("CountDownLatch.await")),"Capture the actual blocking stack, not just a timer warning");
            assertTrue(lines.stream().anyMatch(s->s.contains("role=host")&&s.contains("state=WAITING")));
            assertFalse(String.join("\n",lines).contains("private-track"));
            assertFalse(String.join("\n",lines).contains("signed=secret"));
            Thread.sleep(160);close.invoke(watchdog);Thread.sleep(60);
            int count=lines.size();Thread.sleep(80);assertEquals(count,lines.size(),"Closed watcher must not keep logging");
            assertTrue(count<2600,"Reports must remain bounded");
            assertTrue(host.isAlive(),"Diagnostics must not kill or interrupt playback");
        }finally{close.invoke(watchdog);released.countDown();host.join(1000);}
    }
    @Test void progressingHostDoesNotReportStall()throws Exception {
        Class<?> type;
        try{type=Class.forName("com.muoniumplayer.bedrock.HostWatchdog");}
        catch(ClassNotFoundException missing){fail("Unresponsive player has no independent host watchdog");return;}
        var lines=new CopyOnWriteArrayList<String>();
        var constructor=type.getDeclaredConstructor(Thread.class,Consumer.class,long.class,long.class);constructor.setAccessible(true);
        Object watchdog=constructor.newInstance(Thread.currentThread(),(Consumer<String>)lines::add,80_000_000L,10L);
        Class<?> stage=Class.forName(type.getName()+"$Stage");
        Method phase=type.getDeclaredMethod("phase",stage);phase.setAccessible(true);
        Method close=type.getDeclaredMethod("close");close.setAccessible(true);
        try {
            Object idle=java.util.Arrays.stream(stage.getEnumConstants()).filter(e->e.toString().equals("IDLE")).findFirst().orElseThrow();
            for(int i=0;i<25;i++){phase.invoke(watchdog,idle);Thread.sleep(10);}
            assertFalse(lines.stream().anyMatch(s->s.startsWith("HOST_STALL ")));
        }finally{close.invoke(watchdog);}
    }
}
