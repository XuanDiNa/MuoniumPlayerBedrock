package com.muoniumplayer.bedrock;

import com.sun.jna.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import com.muoniumplayer.compat.mc.GuiScreen;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.settings.HudConfig;

/** Exact uint64 handoff over an owned mutex/mapping; only the render thread creates pages. */
final class HostWorkspace implements AutoCloseable {
    interface Api extends StdCallLibrary {
        Api INSTANCE=Native.load("kernel32",Api.class);
        HANDLE OpenFileMappingW(int access,boolean inherit,WString name);
        HANDLE OpenMutexW(int access,boolean inherit,WString name);
        Pointer MapViewOfFile(HANDLE mapping,int access,int high,int low,long bytes);
        int WaitForSingleObject(HANDLE handle,int millis);
        boolean ReleaseMutex(HANDLE handle);
        boolean UnmapViewOfFile(Pointer view);
        boolean CloseHandle(HANDLE handle);
        long GetTickCount64();
    }
    private HANDLE mapping,mutex;private Pointer view;
    private long session,request;private boolean accepted,returnRequested;
    private GuiScreen opened;
    HostWorkspace(String prefix){
        if(!prefix.matches("Local\\\\MuoniumBedrock-[0-9a-f]{32}"))throw new IllegalArgumentException("Workspace identity");
        mapping=Api.INSTANCE.OpenFileMappingW(6,false,new WString(prefix+"-workspace"));
        mutex=Api.INSTANCE.OpenMutexW(0x00100001,false,new WString(prefix+"-workspace-lock"));
        if(mapping!=null&&mutex!=null)view=Api.INSTANCE.MapViewOfFile(mapping,6,0,0,64);
        if(view==null){close();throw new IllegalStateException("Private workspace transport unavailable");}
    }
    private boolean lock(){int r=Api.INSTANCE.WaitForSingleObject(mutex,0);return r==0||r==0x80;}
    boolean update(OverlaySession panel,boolean enabled){
        if(view==null||!lock())return false;
        boolean focus=false;
        try{
            if(view.getInt(0)!=0x4D57534D||view.getInt(4)!=1)return false;
            long nextSession=view.getLong(8),nextRequest=view.getLong(16);int phase=view.getInt(36);
            if(phase==1&&(session!=nextSession||request!=nextRequest)){
                HostBackNavigation.clear();returnRequested=false;
                session=nextSession;request=nextRequest;accepted=false;opened=null;
                boolean valid=enabled&&session!=0&&request!=0&&Api.INSTANCE.GetTickCount64()<view.getLong(24);
                if(valid)try{
                    valid=openTarget(panel,view.getInt(32));
                    if(valid){opened=BedrockClient.getInstance().gui.screen();focus=true;}
                }catch(RuntimeException e){valid=false;System.out.println("MODMENU workspace UI creation failed");}
                valid&=Api.INSTANCE.GetTickCount64()<view.getLong(24);
                if(!valid&&opened!=null&&BedrockClient.getInstance().gui.screen()==opened){panel.close();opened=null;focus=false;}
                if(valid)HostBackNavigation.bind(()->returnRequested=true);
                view.setInt(36,valid?2:3);
            }else if(session==nextSession&&request==nextRequest){
                if(phase==4&&!accepted)accepted=true;
                if(phase==6||!enabled){
                    HostBackNavigation.clear();returnRequested=false;
                    if(!accepted&&opened!=null&&BedrockClient.getInstance().gui.screen()==opened)panel.close();
                    accepted=false;
                }
            }else {accepted=false;returnRequested=false;HostBackNavigation.clear();}
        }finally{Api.INSTANCE.ReleaseMutex(mutex);}
        if(returnRequested&&accepted)returnToWorkspace(panel);
        return focus;
    }
    static boolean openTarget(OverlaySession panel,int control){
        switch(control){
            case 11 -> panel.open(NCMScreen.getInstance());
            case 7 -> panel.open(new HostHudEditor());
            case 8 -> panel.open(new com.muoniumplayer.core.screens.hud.GuiPresetSettings(
                null,com.muoniumplayer.core.screens.hud.PresetSettingsActions.Mode.MANAGE));
            case 9 -> panel.open(new HostMiniPlayer());
            case 10 -> {if(!HudConfig.islandSearchEnabled)return false;panel.close();HostIslandSearch.open(panel);}
            default -> {return false;}
        }
        return panel.isOpen()&&BedrockClient.getInstance().gui.screen()!=null;
    }
    private void returnToWorkspace(OverlaySession panel){
        if(!accepted||view==null||!lock())return;
        try{
            if(view.getLong(8)!=session||view.getLong(16)!=request||view.getInt(36)!=4)return;
            HostBackNavigation.clear();returnRequested=false;
            panel.close();accepted=false;view.setInt(36,5);
        }finally{Api.INSTANCE.ReleaseMutex(mutex);}
    }
    public void close(){
        HostBackNavigation.clear();returnRequested=false;
        if(view!=null)Api.INSTANCE.UnmapViewOfFile(view);view=null;
        if(mapping!=null)Api.INSTANCE.CloseHandle(mapping);mapping=null;
        if(mutex!=null)Api.INSTANCE.CloseHandle(mutex);mutex=null;
    }
}
