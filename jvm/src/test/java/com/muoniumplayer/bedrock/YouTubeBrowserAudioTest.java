package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Chromium and our synthetic origin. No external media or user profile. */
class YouTubeBrowserAudioTest {
    private interface DesktopFixtureApi extends com.sun.jna.win32.StdCallLibrary {
        DesktopFixtureApi INSTANCE=com.sun.jna.Native.load("user32",DesktopFixtureApi.class);
        boolean GetUserObjectInformationW(com.sun.jna.platform.win32.WinNT.HANDLE desktop,int index,
            com.sun.jna.Pointer value,int bytes,com.sun.jna.ptr.IntByReference needed);
        com.sun.jna.platform.win32.WinNT.HANDLE OpenInputDesktop(int flags,boolean inherit,int access);
        com.sun.jna.platform.win32.WinNT.HANDLE OpenDesktopW(com.sun.jna.WString name,int flags,boolean inherit,int access);
        boolean CloseDesktop(com.sun.jna.platform.win32.WinNT.HANDLE desktop);
    }
    private static String desktopName(com.sun.jna.platform.win32.WinNT.HANDLE desktop){
        try(var value=new com.sun.jna.Memory(512)){
            assertTrue(DesktopFixtureApi.INSTANCE.GetUserObjectInformationW(desktop,2,value,512,new com.sun.jna.ptr.IntByReference()));
            return value.getWideString(0);
        }
    }
    @Test @Timeout(20) void playbackDesktopMustStaySeparateAndBeReleasedOnClose()throws Exception {
        String name;var input=DesktopFixtureApi.INSTANCE.OpenInputDesktop(0,false,1);assertNotNull(input);
        try(var session=new BrowserAudioSession(null)){
            var field=BrowserAudioSession.class.getDeclaredField("desktop");field.setAccessible(true);
            name=desktopName((com.sun.jna.platform.win32.WinNT.HANDLE)field.get(session));
            assertTrue(name.startsWith("MuoniumAudio-"));
            assertNotEquals(desktopName(input),name,"Background playback must never join or switch the input desktop");
        }finally{DesktopFixtureApi.INSTANCE.CloseDesktop(input);}
        long deadline=System.nanoTime()+2_000_000_000L;boolean released=false;
        do {
            var remaining=DesktopFixtureApi.INSTANCE.OpenDesktopW(new com.sun.jna.WString(name),0,false,1);
            if(remaining==null){released=true;break;}DesktopFixtureApi.INSTANCE.CloseDesktop(remaining);Thread.sleep(20);
        }while(System.nanoTime()<deadline);
        assertTrue(released,"Closing playback must release its private desktop and browser windows");
    }
    @Test void debuggingConnectionMustBelongToTheExpectedLoopbackProcess()throws Exception {
        try(var listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
            assertTrue(BrowserAudioSession.loopbackListenerOwnedBy(listener.getLocalPort(),ProcessHandle.current().pid()));
            assertFalse(BrowserAudioSession.loopbackListenerOwnedBy(listener.getLocalPort(),ProcessHandle.current().parent().orElseThrow().pid()));
            assertFalse(BrowserAudioSession.loopbackListenerOwnedBy(0,ProcessHandle.current().pid()));
        }
    }
    @Test @Timeout(20) void playbackBrowserMustNeverBecomeTheWindowsForegroundOwner()throws Exception {
        var running=new java.util.concurrent.atomic.AtomicBoolean(true);
        var activations=new java.util.concurrent.atomic.AtomicInteger();
        var watcher=new Thread(()->{
            com.sun.jna.platform.win32.WinDef.HWND previous=null;
            while(running.get())try {
                var hwnd=com.sun.jna.platform.win32.User32.INSTANCE.GetForegroundWindow();
                if(!java.util.Objects.equals(previous,hwnd)){
                    previous=hwnd;var pid=new com.sun.jna.ptr.IntByReference();
                    com.sun.jna.platform.win32.User32.INSTANCE.GetWindowThreadProcessId(hwnd,pid);
                    var candidate=ProcessHandle.of(Integer.toUnsignedLong(pid.getValue()));
                    if(candidate.isPresent()){
                        var process=candidate.get();
                        for(int depth=0;depth<12;depth++){
                            var parent=process.parent();if(parent.isEmpty())break;process=parent.get();
                            if(process.pid()==ProcessHandle.current().pid()){activations.incrementAndGet();break;}
                        }
                    }
                }
                Thread.sleep(2);
            }catch(InterruptedException stopped){return;}
        },"fixture-foreground-observer");
        watcher.setDaemon(true);watcher.start();
        try(var origin=new Origin();var session=new BrowserAudioSession(null)){
            session.navigate(origin.uri());
            var activatable=new java.util.concurrent.atomic.AtomicInteger();
            com.sun.jna.platform.win32.User32.INSTANCE.EnumWindows((hwnd,unused)->{
                var pid=new com.sun.jna.ptr.IntByReference();
                com.sun.jna.platform.win32.User32.INSTANCE.GetWindowThreadProcessId(hwnd,pid);
                if(Integer.toUnsignedLong(pid.getValue())==session.pid()){
                    char[] type=new char[256];com.sun.jna.platform.win32.User32.INSTANCE.GetClassName(hwnd,type,type.length);
                    if("Chrome_WidgetWin_1".equals(com.sun.jna.Native.toString(type))
                            &&(com.sun.jna.platform.win32.User32.INSTANCE.GetWindowLong(hwnd,-20)&0x08000000)==0)activatable.incrementAndGet();
                }return true;
            },null);
            assertEquals(0,activatable.get(),"Hidden playback must not retain a native browser window capable of activation");
            session.evaluate("(()=>{window.focus();document.querySelector('video')?.focus();return {};})()");
            Thread.sleep(150);
        }finally{running.set(false);watcher.join(1000);}
        assertEquals(0,activations.get(),"A hidden playback window must never take foreground ownership from the user");
    }
    @Test @Timeout(20) void missingSnapshotAuthenticationCanBeRestoredWithoutOverwritingBrowserIdentity()throws Exception {
        var method=BrowserAudioSession.class.getDeclaredMethod("restoreMissingLogin",java.util.Map.class);method.setAccessible(true);
        try(var session=new BrowserAudioSession(null)){
            var first=java.util.Map.of("SID","owned-dummy-browser-session","__Secure-3PAPISID","owned-dummy-api-cookie");
            assertEquals(true,method.invoke(session,first));
            var other=java.util.Map.of("SID","different-dummy-session","__Secure-3PAPISID","different-dummy-api");
            assertEquals(false,method.invoke(session,other),"An existing browser identity must not be replaced by API cookies");
            var query=new com.google.gson.JsonObject();var urls=new com.google.gson.JsonArray();urls.add("https://www.youtube.com/");query.add("urls",urls);
            var cookies=session.call("Network.getCookies",query).getAsJsonArray("cookies");
            var observed=new java.util.HashMap<String,String>();
            for(var c:cookies){var value=c.getAsJsonObject();observed.put(value.get("name").getAsString(),value.get("value").getAsString());}
            assertEquals(first.get("SID"),observed.get("SID"));assertEquals(first.get("__Secure-3PAPISID"),observed.get("__Secure-3PAPISID"));
        }
    }
    @Test void navigationIdentityPreservesOriginPathAndEveryRequestedQueryValue(){
        URI requested=URI.create("https://www.youtube.com/watch?v=owned-video&mode=audio");
        assertTrue(BrowserAudioSession.sameDocument(requested,"https://www.youtube.com/watch?mode=audio&v=owned-video&themeRefresh=1"));
        for(String url:java.util.List.of(
                "https://other.example/watch?v=owned-video&mode=audio",
                "http://www.youtube.com/watch?v=owned-video&mode=audio",
                "https://www.youtube.com:444/watch?v=owned-video&mode=audio",
                "https://www.youtube.com/other?v=owned-video&mode=audio",
                "https://www.youtube.com/watch?v=other-video&mode=audio",
                "https://www.youtube.com/watch?v=owned-video&v=other-video&mode=audio",
                "https://www.youtube.com/watch?v=owned-video&mode=other",
                "https://www.youtube.com/watch?v=owned-video",
                "https://www.youtube.com/watch?mode=audio",
                "https://user@www.youtube.com/watch?v=owned-video&mode=audio"))
            assertFalse(BrowserAudioSession.sameDocument(requested,url));
    }
    @Test @Timeout(40) void navigationAcceptsSameVideoWithServerAddedDisplayParameters()throws Exception {
        var origin=redirectOrigin("/watch?v=owned-video&themeRefresh=1");origin.start();
        try(var session=new BrowserAudioSession(null)) {
            session.navigate(URI.create("http://127.0.0.1:"+origin.getAddress().getPort()+"/watch?v=owned-video"));
            var page=session.evaluate("({sameVideo:new URL(location.href).searchParams.get('v')==='owned-video',ready:document.readyState})");
            assertTrue(page.get("sameVideo").getAsBoolean());
            assertNotEquals("loading",page.get("ready").getAsString());
        }finally{origin.stop(0);}
    }
    @Test @Timeout(25) void navigationCannotAcceptChangedOrAmbiguousVideoIdentity()throws Exception {
        for(String destination:java.util.List.of("/watch?v=other-video&themeRefresh=1","/watch?v=owned-video&v=other-video&themeRefresh=1")) {
            var origin=redirectOrigin(destination);origin.start();
            var worker=Executors.newSingleThreadExecutor();
            try(var session=new BrowserAudioSession(null)) {
                var pending=worker.submit(()->{session.navigate(URI.create("http://127.0.0.1:"+origin.getAddress().getPort()+"/watch?v=owned-video"));return true;});
                try{assertThrows(TimeoutException.class,()->pending.get(2,TimeUnit.SECONDS),"A different video must not be considered our ready document");}
                finally{pending.cancel(true);}
            }finally{worker.shutdownNow();origin.stop(0);}
        }
    }
    private static HttpServer redirectOrigin(String destination)throws Exception {
        var origin=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        origin.createContext("/",e->{try(e){
            if(!e.getRequestURI().getRawQuery().contains("themeRefresh")) {
                e.getResponseHeaders().set("Location",destination);e.sendResponseHeaders(302,-1);return;
            }
            byte[] html="<html><body>Owned ready document</body></html>".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200,html.length);e.getResponseBody().write(html);
        }});return origin;
    }
    @Test void hiddenStartupMustPreserveWindowsArgumentsWithoutAShell()throws Exception {
        var args=java.util.List.of("C:\\Program Files\\Browser\\chrome.exe","", "a b", "a\"b", "a\\", "a\\\\\"b", "(x)&$");
        java.lang.reflect.Method encode;
        try{encode=BrowserAudioSession.class.getDeclaredMethod("commandLine",java.util.List.class);}
        catch(NoSuchMethodException missing){fail("Hidden native startup needs Windows argument quoting");return;}
        encode.setAccessible(true);
        String encoded=(String)encode.invoke(null,args);
        assertArrayEquals(args.toArray(String[]::new),com.sun.jna.platform.win32.Shell32Util.CommandLineToArgv(encoded));
    }
    @Test @Timeout(20) void browserMustRemainWithoutVisibleWindowsBeforeAndDuringPageLoading()throws Exception {
        try(var origin=new Origin();var session=new BrowserAudioSession(null)) {
            assertEquals(0,visibleWindows(session.pid()),"The playback browser must not expose a window before audio is ready");
            session.navigate(origin.uri());
            var page=session.evaluate("({title:document.title,video:!!document.querySelector('video'),width:innerWidth})");
            assertTrue(page.get("video").getAsBoolean());
            assertTrue(page.get("width").getAsInt()>0,"The owned page still needs a media-capable viewport");
            for(int i=0;i<20;i++) {
                assertEquals(0,visibleWindows(session.pid()),"Navigation must keep the playback browser out of taskbar/Alt-Tab");
                Thread.sleep(20);
            }
        }
    }
    static int visibleWindows(long owner) {
        var count=new java.util.concurrent.atomic.AtomicInteger();
        com.sun.jna.platform.win32.User32.INSTANCE.EnumWindows((window,ignored)->{
            var pid=new com.sun.jna.ptr.IntByReference();
            com.sun.jna.platform.win32.User32.INSTANCE.GetWindowThreadProcessId(window,pid);
            if(Integer.toUnsignedLong(pid.getValue())==owner
                    &&com.sun.jna.platform.win32.User32.INSTANCE.IsWindowVisible(window))count.incrementAndGet();
            return true;
        },null);
        return count.get();
    }
    @Test @Timeout(15) void officialSkipUsesOwnedPageInputOnlyForAnExposedEnabledControl()throws Exception {
        java.lang.reflect.Method skip;
        try{skip=BrowserAudioSession.class.getDeclaredMethod("skipOfferedAd");}
        catch(NoSuchMethodException missing){fail("Script element.click does not activate the offered official skip control");return;}
        skip.setAccessible(true);
        var origin=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        origin.createContext("/",e->{try(e){
            byte[] page=("<body class='ad-showing'><button class='ytp-skip-ad-button' onclick='if(event.isTrusted)window.accepted=(window.accepted||0)+1'>Skip</button></body>").getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200,page.length);e.getResponseBody().write(page);
        }});origin.start();
        try(var session=new BrowserAudioSession(null)) {
            session.navigate(URI.create("http://127.0.0.1:"+origin.getAddress().getPort()+"/"));
            assertEquals(true,skip.invoke(session));
            assertEquals(1,session.evaluate("({n:window.accepted||0})").get("n").getAsInt());
            session.evaluate("(()=>{document.querySelector('button').disabled=true;return {};})()");
            assertEquals(false,skip.invoke(session));
            session.evaluate("(()=>{let s=document.querySelector('button');s.disabled=false;s.style.visibility='hidden';return {};})()");
            assertEquals(false,skip.invoke(session));
            session.evaluate("(()=>{let s=document.querySelector('button');s.style.visibility='visible';document.body.insertAdjacentHTML('beforeend','<div style=\"position:fixed;inset:0;z-index:100\"></div>');return {};})()");
            assertEquals(false,skip.invoke(session),"A covered control is not offered for clicking");
            assertEquals(1,session.evaluate("({n:window.accepted||0})").get("n").getAsInt());
        }finally{origin.stop(0);}
    }
    @Test @Timeout(15) void navigationMustWaitForTheRequestedDocumentBeforeReadingItsPlayer()throws Exception {
        var origin=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        origin.createContext("/",exchange->{try(exchange){
            boolean delayed=exchange.getRequestURI().getPath().equals("/new");
            byte[] html=("<html><body>"+(delayed?"<script src='/slow.js'></script>":"")+"<script>window.pageOwner='"+(delayed?"new":"old")+"';</script></body></html>").getBytes(StandardCharsets.UTF_8);
            if(exchange.getRequestURI().getPath().equals("/slow.js")){
                try{Thread.sleep(700);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                html="void 0;".getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(200,html.length);exchange.getResponseBody().write(html);
        }});origin.start();
        try(var session=new BrowserAudioSession(null)){
            session.navigate(URI.create("http://127.0.0.1:"+origin.getAddress().getPort()+"/old"));
            Thread.sleep(150);
            session.navigate(URI.create("http://127.0.0.1:"+origin.getAddress().getPort()+"/new"));
            var page=session.evaluate("({owner:window.pageOwner||'',ready:document.readyState})");
            assertEquals("new",page.get("owner").getAsString(),"Navigate must not return an old or partially initialized document");
            assertNotEquals("loading",page.get("ready").getAsString());
        }finally{origin.stop(0);}
    }
    @Test void dedicatedLoginStorageMustSupportWindowsLongPaths()throws Exception {
        var directory=java.nio.file.Files.createTempDirectory(SessionMedia.directory().toPath(),"youtube-browser-");
        try {
            var nested=java.nio.file.Files.createDirectories(directory.resolve("a".repeat(80)).resolve("b".repeat(80)));
            var file=java.nio.file.Files.writeString(nested.resolve("state.fixture"),"synthetic storage");
            assertTrue(file.toString().length()>260);
            assertFalse(SessionMedia.isLink(file),"A plain long-path file must not be mistaken for an unavailable/reparse path");
        }finally{SessionMedia.releaseBrowserProfile(directory);}
        assertFalse(java.nio.file.Files.exists(directory),"Long-path profile cleanup must also complete");
    }
    @Test void completeLoginSnapshotMustBeIsolatedCancellableAndRestrictedToTheAppProfile()throws Exception {
        java.lang.reflect.Method copy;
        try{copy=BrowserAudioSession.class.getDeclaredMethod("copyLoginProfile",java.nio.file.Path.class,java.util.function.BooleanSupplier.class);}
        catch(NoSuchMethodException missing){fail("The official-page transport still discards the complete dedicated login session");return;}
        copy.setAccessible(true);
        var root=java.nio.file.Path.of("MuoniumPlayer/auth/youtube-browser-profile").toAbsolutePath();
        assertFalse(java.nio.file.Files.exists(root),"The fixture must never replace an existing profile");
        var destination=java.nio.file.Files.createTempDirectory(SessionMedia.directory().toPath(),"youtube-browser-");
        try {
            java.nio.file.Files.createDirectories(root.resolve("Default/Local Storage"));
            java.nio.file.Files.writeString(root.resolve("Local State"),"synthetic login state");
            java.nio.file.Files.writeString(root.resolve("Default/Local Storage/account.fixture"),"synthetic storage");
            assertTrue((boolean)copy.invoke(null,destination,(java.util.function.BooleanSupplier)()->false));
            assertEquals("synthetic login state",java.nio.file.Files.readString(destination.resolve("Local State")));
            assertEquals("synthetic storage",java.nio.file.Files.readString(destination.resolve("Default/Local Storage/account.fixture")));
            java.nio.file.Files.writeString(destination.resolve("Local State"),"changed only in copy");
            assertEquals("synthetic login state",java.nio.file.Files.readString(root.resolve("Local State")));
            var cancelled=assertThrows(java.lang.reflect.InvocationTargetException.class,()->copy.invoke(null,destination,(java.util.function.BooleanSupplier)()->true));
            assertInstanceOf(java.io.InterruptedIOException.class,cancelled.getCause());
            var outside=assertThrows(java.lang.reflect.InvocationTargetException.class,()->copy.invoke(null,root,(java.util.function.BooleanSupplier)()->false));
            assertInstanceOf(java.io.IOException.class,outside.getCause());
        }finally{
            try(var files=java.nio.file.Files.walk(root)){for(var p:files.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.delete(p);}
            SessionMedia.releaseBrowserProfile(destination);
        }
    }
    @Test void verifiedOfficialRouteIsSelectedByDefaultButCanBeDisabledAndNeverRunsInOfflineTests(){
        String key="muonium.youtube.experimentalBrowserAudio",offline="muonium.bedrock.offlineTest";
        String prior=System.getProperty(key),priorOffline=System.getProperty(offline);
        try {
            System.clearProperty(key);System.setProperty(offline,"false");
            assertEquals(BrowserAudioSession.browser()!=null,YouTubeBrowserAudio.preferred(),"Verified official transport must replace the rejected direct route when Chromium is installed");
            System.setProperty(key,"false");assertFalse(YouTubeBrowserAudio.preferred());
            System.setProperty(key,"true");System.setProperty(offline,"true");assertFalse(YouTubeBrowserAudio.preferred());
        }finally{
            if(prior==null)System.clearProperty(key);else System.setProperty(key,prior);
            if(priorOffline==null)System.clearProperty(offline);else System.setProperty(offline,priorOffline);
        }
    }
    @Test @Timeout(15) void postRollMustCompareCallbackLagToTheLastContentClock()throws Exception {
        try(var origin=new Origin();var source=YouTubeBrowserAudio.fixture(origin.uri())) {
            var state=source.session.evaluate("(()=>{let q=window.__muAudio;q.v.pause();q.ended=false;q.queue=[];q.lastSongTime=q.duration-.070072;q.cursor=Math.round((q.duration-.391667)*q.ac.sampleRate);document.body.classList.add('ad-showing');return q.pull();})()");
            assertEquals("ready",state.get("state").getAsString(),"Valid content tail must survive normal media-clock/callback lag");
            assertTrue(state.get("ended").getAsBoolean());
            var background=source.session.evaluate("(()=>{let q=window.__muAudio;q.ended=false;q.lastSongTime=q.duration-.03123;q.cursor=Math.round((q.duration-.477)*q.ac.sampleRate);return q.pull();})()");
            assertEquals("ready",background.get("state").getAsString(),"A hidden browser may deliver up to six callback blocks behind the content clock");
            assertTrue(background.get("ended").getAsBoolean());
            var beyondTail=source.session.evaluate("(()=>{let q=window.__muAudio;q.ended=false;q.lastSongTime=q.duration-.16;q.cursor=Math.round((q.duration-.55)*q.ac.sampleRate);return q.pull();})()");
            assertEquals("advertisement",beyondTail.get("state").getAsString(),"Callback tolerance must also have a hard bound against the declared content tail");
            var incomplete=source.session.evaluate("(()=>{let q=window.__muAudio;q.ended=false;q.cursor=Math.round((q.duration-.9)*q.ac.sampleRate);return q.pull();})()");
            assertEquals("advertisement",incomplete.get("state").getAsString(),"A missing audio tail must never become a successful EOF");
            var midSong=source.session.evaluate("(()=>{let q=window.__muAudio;q.ended=false;q.lastSongTime=q.duration-15;q.cursor=Math.round(q.lastSongTime*q.ac.sampleRate);return q.pull();})()");
            assertEquals("advertisement",midSong.get("state").getAsString(),"Mid-song ads remain separate from EOF");
        }
    }
    @Test @Timeout(15) void preparationMustActivateTheOfferedOfficialSkipInItsOwnPage()throws Exception {
        try(var origin=new Origin(false,false,true);var source=YouTubeBrowserAudio.fixture(origin.uri())) {
            assertFalse(source.session.evaluate("({ad:document.body.classList.contains('ad-showing')})").get("ad").getAsBoolean());
            assertTrue(energy(source.next())>.1);
        }
    }
    @Test @Timeout(15) void preparationCancellationAndConcurrentCloseMustRetireOwnedBrowser()throws Exception {
        try(var origin=new Origin()){
            var before=java.util.Set.copyOf(ProgressiveAudio.transports);
            assertThrows(java.io.InterruptedIOException.class,()->new YouTubeBrowserAudio.Source(origin.uri(),"",null,java.util.Map.of(),()->true));
            assertEquals(before,java.util.Set.copyOf(ProgressiveAudio.transports),"Cancelled startup must release its browser transport");
            var source=YouTubeBrowserAudio.fixture(origin.uri());long pid=source.session.pid();
            var first=CompletableFuture.runAsync(source::close);var second=CompletableFuture.runAsync(source::close);
            CompletableFuture.allOf(first,second).get(5,TimeUnit.SECONDS);
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            assertEquals(before,java.util.Set.copyOf(ProgressiveAudio.transports));
        }
    }
    @Test @Timeout(15) void idleConsumerMustBoundPcmAndPauseOnlyOwnedMedia()throws Exception {
        try(var origin=new Origin();var source=YouTubeBrowserAudio.fixture(origin.uri())){
            Thread.sleep(2800);
            var state=source.session.evaluate("(()=>{let q=window.__muAudio;return {n:q.queue.length,paused:q.v.paused,held:q.held};})()");
            assertTrue(state.get("n").getAsInt()<=24);assertTrue(state.get("held").getAsBoolean());assertTrue(state.get("paused").getAsBoolean());
            assertNotNull(source.next());
            // Drain the Java batch and browser backlog so playback resumes.
            for(int i=0;i<17;i++)assertNotNull(source.next());
            var resumed=source.session.evaluate("({paused:window.__muAudio.v.paused})");
            assertFalse(resumed.get("paused").getAsBoolean());
        }
    }
    static final class Origin implements AutoCloseable {
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        Origin()throws Exception {this(false,false);}
        Origin(boolean qualityReset)throws Exception {this(qualityReset,false);}
        Origin(boolean qualityReset,boolean earlyPostRoll)throws Exception {this(qualityReset,earlyPostRoll,false);}
        Origin(boolean qualityReset,boolean earlyPostRoll,boolean initialAd)throws Exception {
            byte[] wav=wave(80);server.createContext("/",e->{try(e){
                boolean audio=e.getRequestURI().getPath().equals("/tone.wav");
                String html="<video crossorigin='anonymous' src='/tone.wav'></video>";
                if(initialAd)html+="<script>document.body.classList.add('ad-showing')</script><button class='ytp-skip-ad-button' onclick=\"if(event.isTrusted)document.body.classList.remove('ad-showing')\">Skip</button>";
                if(qualityReset)html+="<div id='movie_player'></div><script>document.getElementById('movie_player').setPlaybackQualityRange=()=>{let v=document.querySelector('video');Object.defineProperty(v,'duration',{configurable:true,value:NaN});setTimeout(()=>delete v.duration,100);};</script>";
                if(earlyPostRoll)html+="<script>document.querySelector('video').addEventListener('ended',()=>document.body.classList.add('ad-showing'));</script>";
                byte[] data=audio?wav:html.getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Content-Type",audio?"audio/wav":"text/html");
                String range=e.getRequestHeaders().getFirst("Range");int start=0,end=data.length-1;
                if(range!=null&&audio){String[] parts=range.substring(6).split("-",-1);start=Integer.parseInt(parts[0]);if(!parts[1].isEmpty())end=Integer.parseInt(parts[1]);
                    e.getResponseHeaders().set("Content-Range","bytes "+start+"-"+end+"/"+data.length);}
                e.getResponseHeaders().set("Accept-Ranges","bytes");e.sendResponseHeaders(range!=null&&audio?206:200,end-start+1);e.getResponseBody().write(data,start,end-start+1);
            }});server.start();
        }
        URI uri(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");}
        public void close(){server.stop(0);}
    }
    @Test @Timeout(12) void officialPostRollHandlerMayRunBeforeOurEndedListener()throws Exception {
        try(var origin=new Origin(false,true);var source=YouTubeBrowserAudio.fixture(origin.uri())){
            source.request(79L*source.rate());float[] block;long frames=0;
            while((block=source.next())!=null)frames+=block.length/2;
            assertTrue(frames>source.rate()/2&&frames<2L*source.rate());
        }
    }
    @Test @Timeout(12) void postRollAfterRealContentTailMustTolerateTheAudioCallbackLatency()throws Exception {
        try(var origin=new Origin();var source=YouTubeBrowserAudio.fixture(origin.uri())){
            var state=source.session.evaluate("(()=>{let q=window.__muAudio;q.v.pause();q.queue=[];q.ended=false;q.lastSongTime=q.duration-.052;q.cursor=Math.round((q.duration-.307)*q.ac.sampleRate);document.body.classList.add('ad-showing');return q.pull();})()");
            assertEquals("ready",state.get("state").getAsString(),"Media clock reached the content tail before the official source changed to post-roll");
            assertTrue(state.get("ended").getAsBoolean());
            var early=source.session.evaluate("(()=>{let q=window.__muAudio;q.ended=false;q.cursor=Math.round((q.duration-1)*q.ac.sampleRate);return q.pull();})()");
            assertEquals("advertisement",early.get("state").getAsString(),"A missing audio second must not be accepted as completed content");
        }
    }
    @Test @Timeout(12) void officialQualityChangeMustNotEraseTheCapturedTimeline()throws Exception {
        try(var origin=new Origin(true);var source=YouTubeBrowserAudio.fixture(origin.uri())){
            assertEquals(80*48000,source.frames());
            assertTrue(energy(source.next())>.1,"Temporary metadata reset during quality selection must not silence all PCM");
        }
    }
    @Test @Timeout(30) void originalPcmOwnerMustRecoverSparseBackSeekAfterBrowserEof()throws Exception {
        try(var origin=new Origin()){
            var source=YouTubeBrowserAudio.fixture(origin.uri());
            java.io.File token=ProgressiveAudio.prepareSource(source,()->false,null,false);
            long pid=source.session.pid();
            try {
                var sample=ProgressiveAudio.claim(token);assertNotNull(sample);
                waitReady(token,65000);assertTrue(sampleEnergy(sample,65.3)>.1);
                waitReady(token,79000);
                long end=System.nanoTime()+3_000_000_000L;
                while(ProgressiveAudio.state(token,80001)!=2&&System.nanoTime()<end)Thread.sleep(20);
                assertEquals(2,ProgressiveAudio.state(token,80001));
                assertNull(ProgressiveAudio.analysisFile(token),"A sparse seek must not publish a complete/cacheable WAV");
                Thread.sleep(500); // Exercise the retired decoder, not a seek racing its last packet.
                waitReady(token,900); // Cached prefix exists but is too short to resume.
                assertTrue(sampleEnergy(sample,1.2)>.1,"A partial cached prefix must be extended after sparse EOF");
                waitReady(token,35000);
                assertTrue(sampleEnergy(sample,35.3)>.1,"Backseek after tail must provide real PCM");
            }finally{ProgressiveAudio.discard(token);}
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),"Closing the deck must close only its own browser");
        }
    }
    static void waitReady(java.io.File token,int millis)throws Exception {
        long deadline=System.nanoTime()+4_000_000_000L;int state;
        while((state=ProgressiveAudio.state(token,millis))==1&&System.nanoTime()<deadline)Thread.sleep(20);
        assertEquals(0,ProgressiveAudio.state(token,millis),"Requested PCM must become ready at "+millis+"ms");
    }
    static double sampleEnergy(repackage.com.jsyn.data.FloatSample sample,double second){
        int start=(int)(second*sample.getFrameRate())*sample.getChannelsPerFrame();double energy=0;
        for(int i=0;i<2000;i++){double v=sample.readDouble(start+i);energy+=v*v;}return energy;
    }
    @Test @Timeout(45) void officialPageDecoderMustFeedTheExistingPcmEngineAndSeek() throws Exception {
        Class<?> bridge;
        try{bridge=Class.forName("com.muoniumplayer.bedrock.YouTubeBrowserAudio");}
        catch(ClassNotFoundException missing){fail("Missing official-page PCM route; CDN retries cannot fix this session");return;}
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] wave=wave(80);
        byte[] page=("<video crossorigin='anonymous' src='/tone.wav'></video>").getBytes(StandardCharsets.UTF_8);
        server.createContext("/",e->{try(e){
            byte[] data=e.getRequestURI().getPath().equals("/tone.wav")?wave:page;
            e.getResponseHeaders().set("Content-Type",data==wave?"audio/wav":"text/html");
            String range=e.getRequestHeaders().getFirst("Range");int start=0,end=data.length-1;
            if(range!=null&&data==wave){String[] parts=range.substring(6).split("-",-1);start=Integer.parseInt(parts[0]);if(!parts[1].isEmpty())end=Integer.parseInt(parts[1]);
                e.getResponseHeaders().set("Content-Range","bytes "+start+"-"+end+"/"+data.length);}
            e.getResponseHeaders().set("Accept-Ranges","bytes");e.sendResponseHeaders(range!=null&&data==wave?206:200,end-start+1);
            e.getResponseBody().write(data,start,end-start+1);
        }});server.start();
        ProgressiveAudio.FrameSource source=null;
        try {
            var method=bridge.getDeclaredMethod("fixture",URI.class);method.setAccessible(true);
            source=(ProgressiveAudio.FrameSource)method.invoke(null,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"));
            assertEquals(48000,source.rate());assertEquals(2,source.channels());
            float[] first=source.next();assertNotNull(first);assertTrue(energy(first)>.1,"Real decoded page audio must be non-silent");
            var seek=source.getClass().getMethod("request",long.class);seek.setAccessible(true);seek.invoke(source,65L*source.rate());
            var position=source.getClass().getMethod("position");position.setAccessible(true);
            float[] late=source.next();assertNotNull(late);
            assertTrue((long)position.invoke(source)>=65L*source.rate(),"Sparse seek must reach beyond the old 1:04 failure");
            assertTrue(energy(late)>.1,"Late audio must decode, not merely report a timestamp");
            seek.invoke(source,79L*source.rate());
            float[] tail=source.next();assertNotNull(tail);assertTrue(energy(tail)>.1);
            int frames=tail.length/2;float[] block;
            while((block=source.next())!=null)frames+=block.length/2;
            assertTrue(frames<2*source.rate(),"Seek near EOF must not replay the entire prefix");
            var actual=(YouTubeBrowserAudio.Source)source;
            var postRoll=actual.session.evaluate("(()=>{document.body.classList.add('ad-showing');return window.__muAudio.pull();})()");
            assertEquals("ready",postRoll.get("state").getAsString(),"A post-song advertisement must not turn a completed song into a playback error");
            assertTrue(postRoll.get("ended").getAsBoolean());
            assertEquals(0,postRoll.getAsJsonArray("blocks").size(),"No later advertisement or track belongs to the completed song");
            var replacedBeforeEvent=actual.session.evaluate("(()=>{let q=window.__muAudio;q.ended=false;q.lastSongTime=q.duration-.05;q.cursor=Math.round((q.duration-.1)*q.ac.sampleRate);return q.pull();})()");
            assertEquals("ready",replacedBeforeEvent.get("state").getAsString(),"Official post-roll can replace the source before the media ended event");
            assertTrue(replacedBeforeEvent.get("ended").getAsBoolean());
        } finally {if(source!=null)source.close();server.stop(0);}
    }
    static double energy(float[] data){double sum=0;for(float x:data)sum+=x*x;return sum;}
    static byte[] wave(int seconds){
        int rate=48000,frames=rate*seconds;var b=ByteBuffer.allocate(44+frames*4).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36+frames*4).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
            .putShort((short)1).putShort((short)2).putInt(rate).putInt(rate*4).putShort((short)4).putShort((short)16)
            .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(frames*4);
        for(int i=0;i<frames;i++){short x=(short)(12000*Math.sin(i*2*Math.PI*440/rate));b.putShort(x).putShort(x);}
        return b.array();
    }
}
