package com.muoniumplayer.bedrock;

import com.google.gson.*;
import java.nio.file.*;
import java.net.URI;
import java.util.*;

/** Explicit manual service probe. Never included in ordinary offline tests. */
public final class YouTubeBrowserPlaybackProbe {
    public static void main(String[] args)throws Exception {
        if(args.length<3)throw new IllegalArgumentException("videoId appOwnedAuthFile proxyEndpoint [seekSeconds]");
        String id=args[0];var auth=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
        var cookies=new HashMap<String,String>();for(var e:auth.getAsJsonObject("cookies").entrySet())cookies.put(e.getKey(),e.getValue().getAsString());
        boolean complete=args.length>4&&args[4].equals("--complete-profile");
        try(var source=new YouTubeBrowserAudio.Source(URI.create(YouTubeBrowserAudio.watchUrl(id)),id,args[2],cookies,()->false,complete)){
            int seek=args.length>3?Integer.parseInt(args[3]):-1;if(seek>=0)source.request((long)seek*source.rate());
            long start=System.nanoTime();int prior=-1,windowChecks=0;float[] block;double totalEnergy=0;long samples=0,last=0;
            requireBackground(source.session.pid());windowChecks++;
            System.out.println("OFFICIAL_SESSION "+source.session.evaluate("({signedIn:window.ytcfg?.get?.('LOGGED_IN')===true})"));
            boolean at64=false,at76=false,at120=false,at195=false;try { while((block=source.next())!=null){
                double energy=0;for(float x:block)energy+=x*x;totalEnergy+=energy;samples+=block.length;last=source.position()+block.length/2;
                int second=(int)(source.position()/source.rate());
                if(second==64&&energy>.1)at64=true;if(second==76&&energy>.1)at76=true;
                if(second==120&&energy>.1)at120=true;if(second==195&&energy>.1)at195=true;
                if(second/15!=prior){prior=second/15;requireBackground(source.session.pid());windowChecks++;
                    System.out.println("OFFICIAL_AUDIO seconds="+second+" pcmEnergy="+Math.round(energy)+" elapsedMs="+(System.nanoTime()-start)/1_000_000);}
            }}catch(Exception failure){
                try{System.out.println("OFFICIAL_PAGE_DIAGNOSTIC "+source.session.evaluate("(()=>{let p=document.getElementById('movie_player'),v=document.querySelector('video');return {playerState:p?.getPlayerState?.(),status:window.ytInitialPlayerResponse?.playabilityStatus?.status,sourcePresent:!!v?.getAttribute('src'),mediaError:v?.error?.code||0,online:navigator.onLine,videoMatched:p?.getVideoData?.()?.video_id==='"+id+"',errorVisible:!!document.querySelector('.ytp-error-content-wrap'),screenError:document.querySelector('.ytp-error-content-wrap')?.innerText?.slice(0,500),bodyReady:document.readyState,officialHost:location.hostname,bridgePresent:!!window.__muAudio};})()"));}catch(Exception ignored){}
                throw failure;
            }
            System.out.println("OFFICIAL_AUDIO ended=true frames="+last+" declared="+source.frames()+" audible64="+at64+" audible76="+at76+" totalEnergy="+Math.round(totalEnergy)+" samples="+samples);
            System.out.println("OFFICIAL_AUDIO audible120="+at120+" audible195="+at195);
            requireBackground(source.session.pid());windowChecks++;
            System.out.println("OFFICIAL_BACKGROUND visibleWindows=0 checks="+windowChecks);
            if(last<source.frames()-6*4096L||totalEnergy<1||samples==0
                    ||(seek<0&&(!at64||!at76))||(seek==120&&(!at120||!at195)))
                throw new IllegalStateException("Official service probe did not satisfy PCM/EOF coverage");
        }finally{SessionMedia.close();}
    }
    private static void requireBackground(long owner){
        var count=new java.util.concurrent.atomic.AtomicInteger();
        com.sun.jna.platform.win32.User32.INSTANCE.EnumWindows((window,ignored)->{
            var pid=new com.sun.jna.ptr.IntByReference();
            com.sun.jna.platform.win32.User32.INSTANCE.GetWindowThreadProcessId(window,pid);
            if(Integer.toUnsignedLong(pid.getValue())==owner
                    &&com.sun.jna.platform.win32.User32.INSTANCE.IsWindowVisible(window)){
                count.incrementAndGet();var bounds=new com.sun.jna.platform.win32.WinDef.RECT();
                com.sun.jna.platform.win32.User32.INSTANCE.GetWindowRect(window,bounds);
                char[] type=new char[256];com.sun.jna.platform.win32.User32.INSTANCE.GetClassName(window,type,type.length);
                System.out.println("OFFICIAL_VISIBLE_WINDOW class="+com.sun.jna.Native.toString(type)
                    +" x="+bounds.left+" y="+bounds.top+" w="+(bounds.right-bounds.left)+" h="+(bounds.bottom-bounds.top));
            }
            return true;
        },null);
        if(count.get()!=0)throw new IllegalStateException("Playback browser exposed a window");
    }
}
