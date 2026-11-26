package com.muoniumplayer.bedrock;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.regex.Pattern;
import com.sun.jna.*;
import com.sun.jna.win32.*;
import com.sun.jna.platform.win32.Kernel32;

/** Read-only WinHTTP access to the current user's manual/PAC/WPAD proxy policy. */
final class WindowsRoomProxy {
    record Config(boolean autoDetect,String pac,String proxy,String bypass){}
    interface Api extends StdCallLibrary {
        Api INSTANCE=Native.load("winhttp",Api.class,W32APIOptions.UNICODE_OPTIONS);
        boolean WinHttpGetIEProxyConfigForCurrentUser(IEConfig config);
        Pointer WinHttpOpen(WString agent,int access,WString proxy,WString bypass,int flags);
        boolean WinHttpSetTimeouts(Pointer session,int resolve,int connect,int send,int receive);
        boolean WinHttpGetProxyForUrl(Pointer session,WString url,AutoOptions options,ProxyInfo info);
        boolean WinHttpCloseHandle(Pointer session);
    }
    @Structure.FieldOrder({"autoDetect","pac","proxy","bypass"})
    public static class IEConfig extends Structure {public int autoDetect;public Pointer pac,proxy,bypass;}
    @Structure.FieldOrder({"flags","detectFlags","url","reserved","reservedFlags","autoLogon"})
    public static class AutoOptions extends Structure {public int flags,detectFlags;public WString url;public Pointer reserved;public int reservedFlags,autoLogon;}
    @Structure.FieldOrder({"access","proxy","bypass"})
    public static class ProxyInfo extends Structure {public int access;public Pointer proxy,bypass;}
    private static String text(Pointer p){return p==null?"":p.getWideString(0);}
    private static void free(Pointer p){if(p!=null)Kernel32.INSTANCE.GlobalFree(p);}
    static Config read() throws IOException {
        if(!Platform.isWindows())throw new IOException("系统代理仅支持 Windows；请改用自定义代理");
        var c=new IEConfig();
        try {
            if(!Api.INSTANCE.WinHttpGetIEProxyConfigForCurrentUser(c))throw new IOException("无法读取 Windows 系统代理");
            return new Config(c.autoDetect!=0,text(c.pac),text(c.proxy),text(c.bypass));
        }finally{free(c.pac);free(c.proxy);free(c.bypass);}
    }
    static RoomProxySettings.Value resolve(URI endpoint) throws IOException {
        Config c=read();
        if(!c.autoDetect()&&c.pac().isEmpty())return parse(c.proxy(),c.bypass(),endpoint);
        Pointer session=Api.INSTANCE.WinHttpOpen(new WString("MuoniumRoom/1"),1,null,null,0);
        if(session==null)throw new IOException("系统代理会话创建失败");
        var info=new ProxyInfo();
        try {
            Api.INSTANCE.WinHttpSetTimeouts(session,2000,2000,2000,2000);
            var options=new AutoOptions();options.flags=c.pac().isEmpty()?1:2;
            options.detectFlags=c.pac().isEmpty()?3:0;options.url=c.pac().isEmpty()?null:new WString(c.pac());
            // No ambient Windows credentials are sent to a PAC host.
            options.autoLogon=0;
            String scheme="wss".equals(endpoint.getScheme())?"https":"http";
            String url;
            try{url=new URI(scheme,null,endpoint.getHost(),endpoint.getPort(),"/",null,null).toASCIIString();}
            catch(URISyntaxException e){throw new IOException("房间地址无效");}
            if(!Api.INSTANCE.WinHttpGetProxyForUrl(session,new WString(url),options,info))
                throw new IOException("系统自动代理解析失败；请使用自定义代理（未改走直连）");
            if(info.access==1)return direct();
            if(info.access!=3)throw new IOException("系统代理类型不可用");
            return parse(text(info.proxy),text(info.bypass),endpoint);
        }finally{free(info.proxy);free(info.bypass);Api.INSTANCE.WinHttpCloseHandle(session);}
    }
    static RoomProxySettings.Value parse(String proxy,String bypass,URI endpoint) throws IOException {
        String host=endpoint.getHost();if(host==null)throw new IOException("房间地址无效");
        for(String pattern:bypass.split("[;,\\s]+")) {
            if(pattern.isBlank())continue;
            if(pattern.equalsIgnoreCase("<local>")&&!host.contains(".")&&!host.contains(":"))return direct();
            String regex=Arrays.stream(pattern.split("\\*",-1)).map(Pattern::quote).reduce((a,b)->a+".*"+b).orElse("");
            if(host.matches("(?i)"+regex))return direct();
        }
        String wanted="wss".equals(endpoint.getScheme())?"https":"http",chosen=null,socks=null;
        boolean mapped=proxy.contains("=");
        for(String item:proxy.split("[;\\s]+")) {
            if(item.isBlank())continue;
            int at=item.indexOf('=');
            if(at>=0){String key=item.substring(0,at).toLowerCase(Locale.ROOT);
                if(key.equals(wanted)&&chosen==null)chosen=item.substring(at+1);
                if(key.equals("socks")&&socks==null)socks=item.substring(at+1);
            }else if(!mapped&&chosen==null)chosen=item;
        }
        var mode=RoomProxySettings.Mode.HTTP;
        if(chosen==null&&socks!=null){chosen=socks;mode=RoomProxySettings.Mode.SOCKS5;}
        if(chosen==null)return direct();
        if(chosen.startsWith("http://"))chosen=chosen.substring(7);
        else if(chosen.startsWith("socks://")){chosen=chosen.substring(8);mode=RoomProxySettings.Mode.SOCKS5;}
        else if(chosen.startsWith("socks5://")){chosen=chosen.substring(9);mode=RoomProxySettings.Mode.SOCKS5;}
        int colon=chosen.lastIndexOf(':');
        try {
            if(colon<=0)throw new IllegalArgumentException();
            return new RoomProxySettings.Value(mode,chosen.substring(0,colon),Integer.parseInt(chosen.substring(colon+1)));
        }catch(IllegalArgumentException e){throw new IOException("系统代理地址不可用；请使用无认证 HTTP 或 SOCKS5 代理");}
    }
    private static RoomProxySettings.Value direct(){return new RoomProxySettings.Value(RoomProxySettings.Mode.DIRECT,"",0);}
}
