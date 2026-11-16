package com.muoniumplayer.bedrock;

import com.muoniumplayer.compat.mc.GuiScreen;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ExternalScreenLifecycleTest {
    static class Page extends GuiScreen {
        int opens,clicks,releases;
        boolean dragging;
        public void initGui(){opens++;}
        protected void mouseClicked(int x,int y,int b){clicks++;dragging=true;}
        protected void mouseReleased(int x,int y,int b){releases++;dragging=false;}
    }
    @Test void ownedEntryOffersDirectButtonsWithoutApplySelection() throws Exception {
        var client=BedrockClient.getInstance();client.bindWindow(0);client.getWindow().size(960,600,1);
        var screen=new HostToolsScreen();client.gui.setScreen(screen);
        try {
            var field=GuiScreen.class.getDeclaredField("buttonList");field.setAccessible(true);
            var buttons=(java.util.List<com.muoniumplayer.compat.mc.GuiButton>)field.get(screen);
            assertTrue(buttons.stream().anyMatch(b->b.displayString.contains("打开播放器")),"The landing page needs a direct Player button");
            assertTrue(buttons.stream().anyMatch(b->b.displayString.contains("普通设置")),"Native host settings must remain accessible");
            assertTrue(buttons.stream().anyMatch(b->b.displayString.contains("HUD")));
            assertTrue(buttons.stream().noneMatch(b->b.displayString.contains("Apply")));
        }finally{client.gui.setScreen(null);}
    }
    @Test void configureIsIdempotentAndBackgroundCompletionSurvivesFocusLossAndClose() throws Exception {
        var client=BedrockClient.getInstance();client.bindWindow(0);client.gui.setScreen(null);
        var page=new Page();var session=new OverlaySession(client,()->page);
        var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);var completed=new AtomicInteger();
        try(var worker=Executors.newSingleThreadExecutor()) {
            session.show();session.show();assertTrue(session.isOpen());assertEquals(1,page.opens);
            assertEquals(0,page.clicks,"Opening a page cannot synthesize an action");
            worker.submit(()->{entered.countDown();try{finish.await();client.execute(completed::incrementAndGet);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertFalse(session.visible(true,false,false,false));assertTrue(session.isOpen());
            assertTrue(session.visible(true,false,true,false));assertEquals(1,page.opens);
            session.close();finish.countDown();worker.shutdown();assertTrue(worker.awaitTermination(2,TimeUnit.SECONDS));
            client.drainTasks();assertEquals(1,completed.get(),"Closing the page cannot cancel background work");
        }finally{finish.countDown();session.close();}
    }
    @Test void losingFocusCancelsPressedAndDraggingStateWithoutAReleaseClick() throws Exception {
        var client=BedrockClient.getInstance();client.bindWindow(0);client.gui.setScreen(null);
        var page=new Page();var session=new OverlaySession(client,()->page);session.open(page);
        try {
            page.clickEvent(20,20,0);assertTrue(page.dragging);
            session.visible(true,false,false,true);session.visible(true,false,false,false);
            assertFalse(page.dragging,"Focus loss must release drag state");assertTrue(page.releases>0);
            assertEquals(1,page.clicks);assertSame(page,client.gui.screen());
            session.visible(true,false,true,false);assertFalse(page.dragging);
        }finally{session.close();}
    }
}
