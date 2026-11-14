package com.muoniumplayer.bedrock;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.win32.StdCallLibrary;

/** At most three additional passive alpha bands, owned by the main overlay.
 * No GL context, input capture, topmost flag or game-window mutation. */
final class PassiveLayerWindows implements AutoCloseable {
    private interface Api extends StdCallLibrary {
        Api I=Native.load("user32",Api.class);
        WinDef.HWND CreateWindowExW(int ex,WString type,WString title,int style,int x,int y,int w,int h,
            WinDef.HWND parent,WinDef.HMENU menu,WinDef.HINSTANCE instance,Pointer param);
    }
    private final WinDef.HWND owner;
    private final WinDef.HWND[] windows=new WinDef.HWND[3];
    private final boolean[] shown=new boolean[3];
    record Placement(int slot,AlphaBounds.Region region){}
    private final AlphaBounds.Region[] previous=new AlphaBounds.Region[4];
    private final boolean[] active=new boolean[3];
    private boolean visible;
    PassiveLayerWindows(WinDef.HWND owner){this.owner=owner;}
    WinDef.HWND window(int index) {
        if(index==0)return owner;
        int at=index-1;
        if(windows[at]==null){
            windows[at]=Api.I.CreateWindowExW(0x80000|0x20|0x08000000|0x80,new WString("STATIC"),new WString("Muonium passive layer"),
                0x80000000,0,0,1,1,owner,null,null,null);
            if(windows[at]==null)throw new IllegalStateException("Passive layer unavailable win32="+Native.getLastError());
        }
        return windows[at];
    }
    // Keep unchanged bands on the same HWND. Array-order reassignment hid the
    // top letterbox whenever a lyric island disappeared below it.
    java.util.List<Placement> plan(java.util.List<AlphaBounds.Region> next){
        int[] slots=new int[next.size()];java.util.Arrays.fill(slots,-1);
        boolean[] used=new boolean[4];slots[0]=0;used[0]=true; // Main/input HWND stays live.
        for(int i=1;i<slots.length;i++)for(int s=1;s<4;s++)
            if(!used[s]&&next.get(i).equals(previous[s])){slots[i]=s;used[s]=true;break;}
        while(true){int ni=-1,ns=-1;long best=0;
            for(int i=1;i<slots.length;i++)if(slots[i]<0)for(int s=1;s<4;s++)if(!used[s]&&previous[s]!=null){
                var a=next.get(i);var b=previous[s];long overlap=(long)Math.max(0,Math.min(a.right(),b.right())-Math.max(a.left(),b.left()))
                    *Math.max(0,Math.min(a.top(),b.top())-Math.max(a.bottom(),b.bottom()));
                if(overlap>best){best=overlap;ni=i;ns=s;}
            }
            if(ni<0)break;slots[ni]=ns;used[ns]=true;
        }
        for(int i=1;i<slots.length;i++)if(slots[i]<0)for(int s=1;s<4;s++)if(!used[s]){slots[i]=s;used[s]=true;break;}
        var result=new java.util.ArrayList<Placement>();
        for(int i=0;i<slots.length;i++)result.add(new Placement(slots[i],next.get(i)));
        // Publish new/expanding windows before shrinking an old full-frame
        // window. Retire old windows only after all replacements are visible.
        result.sort(java.util.Comparator.comparingInt(p->{var old=previous[p.slot()];var r=p.region();
            return old!=null&&(r.left()>old.left()||r.bottom()>old.bottom()||r.right()<old.right()||r.top()<old.top())?1:0;}));
        return result;
    }
    void presented(Placement p){if(p.slot()>0){active[p.slot()-1]=true;show(p.slot()-1,visible);}}
    void commit(java.util.List<Placement> plan){
        java.util.Arrays.fill(previous,null);for(var p:plan)previous[p.slot()]=p.region();
        for(int i=0;i<active.length;i++){active[i]=previous[i+1]!=null;if(!active[i])show(i,false);}
    }
    void count(int next){if(next!=1)throw new IllegalArgumentException("Input shield is one window");
        java.util.Arrays.fill(previous,null);java.util.Arrays.fill(active,false);for(int i=0;i<3;i++)show(i,false);}
    void refresh(){visibility(visible);}
    private void show(int i,boolean next){if(windows[i]!=null&&shown[i]!=next){User32.INSTANCE.ShowWindow(windows[i],next?4:0);shown[i]=next;}}
    void visibility(boolean next){visible=next;for(int i=0;i<windows.length;i++)show(i,visible&&active[i]);}
    public void close(){for(int i=0;i<windows.length;i++)if(windows[i]!=null){User32.INSTANCE.DestroyWindow(windows[i]);windows[i]=null;shown[i]=false;}
        java.util.Arrays.fill(previous,null);java.util.Arrays.fill(active,false);}
}
