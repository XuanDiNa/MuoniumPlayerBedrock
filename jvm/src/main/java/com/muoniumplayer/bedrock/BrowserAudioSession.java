package com.muoniumplayer.bedrock;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/** One owned hidden Chromium, one loopback CDP connection; no general profiles. */
final class BrowserAudioSession implements AutoCloseable, WebSocket.Listener {
    private final ProcessHandle process;
    private final com.sun.jna.platform.win32.WinNT.HANDLE desktop;
    private final Path profile;
    private final ConcurrentHashMap<Integer,CompletableFuture<JsonObject>> calls=new ConcurrentHashMap<>();
    private final AtomicInteger ids=new AtomicInteger();
    private final AtomicInteger documentCommits=new AtomicInteger();
    private final StringBuilder message=new StringBuilder();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
        .proxy(new ProxySelector(){public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI u,SocketAddress a,IOException e){}}).build();
    private volatile WebSocket socket;
    private volatile String pageSession;
    private volatile boolean closed;
    private final AtomicBoolean closing=new AtomicBoolean();
    private final CountDownLatch retired=new CountDownLatch(1);

    static Path browser(){
        for(String[] location:new String[][]{{"PROGRAMFILES","Google/Chrome/Application/chrome.exe"},
                {"PROGRAMFILES(X86)","Microsoft/Edge/Application/msedge.exe"},
                {"PROGRAMFILES","Microsoft/Edge/Application/msedge.exe"},
                {"LOCALAPPDATA","Google/Chrome/Application/chrome.exe"}}){
            String base=System.getenv(location[0]);if(base!=null){Path p=Path.of(base,location[1]);if(Files.isRegularFile(p))return p;}
        }return null;
    }
    BrowserAudioSession(String proxy)throws Exception {this(proxy,false,()->false);}
    BrowserAudioSession(String proxy,boolean completeLogin,java.util.function.BooleanSupplier cancelled)throws Exception {
        Path browser=browser();if(browser==null)throw new IOException("YouTube 官方播放需要 Chrome 或 Edge");
        profile=Files.createTempDirectory(SessionMedia.directory().toPath(),"youtube-browser-");
        int debugPort;
        Started started;
        try {
        try(var listener=new ServerSocket()){
            listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0));debugPort=listener.getLocalPort();
        }
        if(completeLogin&&!copyLoginProfile(profile,cancelled))throw new IOException("请先完成 YouTube 专用浏览器登录");
        var args=new ArrayList<>(List.of(browser.toString(),"--window-position=-32000,-32000","--mute-audio","--no-first-run",
            "--no-default-browser-check","--disable-background-mode","--disable-background-timer-throttling",
            "--disable-renderer-backgrounding","--disable-backgrounding-occluded-windows","--disable-features=CalculateNativeWinOcclusion",
            "--autoplay-policy=no-user-gesture-required",
            // Hidden targets do not commit display frames; permit their CDP input.
            "--allow-pre-commit-input",
            "--remote-debugging-address=127.0.0.1","--remote-debugging-port="+debugPort,"--user-data-dir="+profile));
        if(proxy!=null&&!proxy.isBlank())args.add("--proxy-server=http://"+proxy);
        // A hidden native Chrome window can still acquire foreground ownership.
        // A private, never-switched desktop isolates native activation from the user.
        args.add("about:blank");
        started=startHidden(args);
        }catch(Exception failure){try{SessionMedia.releaseBrowserProfile(profile);}catch(IOException ignored){}throw failure;}
        process=started.process();desktop=started.desktop();
        ProgressiveAudio.transports.add(this);
        try {
            long deadline=System.nanoTime()+15_000_000_000L;int port=0;String browserPath=null;
            while(System.nanoTime()<deadline&&process.isAlive()){
                if(loopbackListenerOwnedBy(debugPort,process.pid()))try{
                    var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+debugPort+"/json/version"))
                        .timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.ofString());
                    if(response.statusCode()==200&&response.body().length()<65536){
                        var version=JsonParser.parseString(response.body()).getAsJsonObject();
                        URI ws=URI.create(version.get("webSocketDebuggerUrl").getAsString());
                        if("ws".equals(ws.getScheme())&&"127.0.0.1".equals(ws.getHost())&&ws.getPort()==debugPort
                                &&ws.getRawUserInfo()==null&&ws.getRawQuery()==null&&ws.getRawFragment()==null
                                &&ws.getRawPath().matches("/devtools/browser/[A-Za-z0-9-]+")
                                &&loopbackListenerOwnedBy(debugPort,process.pid())){
                            port=debugPort;browserPath=ws.getRawPath();break;
                        }
                    }
                }catch(IOException|RuntimeException pending){ /* Wait for this owned browser only. */ }
                Thread.sleep(50);
            }
            if(browserPath==null)throw new IOException("YouTube 专用浏览器未启动");
            URI endpoint=URI.create("ws://127.0.0.1:"+port+browserPath);
            socket=http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3)).buildAsync(endpoint,this).get(4,TimeUnit.SECONDS);
            String targetId=null;
            while(System.nanoTime()<deadline&&process.isAlive()){
                for(var item:browserCall("Target.getTargets",new JsonObject()).getAsJsonArray("targetInfos")){
                    var target=item.getAsJsonObject();
                    if("page".equals(target.get("type").getAsString())&&"about:blank".equals(target.get("url").getAsString())){
                        targetId=target.get("targetId").getAsString();break;
                    }
                }
                if(targetId!=null)break;Thread.sleep(50);
            }
            if(targetId==null)throw new IOException("YouTube 浏览器后台页面不可用");
            var attach=new JsonObject();attach.addProperty("targetId",targetId);attach.addProperty("flatten",true);
            pageSession=browserCall("Target.attachToTarget",attach).get("sessionId").getAsString();
            // Keep this hidden owned tab media-active without changing OS focus.
            var viewport=new JsonObject();viewport.addProperty("width",1280);viewport.addProperty("height",720);
            viewport.addProperty("deviceScaleFactor",1);viewport.addProperty("mobile",false);
            call("Emulation.setDeviceMetricsOverride",viewport);
            var activePage=new JsonObject();activePage.addProperty("enabled",true);
            call("Emulation.setFocusEmulationEnabled",activePage);
            call("Runtime.enable",new JsonObject());call("Page.enable",new JsonObject());
        }catch(Exception failure){close();throw failure;}
    }
    private interface DesktopApi extends com.sun.jna.win32.StdCallLibrary {
        DesktopApi INSTANCE=com.sun.jna.Native.load("user32",DesktopApi.class);
        com.sun.jna.platform.win32.WinNT.HANDLE CreateDesktopW(com.sun.jna.WString name,
            com.sun.jna.Pointer device,com.sun.jna.Pointer mode,int flags,int access,com.sun.jna.Pointer security);
        boolean CloseDesktop(com.sun.jna.platform.win32.WinNT.HANDLE desktop);
    }
    private record Started(ProcessHandle process,com.sun.jna.platform.win32.WinNT.HANDLE desktop){}
    private interface TcpOwnerApi extends com.sun.jna.win32.StdCallLibrary {
        TcpOwnerApi INSTANCE=com.sun.jna.Native.load("iphlpapi",TcpOwnerApi.class);
        int GetExtendedTcpTable(com.sun.jna.Pointer table,com.sun.jna.ptr.IntByReference bytes,
            boolean order,int family,int tableClass,int reserved);
    }
    /** A released ephemeral port may be claimed by another process. Never attach to it. */
    static boolean loopbackListenerOwnedBy(int port,long pid){
        if(port<1||port>65535||pid<1)return false;
        var bytes=new com.sun.jna.ptr.IntByReference();
        int status=TcpOwnerApi.INSTANCE.GetExtendedTcpTable(null,bytes,false,2,3,0);
        if(status!=122||bytes.getValue()<4||bytes.getValue()>1024*1024)return false;
        try(var table=new com.sun.jna.Memory(bytes.getValue())){
            if(TcpOwnerApi.INSTANCE.GetExtendedTcpTable(table,bytes,false,2,3,0)!=0)return false;
            int rows=table.getInt(0);if(rows<0||rows>(table.size()-4)/24)return false;
            for(int row=0;row<rows;row++){
                long offset=4L+24L*row;
                if((table.getByte(offset+4)&255)==127&&table.getByte(offset+5)==0
                        &&table.getByte(offset+6)==0&&table.getByte(offset+7)==1
                        &&((table.getByte(offset+8)&255)<<8|(table.getByte(offset+9)&255))==port
                        &&Integer.toUnsignedLong(table.getInt(offset+20))==pid)return true;
            }
        }return false;
    }
    /** Create only our browser with SW_HIDE; no shell and no visible-first race. */
    private static Started startHidden(List<String> args)throws IOException {
        String name="MuoniumAudio-"+UUID.randomUUID();
        var desktop=DesktopApi.INSTANCE.CreateDesktopW(new com.sun.jna.WString(name),null,null,0,0x01FF,null);
        if(desktop==null)throw new IOException("YouTube background desktop unavailable");
        var startup=new com.sun.jna.platform.win32.WinBase.STARTUPINFO();
        startup.cb=new com.sun.jna.platform.win32.WinDef.DWORD(startup.size());
        startup.lpDesktop="WinSta0\\"+name;
        startup.dwFlags=1; // STARTF_USESHOWWINDOW
        startup.wShowWindow=new com.sun.jna.platform.win32.WinDef.WORD(0); // SW_HIDE
        var info=new com.sun.jna.platform.win32.WinBase.PROCESS_INFORMATION();
        var kernel=com.sun.jna.platform.win32.Kernel32.INSTANCE;
        if(!kernel.CreateProcessW(args.getFirst(),com.sun.jna.Native.toCharArray(commandLine(args)),null,null,false,
                new com.sun.jna.platform.win32.WinDef.DWORD(0x08000000),null,null,startup,info)){ // CREATE_NO_WINDOW
            DesktopApi.INSTANCE.CloseDesktop(desktop);
            throw new IOException("YouTube browser hidden startup failed (Windows "+kernel.GetLastError()+")");
        }
        try{return new Started(ProcessHandle.of(Integer.toUnsignedLong(info.dwProcessId.intValue()))
            .orElseThrow(()->new IOException("YouTube browser exited during startup")),desktop);}
        catch(IOException failed){DesktopApi.INSTANCE.CloseDesktop(desktop);throw failed;}
        finally{kernel.CloseHandle(info.hThread);kernel.CloseHandle(info.hProcess);}
    }
    static String commandLine(List<String> args){
        var line=new StringJoiner(" ");
        for(String arg:args){
            var quoted=new StringBuilder("\"");int slashes=0;
            for(char c:arg.toCharArray()){
                if(c=='\\'){slashes++;continue;}
                quoted.append("\\".repeat(slashes*(c=='\"'?2:1)));slashes=0;
                if(c=='\"')quoted.append('\\');quoted.append(c);
            }
            line.add(quoted.append("\\".repeat(slashes*2)).append('\"'));
        }
        return line.toString();
    }
    /** Snapshot only the mod's idle login profile; Chromium never opens the original. */
    static boolean copyLoginProfile(Path destination,java.util.function.BooleanSupplier cancelled)throws IOException {
        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Login snapshot cancelled");
        Path source=Path.of("MuoniumPlayer/auth/youtube-browser-profile").toAbsolutePath().normalize();
        Path registered=com.muoniumplayer.core.settings.ConfigPaths.YOUTUBE_BROWSER_PROFILE.toPath().toAbsolutePath().normalize();
        if(!source.equals(registered))throw new IOException("Dedicated login profile identity mismatch");
        Path target=destination.toAbsolutePath().normalize();
        if(!target.getParent().equals(SessionMedia.directory().toPath())||!target.getFileName().toString().matches("youtube-browser-[0-9]+"))
            throw new IOException("Login snapshot destination identity mismatch");
        SessionMedia.requirePlainRoot(source);SessionMedia.requirePlainRoot(target);
        if(!Files.isDirectory(source,LinkOption.NOFOLLOW_LINKS))return false;
        requireIdleLogin(source);
        Map<Path,BasicFileAttributes> observed=new HashMap<>();long[] bytes={0};
        Files.walkFileTree(source,new SimpleFileVisitor<>(){
            private void check(Path path)throws IOException {
                if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Login snapshot cancelled");
                if(SessionMedia.isLink(path))throw new IOException("Dedicated login profile contains a reparse point");
            }
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs)throws IOException {
                check(dir);Path relative=source.relativize(dir);
                if(!relative.toString().isEmpty())Files.createDirectory(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException {
                check(file);String name=file.getFileName().toString();
                if(name.equals("DevToolsActivePort")||name.startsWith("Singleton"))return FileVisitResult.CONTINUE;
                if(!attrs.isRegularFile()||observed.size()>=50000||(bytes[0]+=attrs.size())>512L*1024*1024)
                    throw new IOException("Dedicated login snapshot exceeds its working bound");
                observed.put(file,attrs);Files.copy(file,target.resolve(source.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
        requireIdleLogin(source);
        for(var item:observed.entrySet()){
            var after=Files.readAttributes(item.getKey(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            var before=item.getValue();
            if(after.size()!=before.size()||!after.lastModifiedTime().equals(before.lastModifiedTime())||SessionMedia.isLink(item.getKey()))
                throw new IOException("Dedicated login profile changed while copying");
        }
        return true;
    }
    private static void requireIdleLogin(Path source)throws IOException {
        if(com.muoniumplayer.core.ncm.music.YouTubeBrowserLogin.isBusy())throw new IOException("请先关闭专用登录浏览器再播放");
        String identity=source.toString().toLowerCase(Locale.ROOT).replace('/','\\');
        try(var processes=ProcessHandle.allProcesses()){
            if(processes.anyMatch(p->p.info().commandLine().map(c->{String command=c.toLowerCase(Locale.ROOT).replace('/','\\');
                return command.contains("--user-data-dir")&&command.contains(identity);}).orElse(false)))
                throw new IOException("请先关闭专用登录浏览器再播放");
        }
    }
    boolean skipOfferedAd()throws Exception {
        var offered=evaluate("""
            (()=>{
              if(!document.querySelector('.ad-showing,.ad-interrupting'))return {};
              const s=document.querySelector('.ytp-skip-ad-button,.ytp-ad-skip-button-modern,.ytp-ad-skip-button');
              if(!s||s.disabled||s.getAttribute('aria-disabled')==='true')return {};
              for(let e=s;e;e=e.parentElement){const c=getComputedStyle(e);
                if(c.display==='none'||c.visibility!=='visible'||Number(c.opacity)===0)return {};}
              const r=s.getBoundingClientRect(),x=r.x+r.width/2,y=r.y+r.height/2;
              if(r.width<=0||r.height<=0||x<0||y<0||x>=innerWidth||y>=innerHeight)return {};
              const target=document.elementFromPoint(x,y);
              if(!target||!s.contains(target))return {};
              s.focus({preventScroll:true});
              return {focused:document.activeElement===s||s.contains(document.activeElement)};
            })()
            """);
        if(!offered.has("focused")||!offered.get("focused").getAsBoolean())return false;
        // Hidden pages have no painted surface for reliable mouse hit tests.
        // Activate the exposed official control with its Enter action.
        // Trusted CDP input stays in this tab; no OS keyboard or game input.
        for(String type:List.of("rawKeyDown","char","keyUp")) {
            var event=new JsonObject();event.addProperty("type",type);event.addProperty("key","Enter");
            event.addProperty("code","Enter");event.addProperty("windowsVirtualKeyCode",13);
            event.addProperty("text","\r");call("Input.dispatchKeyEvent",event);
        }
        return true;
    }
    JsonObject call(String method,JsonObject params)throws Exception {
        if(pageSession==null)throw new IOException("YouTube browser page unavailable");
        return command(method,params,pageSession);
    }
    private JsonObject browserCall(String method,JsonObject params)throws Exception {return command(method,params,null);}
    private JsonObject command(String method,JsonObject params,String session)throws Exception {
        if(closed||!process.isAlive())throw new InterruptedIOException("YouTube browser released");
        int id=ids.incrementAndGet();var result=new CompletableFuture<JsonObject>();calls.put(id,result);
        JsonObject request=new JsonObject();request.addProperty("id",id);request.addProperty("method",method);request.add("params",params);
        if(session!=null)request.addProperty("sessionId",session);
        try {
            socket.sendText(request.toString(),true).get(3,TimeUnit.SECONDS);
            JsonObject response=result.get(12,TimeUnit.SECONDS);
            if(response.has("error"))throw new IOException("YouTube browser command rejected: "+method);
            return response.getAsJsonObject("result");
        }catch(ExecutionException failed){throw new IOException("YouTube browser transport failed",failed.getCause());}
        finally{calls.remove(id);}
    }
    JsonObject evaluate(String expression)throws Exception {
        var p=new JsonObject();p.addProperty("expression",expression);p.addProperty("returnByValue",true);
        p.addProperty("awaitPromise",true);p.addProperty("timeout",10000);
        var result=call("Runtime.evaluate",p);
        if(result.has("exceptionDetails"))throw new IOException("YouTube page audio bridge unavailable");
        JsonObject object=result.getAsJsonObject("result");
        return object!=null&&object.has("value")&&object.get("value").isJsonObject()?object.getAsJsonObject("value"):new JsonObject();
    }
    void cookies(Map<String,String> cookies)throws Exception {
        if(cookies.isEmpty())return;
        var values=new JsonArray();for(var entry:cookies.entrySet()){
            var c=new JsonObject();c.addProperty("name",entry.getKey());c.addProperty("value",entry.getValue());
            c.addProperty("domain",".youtube.com");c.addProperty("path","/");c.addProperty("secure",true);values.add(c);
        }
        var params=new JsonObject();params.add("cookies",values);call("Network.setCookies",params);
    }
    /** A copied encrypted profile can load without its login cookies. Preserve
     * an existing browser identity; otherwise hand off our exported cookies and
     * let the official page validate them. Never modify the saved login profile. */
    boolean restoreMissingLogin(Map<String,String> imported)throws Exception {
        var identity=Set.of("SID","__Secure-1PSID","__Secure-3PSID");
        if(identity.stream().noneMatch(n->imported.containsKey(n)&&!imported.get(n).isBlank()))return false;
        var params=new JsonObject();var urls=new JsonArray();urls.add("https://www.youtube.com/");params.add("urls",urls);
        var stored=call("Network.getCookies",params).getAsJsonArray("cookies");
        if(stored!=null)for(var item:stored){var c=item.getAsJsonObject();
            if(identity.contains(c.get("name").getAsString())&&!c.get("value").getAsString().isBlank())return false;
        }
        cookies(imported);
        System.out.println("YOUTUBE_BROWSER state=login-snapshot cookieRestore=true");
        return true;
    }
    void navigate(URI uri)throws Exception {
        int before=documentCommits.get();
        var p=new JsonObject();p.addProperty("url",uri.toString());
        var navigation=call("Page.navigate",p);
        if(navigation.has("errorText"))throw new IOException("Official page navigation failed");
        long deadline=System.nanoTime()+25_000_000_000L;
        while(System.nanoTime()<deadline){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Browser navigation cancelled");
            if(documentCommits.get()>before){
                try {
                    var state=evaluate("({url:location.href,ready:document.readyState})");
                    if(state.has("url")&&sameDocument(uri,state.get("url").getAsString())
                            &&state.has("ready")&&!"loading".equals(state.get("ready").getAsString()))return;
                }catch(IOException replaced){if(closed||!process.isAlive())throw replaced;}
            }
            Thread.sleep(50);
        }
        throw new IOException("Official page document did not become ready");
    }
    /** Preserve the requested document identity while allowing display parameters added by its server. */
    static boolean sameDocument(URI requested,String location){
        try {
            URI actual=URI.create(location);
            if(!Objects.equals(requested.getScheme(),actual.getScheme())
                    ||!Objects.equals(requested.getHost(),actual.getHost())
                    ||requested.getPort()!=actual.getPort()
                    ||!Objects.equals(requested.getRawUserInfo(),actual.getRawUserInfo())
                    ||!Objects.equals(requested.getRawPath(),actual.getRawPath()))return false;
            var expected=queryIdentity(requested);var observed=queryIdentity(actual);
            if(expected.isEmpty())return observed.isEmpty();
            return expected.entrySet().stream().allMatch(e->e.getValue().equals(observed.get(e.getKey())));
        }catch(IllegalArgumentException invalid){return false;}
    }
    private static Map<String,List<String>> queryIdentity(URI uri){
        Map<String,List<String>> result=new HashMap<>();
        String raw=uri.getRawQuery();
        if(raw!=null&&!raw.isEmpty())for(String item:raw.split("&")){
            String[] pair=item.split("=",2);
            String key=URLDecoder.decode(pair[0],java.nio.charset.StandardCharsets.UTF_8);
            String value=pair.length>1?URLDecoder.decode(pair[1],java.nio.charset.StandardCharsets.UTF_8):"";
            result.computeIfAbsent(key,ignored->new ArrayList<>()).add(value);
        }
        return result;
    }
    long pid(){return process.pid();}
    public void onOpen(WebSocket ws){ws.request(1);}
    public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last){
        try {
            if(message.length()+data.length()>4*1024*1024)throw new IOException("Browser packet bound");
            message.append(data);
            if(last){JsonObject packet=JsonParser.parseString(message.toString()).getAsJsonObject();message.setLength(0);
                if(packet.has("sessionId")&&packet.get("sessionId").getAsString().equals(pageSession)
                        &&packet.has("method")&&"Page.frameNavigated".equals(packet.get("method").getAsString())){
                    var frame=packet.getAsJsonObject("params").getAsJsonObject("frame");
                    if(!frame.has("parentId"))documentCommits.incrementAndGet();
                }
                if(packet.has("id")){var pending=calls.get(packet.get("id").getAsInt());if(pending!=null)pending.complete(packet);}}
            ws.request(1);
        }catch(Exception invalid){onError(ws,invalid);ws.abort();}return null;
    }
    public CompletionStage<?> onClose(WebSocket ws,int status,String reason){onError(ws,new IOException("Browser closed"));return null;}
    public void onError(WebSocket ws,Throwable error){for(var future:calls.values())future.completeExceptionally(error);}
    private boolean awaitExit(long millis)throws InterruptedException {
        if(!process.isAlive())return true;
        try{process.onExit().get(millis,TimeUnit.MILLISECONDS);return true;}
        catch(TimeoutException busy){return false;}
        catch(ExecutionException failed){return !process.isAlive();}
    }
    public void close(){
        if(!closing.compareAndSet(false,true)){
            // Wait only for process retirement, never for SessionMedia's lock.
            // Session shutdown may already hold that lock while a decoder closes.
            try{retired.await(4,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            return;
        }
        var descendants=process.descendants().toList();
        // Closing the dedicated browser also retires its renderer processes.
        try{if(socket!=null&&process.isAlive())browserCall("Browser.close",new JsonObject());}catch(Exception ignored){}
        closed=true;if(socket!=null)socket.abort();onError(socket,new IOException("Browser released"));
        try{if(!awaitExit(1500))process.destroy();}catch(InterruptedException e){Thread.currentThread().interrupt();process.destroy();}
        if(process.isAlive())process.destroyForcibly();for(var child:descendants)if(child.isAlive())child.destroy();
        http.shutdownNow();
        DesktopApi.INSTANCE.CloseDesktop(desktop);
        ProgressiveAudio.transports.remove(this);
        retired.countDown();
        try{if(awaitExit(1000))SessionMedia.releaseBrowserProfile(profile);}
        catch(InterruptedException e){Thread.currentThread().interrupt();}catch(IOException busy){ /* session cleanup retries */ }
    }
}
