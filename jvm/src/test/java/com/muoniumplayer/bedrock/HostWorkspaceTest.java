package com.muoniumplayer.bedrock;
import com.muoniumplayer.compat.mc.GuiScreen;
import com.sun.jna.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class HostWorkspaceTest {
    interface Api extends StdCallLibrary {
        Api I=Native.load("kernel32",Api.class);
        HANDLE CreateFileMappingW(HANDLE file,Pointer attrs,int protection,int high,int bytes,WString name);
        HANDLE CreateMutexW(Pointer attrs,boolean owned,WString name);
        Pointer MapViewOfFile(HANDLE map,int access,int high,int low,long bytes);
        boolean UnmapViewOfFile(Pointer p);boolean CloseHandle(HANDLE h);long GetTickCount64();
    }
    @Test void exactPrivateMailboxCreatesRealMiniPageAndReturnsOnRootEscape()throws Exception{
        var api=Api.I;String prefix="Local\\MuoniumBedrock-"+UUID.randomUUID().toString().replace("-","");
        var map=api.CreateFileMappingW(new HANDLE(Pointer.createConstant(-1L)),null,4,0,64,new WString(prefix+"-workspace"));
        var mutex=api.CreateMutexW(null,false,new WString(prefix+"-workspace-lock"));
        var view=api.MapViewOfFile(map,6,0,0,64);assertNotNull(view);
        var client=BedrockClient.getInstance();client.bindWindow(0);client.getWindow().size(960,600,1);
        var panel=new OverlaySession(client,HostToolsScreen::entry);
        try(var workspace=new HostWorkspace(prefix)){
            view.clear(64);view.setInt(0,0x4D57534D);view.setInt(4,1);
            assertFalse(workspace.update(panel,true));assertNull(client.gui.screen());
            long id=Long.MIN_VALUE+77;view.setLong(8,19);view.setLong(16,id);view.setLong(24,api.GetTickCount64()+14000);view.setInt(32,9);view.setInt(36,1);
            assertTrue(workspace.update(panel,true));assertInstanceOf(HostMiniPlayer.class,client.gui.screen());assertEquals(2,view.getInt(36));assertEquals(id,view.getLong(16));
            var mini=client.gui.screen();assertFalse(workspace.update(panel,true));assertSame(mini,client.gui.screen());
            view.setInt(36,4);workspace.update(panel,true);assertEquals(4,view.getInt(36));
            panel.visible(true,false,false,false);assertEquals(4,view.getInt(36),"Focus loss cannot return or cancel");
            panel.close();workspace.update(panel,true);assertEquals(4,view.getInt(36),"Non-Esc close cannot return");
            panel.open(new HostMiniPlayer());
            var completed=new AtomicInteger();client.execute(completed::incrementAndGet);
            client.gui.screen().keyEvent('\0',com.muoniumplayer.compat.lwjgl2.Keyboard.KEY_ESCAPE);
            workspace.update(panel,true);
            assertEquals(5,view.getInt(36),"Root Esc must return to Workspace without an extra button");
            assertFalse(panel.isOpen());workspace.update(panel,true);assertEquals(5,view.getInt(36));
            client.drainTasks();assertEquals(1,completed.get(),"Return cannot cancel background work");
            view.setLong(16,id+1);view.setLong(24,api.GetTickCount64()-1);view.setInt(36,1);
            assertFalse(workspace.update(panel,true));assertEquals(3,view.getInt(36));assertFalse(panel.isOpen(),"Expired command cannot open later");
            view.setLong(16,id+2);view.setLong(24,api.GetTickCount64()+14000);view.setInt(36,1);
            assertFalse(workspace.update(panel,false));assertEquals(3,view.getInt(36));
            view.setLong(16,id+3);view.setInt(32,99);view.setInt(36,1);assertFalse(workspace.update(panel,true));assertEquals(3,view.getInt(36));
        }finally{panel.close();api.UnmapViewOfFile(view);api.CloseHandle(mutex);api.CloseHandle(map);}
    }
    @Test void toolsPageDoesNotAddAWorkspaceReturnButton()throws Exception{
        var client=BedrockClient.getInstance();client.bindWindow(0);client.getWindow().size(960,600,1);
        try{
            var page=HostToolsScreen.entry();client.gui.setScreen(page);
            var field=GuiScreen.class.getDeclaredField("buttonList");field.setAccessible(true);
            var list=(java.util.List<com.muoniumplayer.compat.mc.GuiButton>)field.get(page);
            assertEquals(7,list.size(),"Only original player tools remain; Back uses Esc");
            assertFalse(list.stream().anyMatch(b->b.displayString.contains("Workspace")));
        }finally{client.gui.setScreen(null);}
    }
    static class DelayedPage extends GuiScreen {
        boolean closing,modal=true;
        public boolean hostBackClosing(){return closing;}
        protected void keyTyped(char ch,int code){
            if(code!=com.muoniumplayer.compat.lwjgl2.Keyboard.KEY_ESCAPE)return;
            if(modal){modal=false;return;}closing=true;
        }
    }
    @Test void modalEscapeStaysOnPageAndAnimatedRootExitReturnsOnlyAfterActualClosure()throws Exception{
        try(var fixture=new Mailbox()){
            var root=new DelayedPage();fixture.panel.open(root);
            root.keyEvent('\0',com.muoniumplayer.compat.lwjgl2.Keyboard.KEY_ESCAPE);fixture.update();
            assertSame(root,fixture.client.gui.screen());assertEquals(4,fixture.view.getInt(36));
            // A non-Esc close after dismissing a modal must not reuse that key.
            fixture.panel.close();fixture.update();assertEquals(4,fixture.view.getInt(36));
            fixture.panel.open(root);root.keyEvent('\0',com.muoniumplayer.compat.lwjgl2.Keyboard.KEY_ESCAPE);fixture.update();
            assertEquals(4,fixture.view.getInt(36),"Retiring animation still owns the page");
            fixture.client.gui.setScreen(null);fixture.panel.finishScreenClose();fixture.update();
            assertEquals(5,fixture.view.getInt(36),"Animated root close must retain its explicit Esc intent");
        }
    }
    @Test void childEscapeReturnsToParentAndAbandonedAnimationCannotReturnLater()throws Exception{
        try(var fixture=new Mailbox()){
            var parent=new DelayedPage();parent.modal=false;
            var child=new GuiScreen(){public void onClose(){mc.gui.setScreen(parent);}};
            fixture.panel.open(child);child.keyEvent('\0',com.muoniumplayer.compat.lwjgl2.Keyboard.KEY_ESCAPE);fixture.update();
            assertSame(parent,fixture.client.gui.screen());assertEquals(4,fixture.view.getInt(36));
            parent.keyEvent('\0',com.muoniumplayer.compat.lwjgl2.Keyboard.KEY_ESCAPE);fixture.update();
            fixture.client.gui.setScreen(new HostMiniPlayer());fixture.panel.close();fixture.update();
            assertEquals(4,fixture.view.getInt(36),"Changing pages discards an abandoned root-close intent");
        }
    }
    @Test void formerBannerAreaDoesNotInterceptOrdinaryPlayerClicks()throws Exception{
        try(var fixture=new Mailbox()){
            var clicks=new AtomicInteger();var page=new GuiScreen(){protected void mouseClicked(int x,int y,int b){clicks.incrementAndGet();}};
            fixture.panel.open(page);page.clickEvent(950,12,0);fixture.update();
            assertEquals(1,clicks.get(),"Removed banner must not retain an invisible click target");assertEquals(4,fixture.view.getInt(36));
        }
    }
    @Test void earlyEscapeWaitsForNativeAcknowledgementAndCancellationClearsIntent()throws Exception{
        try(var fixture=new Mailbox()){
            fixture.view.setLong(16,fixture.view.getLong(16)+1);fixture.view.setInt(36,1);fixture.update();
            assertEquals(2,fixture.view.getInt(36));
            fixture.client.gui.screen().keyEvent('\0',com.muoniumplayer.compat.lwjgl2.Keyboard.KEY_ESCAPE);fixture.update();
            assertEquals(2,fixture.view.getInt(36),"Never return before native ownership acknowledgement");
            fixture.view.setInt(36,4);fixture.update();assertEquals(5,fixture.view.getInt(36));
            fixture.view.setLong(16,fixture.view.getLong(16)+1);fixture.view.setInt(36,1);fixture.update();
            fixture.client.gui.screen().keyEvent('\0',com.muoniumplayer.compat.lwjgl2.Keyboard.KEY_ESCAPE);
            fixture.view.setInt(36,6);fixture.update();assertEquals(6,fixture.view.getInt(36));
            fixture.view.setLong(16,fixture.view.getLong(16)+1);fixture.view.setInt(36,1);fixture.update();
            fixture.view.setInt(36,4);fixture.update();assertEquals(4,fixture.view.getInt(36),"Cancelled return cannot leak into a new handoff");
        }
    }
    static class Mailbox implements AutoCloseable {
        final Api api=Api.I;final HANDLE mapping,mutex;final Pointer view;
        final BedrockClient client=BedrockClient.getInstance();final OverlaySession panel;final HostWorkspace workspace;
        Mailbox(){
            String prefix="Local\\MuoniumBedrock-"+UUID.randomUUID().toString().replace("-","");
            mapping=api.CreateFileMappingW(new HANDLE(Pointer.createConstant(-1L)),null,4,0,64,new WString(prefix+"-workspace"));
            mutex=api.CreateMutexW(null,false,new WString(prefix+"-workspace-lock"));view=api.MapViewOfFile(mapping,6,0,0,64);assertNotNull(view);
            client.bindWindow(0);client.getWindow().size(960,600,1);client.gui.setScreen(null);panel=new OverlaySession(client,HostToolsScreen::entry);workspace=new HostWorkspace(prefix);
            view.clear(64);view.setInt(0,0x4D57534D);view.setInt(4,1);view.setLong(8,22);view.setLong(16,Long.MIN_VALUE+88);
            view.setLong(24,api.GetTickCount64()+14000);view.setInt(32,9);view.setInt(36,1);assertTrue(workspace.update(panel,true));
            view.setInt(36,4);workspace.update(panel,true);
        }
        void update(){workspace.update(panel,true);}
        public void close(){workspace.close();panel.close();api.UnmapViewOfFile(view);api.CloseHandle(mutex);api.CloseHandle(mapping);}
    }
}
