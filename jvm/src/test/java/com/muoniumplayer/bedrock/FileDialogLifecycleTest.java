package com.muoniumplayer.bedrock;

import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FileDialogLifecycleTest {
    @Test void longLivedPickerNeverBlocksHostAndResultReturnsOnHostThread()throws Exception {
        var client=BedrockClient.getInstance();client.bindWindow(0);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var result=new AtomicReference<File>();var callback=new AtomicReference<Thread>();
        File selected=new File(".").getAbsoluteFile();
        try {
            assertTrue(HostFileDialogs.request(".",file->{result.set(file);callback.set(Thread.currentThread());},(initial,owner)->{
                assertTrue(java.awt.EventQueue.isDispatchThread());entered.countDown();
                assertTrue(release.await(5,TimeUnit.SECONDS));return selected;
            }));
            assertTrue(entered.await(2,TimeUnit.SECONDS));assertTrue(HostFileDialogs.active());
            assertFalse(HostFileDialogs.request(".",f->{},(a,b)->null),"Only one owned picker may exist");
            var pumped=new AtomicInteger();client.execute(pumped::incrementAndGet);client.drainTasks();
            assertEquals(1,pumped.get(),"Host task queue must pump while picker waits for user");
            assertNull(result.get());release.countDown();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(HostFileDialogs.active()&&System.nanoTime()<end){client.drainTasks();Thread.sleep(10);}
            client.drainTasks();assertFalse(HostFileDialogs.active());assertEquals(selected,result.get());
            assertSame(Thread.currentThread(),callback.get());
        }finally{release.countDown();HostFileDialogs.cancel();}
    }
    @Test void disableInvalidatesPendingResultInsteadOfImportingIntoClosedPage()throws Exception {
        var client=BedrockClient.getInstance();client.bindWindow(0);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var called=new AtomicBoolean();
        try {
            assertTrue(HostFileDialogs.request(".",f->called.set(true),(initial,owner)->{
                entered.countDown();release.await(3,TimeUnit.SECONDS);return new File(".");
            }));
            assertTrue(entered.await(2,TimeUnit.SECONDS));HostFileDialogs.cancel();release.countDown();
            java.awt.EventQueue.invokeAndWait(()->{});client.drainTasks();
            assertFalse(HostFileDialogs.active());assertFalse(called.get());
        }finally{release.countDown();HostFileDialogs.cancel();}
    }
    @Test void reportedOriginalImportNoLongerRunsModalDialogInsideClickCallback()throws Exception {
        try(var stream=getClass().getResourceAsStream("/com/muoniumplayer/core/screens/ncm/PlaylistCreateSheet.class")) {
            assertNotNull(stream);String code=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.ISO_8859_1);
            assertTrue(code.contains("HostFileDialogs"));assertFalse(code.contains("showOpenDialog"));
        }
    }
    @Test void cancellationAllowsRetryAndOldCompletionCannotStealNewRequest()throws Exception {
        var client=BedrockClient.getInstance();client.bindWindow(0);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var oldCalled=new AtomicBoolean();var selected=new AtomicReference<File>();
        File expected=new File("retry");
        try {
            assertTrue(HostFileDialogs.request(".",file->oldCalled.set(true),(initial,owner)->{
                entered.countDown();release.await(3,TimeUnit.SECONDS);return new File("stale");
            }));
            assertTrue(entered.await(2,TimeUnit.SECONDS));HostFileDialogs.cancel();
            assertTrue(HostFileDialogs.request(".",selected::set,(initial,owner)->expected));
            release.countDown();java.awt.EventQueue.invokeAndWait(()->{});client.drainTasks();
            assertFalse(oldCalled.get());assertEquals(expected,selected.get());assertFalse(HostFileDialogs.active());
        }finally{release.countDown();HostFileDialogs.cancel();}
    }
    @Test void pickerFailureReleasesRequestAndReturnsNoSelection()throws Exception {
        var client=BedrockClient.getInstance();client.bindWindow(0);
        var called=new AtomicBoolean();var selected=new AtomicReference<File>(new File("unchanged"));
        try {
            assertTrue(HostFileDialogs.request(".",file->{called.set(true);selected.set(file);},(initial,owner)->{
                throw new java.io.IOException("fixture");
            }));
            java.awt.EventQueue.invokeAndWait(()->{});client.drainTasks();
            assertTrue(called.get());assertNull(selected.get());assertFalse(HostFileDialogs.active());
        }finally{HostFileDialogs.cancel();}
    }
}
