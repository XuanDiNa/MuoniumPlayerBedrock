package com.muoniumplayer.bedrock;

import com.muoniumplayer.compat.mc.GuiScreen;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OverlaySessionTest {
    static final class Page extends GuiScreen {
        int opens, closes;
        final Object pendingNotice = new Object();
        @Override public void initGui() { opens++; }
        @Override public void onGuiClosed() { closes++; }
    }
    @Test void altTabAndMinimizeRetainThePendingPageUntilAnExplicitClose() {
        var client = BedrockClient.getInstance(); client.bindWindow(0); client.gui.setScreen(null);
        var page = new Page(); var session = new OverlaySession(client, () -> page);
        try {
            session.toggle(); Object notice = page.pendingNotice;
            assertTrue(session.visible(true,false,false,true));
            assertFalse(session.visible(true,false,false,false));
            assertSame(page,client.gui.screen(),"Focus loss must hide, not close the pending installation page");
            assertTrue(session.isOpen()); assertEquals(0,page.closes);
            assertFalse(session.visible(true,true,false,false));
            assertTrue(session.visible(true,false,true,false));
            assertSame(page,client.gui.screen()); assertSame(notice,page.pendingNotice);
            assertEquals(1,page.opens,"Returning focus must not rebuild the page");
            session.toggle(); assertNull(client.gui.screen()); assertEquals(1,page.closes);
        } finally { session.close(); }
    }
    @Test void disablingClosesTheInteractivePageAndDoesNotReopenItOnEnable() {
        var client = BedrockClient.getInstance(); client.bindWindow(0); client.gui.setScreen(null);
        var page = new Page(); var session = new OverlaySession(client, () -> page);
        session.toggle();
        assertFalse(session.visible(false,false,false,true));
        assertFalse(session.isOpen()); assertNull(client.gui.screen()); assertEquals(1,page.closes);
        assertTrue(session.visible(true,false,true,false));
        assertFalse(session.isOpen()); assertNull(client.gui.screen());
    }
    @Test void anOriginalScreenCloseEndsInteractionUntilTheNextOpenAction() {
        var client = BedrockClient.getInstance(); client.bindWindow(0); client.gui.setScreen(null);
        var page = new Page(); var session = new OverlaySession(client, () -> page);
        session.toggle(); client.gui.setScreen(null);
        assertTrue(session.finishScreenClose()); assertFalse(session.isOpen());
        assertFalse(session.finishScreenClose());
        assertTrue(session.visible(true,false,true,false));
        assertNull(client.gui.screen());
        session.toggle(); assertSame(page,client.gui.screen());
        session.close();
    }
}
