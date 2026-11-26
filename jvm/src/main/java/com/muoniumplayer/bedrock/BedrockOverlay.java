package com.muoniumplayer.bedrock;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.ExternalMediaService;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.render.skija.SkijaHost;
import com.sun.jna.Pointer;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.*;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.lwjgl.glfw.*;
import java.util.concurrent.locks.LockSupport;
import java.nio.file.*;

/** Transparent owned overlay. Real world camera data arrives as an immutable
 * read-only snapshot; no game object is retained by the Java player. */
public final class BedrockOverlay {
    private static HostSettings appliedSettings;
    private interface ExtraUser32 extends StdCallLibrary {
        ExtraUser32 INSTANCE = Native.load("user32", ExtraUser32.class, W32APIOptions.DEFAULT_OPTIONS);
        boolean IsIconic(WinDef.HWND window);
        boolean ClientToScreen(WinDef.HWND window, WinDef.POINT point);
    }
    public static void run(String[] args) throws Exception {
        Path config=Path.of(option(args,"--config")).toAbsolutePath();
        try(var health=HostWatchdog.open(Thread.currentThread(),config.getParent().resolve("logs"))) {
            run(args,health);
        }
    }
    private static void run(String[] args,HostWatchdog health) throws Exception {
        appliedSettings = null;
        System.out.println("OVERLAY_COMPAT patch=sparse-ulw-20261113 version="+MuoniumPlayerClient.MOD_VERSION+" presentation=auto-native-d3d12 fallback=layered-window gameBackend=D3D11,D3D12 arke=exact-adapter legacyD3D11Hooks=disabled render=hidden-fbo display=layered-NO_API framePacing=world-camera-signal,fallback-game-fps,ui-60 alphaCrop=max-reduction-bands4");
        long parent = Long.parseLong(option(args, "--parent-pid"));
        var process = ProcessHandle.of(parent).orElseThrow();
        var owner = new WinDef.HWND(Pointer.createConstant(Long.parseUnsignedLong(option(args, "--owner"))));
        String prefix = option(args, "--control-prefix");
        if (!prefix.matches("Local\\\\MuoniumBedrock-[0-9a-f]{32}")) throw new IllegalArgumentException("Control identity");
        var controls = new WinNT.HANDLE[14];
        long window = 0;
        OverlayWindows windows=null;
        LayeredPresenter presenter=null;
        HostWorkspace workspace=null;
        var errors = GLFWErrorCallback.createPrint(System.err); errors.set();
        try {
            for (int i = 0; i < controls.length; i++) {
                controls[i] = Kernel32.INSTANCE.OpenEvent(i == 5 || i == 13 ? 0x0002 : 0x00100000, false, prefix + "-" + i);
                if (controls[i] == null) throw new IllegalStateException("Native control unavailable");
            }
            if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW unavailable");
            windows=OverlayWindows.create();window=windows.display();
            var overlay=windows.handle();
            User32.INSTANCE.SetWindowLongPtr(overlay, -8, owner.getPointer());
            int exStyle = User32.INSTANCE.GetWindowLong(overlay, -20);
            User32.INSTANCE.SetWindowLong(overlay, -20, (exStyle & ~0x00040000) | 0x00000080);
            var windowMode=new OverlayWindowMode(window);
            var diagnostics=new OverlayDiagnostics(windows,windowMode);
            var client = BedrockClient.getInstance(); client.bindWindow(window);
            var player = MuoniumPlayerClient.getInstance(); player.init();
            installInput(window);
            HostSettings settings = HostSettings.parse("{}");
            String lastConfig = null;
            Path config = Path.of(option(args, "--config"));
            HostHudSync.bind(config);
            var panel = new OverlaySession(client, NCMScreen::getInstance);
            workspace=new HostWorkspace(prefix);
            HostToolsScreen.ordinarySettings(()->{panel.close();Kernel32.INSTANCE.SetEvent(controls[13]);});
            boolean shown = false;
            long lastReload = 0, lastTick = 0,lastDepthLog=0,lastVoiceTick=0;
            WorldFrame lastPresentedCamera=null;
            var lyricRefresh=LyricRefresh.global();var lyricGate=new LyricRefresh.Gate();int lastRefreshChoice=-1;
            presenter=new LayeredPresenter(overlay);
            windowMode.update(false);
            presenter.resize(1280,720);SkijaHost.renderTarget(presenter.framebuffer());
            drawFrame(panel,()->{});presenter.present(0,0);
            var environmentRuntime=new EnvironmentRuntime();
            var speaker=new WorldSpeaker();
            var cinematic=new CinematicRuntime();
            EnvironmentFrame environment=null;
            try (LyricOverlay lyrics = new LyricOverlay(); WorldReader worldReader=new WorldReader(prefix);
                 EnvironmentReader environmentReader=new EnvironmentReader(prefix);DepthReader depthReader=new DepthReader(prefix);
                 CinematicReader cinematicReader=new CinematicReader(prefix);NativeComposition composition=new NativeComposition(prefix);
                 RoomVoiceKeys voiceKeys=new RoomVoiceKeys(prefix);RoomVoiceWorldReader voiceWorldReader=new RoomVoiceWorldReader(prefix)) {
                System.out.println("BEDROCK_HOST_READY source=upstream lyrics=hud,stage,world-camera keys=NONE");
                Kernel32.INSTANCE.SetEvent(controls[5]);
                while (process.isAlive() && User32.INSTANCE.IsWindow(owner) && !GLFW.glfwWindowShouldClose(window)) {
                    if (Kernel32.INSTANCE.WaitForSingleObject(controls[4], 0) == 0) break;
                    health.phase(HostWatchdog.Stage.EVENTS);
                    long now = System.nanoTime(); GLFW.glfwPollEvents();
                    health.phase(HostWatchdog.Stage.TASKS);client.drainTasks();
                    health.phase(HostWatchdog.Stage.SETTINGS);
                    if (now - lastReload > 500_000_000L) {
                        lastReload = now;
                        try {
                            if (Files.size(config) > 65536) throw new IllegalArgumentException("Settings size");
                            String text = Files.readString(config);
                            if (!text.equals(lastConfig)) {
                                var next = HostSettings.parse(text);
                                lastPresentedCamera=null;
                                applySettings(next); settings = next; lastConfig = text; lyrics.retryStage();
                                HostHudSync.observe(text);
                                if (!settings.enabled()) {
                                    panel.close(); HostFileDialogs.cancel(); ExternalMediaService.stop(); PartyBridge.close();
                                    if (CloudMusic.player != null) CloudMusic.player.pause();
                                }
                            }
                        } catch (Exception ignored) { /* Keep the last complete settings snapshot. */ }
                    }
                    var foreground = User32.INSTANCE.GetForegroundWindow();
                    boolean gameFocused = owner.equals(foreground);
                    boolean playerFocused = overlay.equals(foreground);
                    HostInputState.update(window,playerFocused);
                    health.phase(HostWatchdog.Stage.ENVIRONMENT);
                    long environmentStarted=System.nanoTime();
                    WorldFrame frame=worldReader.read();
                    RoomVoiceWorld voiceWorld=voiceWorldReader.read();
                    // Keep the proven camera-projected lyric raster in the
                    // common alpha layer. Native atlas/depth admission has not
                    // been established by the game, so it cannot own visibility.
                    lyrics.composition(null);
                    speaker.update(frame,settings.enabled()&&settings.speakerEnabled());
                    if(now-lastTick>50_000_000L) {
                        boolean autoMusic=com.muoniumplayer.core.ncm.music.ambient.AmbientSettings.isEnabled();
                        boolean sceneSound=com.muoniumplayer.core.ncm.music.acoustics.AcousticsSettings.isEnabled();
                        environmentReader.request(settings.enabled()&&(frame!=null&&(autoMusic||sceneSound||settings.speakerEnabled()||settings.worldLyricsOcclusion()||cinematic.enabled())||voiceWorld!=null&&RoomVoice.global().needsGeometry()));
                        environment=environmentReader.read(frame);
                        environmentRuntime.tick(environment,settings.enabled(),autoMusic,sceneSound,speaker.anchor());
                    }
                    client.setScene(frame==null?BedrockClient.Scene.UNKNOWN:frame.scene(panel.isOpen()));
                    lyrics.updateWorld(frame);
                    lyrics.updateEnvironment(environment);
                    var depthFrame=depthReader.read();lyrics.updateDepth(depthFrame,depthReader.now());
                    long environmentNanos=System.nanoTime()-environmentStarted;
                    health.phase(HostWatchdog.Stage.CONTROLS);
                    voiceKeys.poll(settings.enabled());
                    boolean requestFocus=workspace.update(panel,settings.enabled());
                    if(Kernel32.INSTANCE.WaitForSingleObject(controls[12],0)==0&&settings.enabled()){
                        panel.open(HostToolsScreen.entry());requestFocus=true;
                    }
                    if(Kernel32.INSTANCE.WaitForSingleObject(controls[11],0)==0&&settings.enabled()){
                        panel.show();requestFocus=true;
                    }
                    if(settings.enabled())for(int action=7;action<=10;action++)if(Kernel32.INSTANCE.WaitForSingleObject(controls[action],0)==0) {
                        if(action==7)panel.open(new HostHudEditor());
                        else if(action==8)panel.open(new com.muoniumplayer.core.screens.hud.GuiPresetSettings(
                            ()->client.gui.setScreen(NCMScreen.getInstance()),com.muoniumplayer.core.screens.hud.PresetSettingsActions.Mode.MANAGE));
                        else if(action==10)HostIslandSearch.open(panel);
                        else {
                            panel.open(new HostMiniPlayer());
                        }
                        requestFocus=true;
                    }
                    if(Kernel32.INSTANCE.WaitForSingleObject(controls[6],0)==0&&settings.enabled()&&settings.speakerEnabled()
                            &&gameFocused&&!panel.isOpen()) {
                        if(!speaker.toggle(environmentReader.read(frame)))System.out.println("BEDROCK_SPEAKER placement-unavailable aim-at-known-block-within-6");
                    }
                    for (int i = 0; i < 4; i++) if (Kernel32.INSTANCE.WaitForSingleObject(controls[i], 0) == 0 && settings.enabled()) {
                        if (i == 0) {
                            panel.toggle();
                            requestFocus=panel.isOpen();
                        } else if (i == 1) com.muoniumplayer.core.MusicHotkeys.togglePlayback();
                        else if (i == 2) CloudMusic.next();
                        else CloudMusic.prev();
                    }
                    panel.finishScreenClose();
                    // An unfocused interactive overlay must never sit in front of
                    // the game and intercept gameplay clicks.
                    boolean wantsVisible = !HostFileDialogs.active()&&panel.visible(settings.enabled(), ExtraUser32.INSTANCE.IsIconic(owner), gameFocused, playerFocused);
                    long cinematicStarted=System.nanoTime();
                    cinematic.update(cinematicReader,environment,frame,settings.enabled()&&gameFocused&&!panel.isOpen()
                        &&!HostFileDialogs.active()&&!ExtraUser32.INSTANCE.IsIconic(owner),now);
                    if(now-lastVoiceTick>50_000_000L){lastVoiceTick=now;RoomVoice.global().updatePose(settings.enabled()?voiceWorld:null,voiceWorld==null?null:environmentReader.readEpoch(voiceWorld.geometryEpoch()));}
                    diagnostics.parts(environmentNanos,System.nanoTime()-cinematicStarted);
                    if(windowMode.update(panel.isOpen()))diagnostics.mode(panel.isOpen());
                    if(!wantsVisible&&shown){presenter.visible(false);User32.INSTANCE.ShowWindow(overlay,0);shown=false;}
                    diagnostics.state(panel.isOpen(),wantsVisible,gameFocused,playerFocused);
                    var rect = new WinDef.RECT(); var origin = new WinDef.POINT();
                    if (!User32.INSTANCE.GetClientRect(owner, rect) || !ExtraUser32.INSTANCE.ClientToScreen(owner, origin)) break;
                    int width = rect.right - rect.left, height = rect.bottom - rect.top;
                    boolean nativeMode=composition.acknowledged(wantsVisible);
                    if (width < 1 || height < 1 || width > 16384 || height > 16384) { Thread.sleep(50); continue; }
                    // SetWindowPos avoids GLFW logical/physical DPI rounding. No
                    // TOPMOST flag is used, so other applications remain above it.
                    if(panel.isOpen())User32.INSTANCE.SetWindowPos(overlay, null, origin.x, origin.y, width, height, 0x0004 | 0x0010);
                    if(width!=client.getWindow().getWidth()||height!=client.getWindow().getHeight()) {
                        double scale=Math.max(1,Math.min(width/1280.0,height/720.0));
                        client.getWindow().size(width,height,scale);
                        if(client.gui.screen()!=null)client.gui.screen().resize(SkijaHost.guiWidth(),SkijaHost.guiHeight());
                    }
                    health.phase(HostWatchdog.Stage.TICK);
                    if (now - lastTick > 50_000_000L) {
                        lastTick = now;
                        if(settings.enabled())player.tick();
                        com.muoniumplayer.core.ncm.music.FocusVolumeDucker.onClientTick(gameFocused||playerFocused);
                        if (client.gui.screen() != null) client.gui.screen().updateScreen();
                    }
                    if (!wantsVisible) {
                        if(composition.available()){composition.beginFrame(null,false);presenter.compose(composition,false,false,false);}
                        lastPresentedCamera=null;
                        lyricGate.remaining(false,lyricRefresh.index(),now);
                        depthReader.request(frame,width,height,null,false);
                        // Hidden first: never return focus with an opaque player
                        // frame still composited over the game.
                        if(!panel.isOpen()&&overlay.equals(User32.INSTANCE.GetForegroundWindow()))User32.INSTANCE.SetForegroundWindow(owner);
                        health.phase(HostWatchdog.Stage.IDLE);Thread.sleep(25); continue;
                    }
                    int refreshChoice=lyricRefresh.index();
                    if(refreshChoice!=lastRefreshChoice){lastRefreshChoice=refreshChoice;
                        System.out.println("LYRIC_REFRESH choice="+refreshChoice+" capHz="+LyricRefresh.hertz(refreshChoice)+" scope=passive-3d uiHz=60");}
                    long capWait=lyricGate.remaining(!cinematic.active()&&!panel.isOpen()&&settings.lyricsEnabled()&&settings.lyricsMode()>0,refreshChoice,System.nanoTime());
                    if(capWait>0){health.phase(HostWatchdog.Stage.IDLE);LockSupport.parkNanos(Math.min(capWait,8_000_000L));continue;}
                    boolean cameraDriven=!panel.isOpen()&&(cinematic.enabled()||settings.lyricsEnabled()&&settings.lyricsMode()>0)&&frame!=null&&worldReader.frameSignaled();
                    if(cameraDriven&&frame.sameFrame(lastPresentedCamera)){
                        health.phase(HostWatchdog.Stage.IDLE);worldReader.awaitNext(frame,8_000_000L);continue;
                    }
                    health.phase(HostWatchdog.Stage.DRAW);
                    long drawStarted=System.nanoTime();
                    presenter.resize(width,height);SkijaHost.renderTarget(presenter.framebuffer());
                    var drawingSettings=settings;
                    composition.beginFrame(frame,false);
                    drawFrame(panel,()->{
                        renderPassiveOverlays(drawingSettings,frame!=null,cinematic.hideHud(),()->lyrics.render(drawingSettings,now,frame),player::renderHud);
                        if(!cinematic.hideHud()) {
                            SpeakerMarker.draw(SkijaHost.canvas(),frame,speaker.anchor(),SkijaHost.guiWidth(),SkijaHost.guiHeight(),
                                com.muoniumplayer.core.ncm.music.acoustics.AcousticsSettings.isEnabled());
                        }
                        cinematic.draw();
                    });
                    long drawNanos=System.nanoTime()-drawStarted;
                    var depthCrop=lyrics.depthCrop();
                    depthReader.request(frame,width,height,depthCrop,settings.enabled()&&settings.lyricsEnabled()&&settings.lyricsMode()==2&&settings.worldLyricsOcclusion()&&!panel.isOpen()&&!nativeMode);
                    if(settings.worldLyricsOcclusion()&&now-lastDepthLog>5_000_000_000L){lastDepthLog=now;
                        System.out.println("DEPTH_CLIP received="+(depthFrame!=null)+" appliedRows="+lyrics.depthApplied()+" partialRows="+lyrics.depthPartial()+" reprojectedRows="+lyrics.depthReprojected()
                            +" sampleStep="+(depthFrame==null?0:depthFrame.sampleStep)+" requestedPixels="+(depthCrop==null?0:(long)depthCrop[2]*depthCrop[3]));}
                    health.phase(HostWatchdog.Stage.PRESENT);
                    if(nativeMode){
                        presenter.compose(composition,true,settings.worldLyricsOcclusion(),!panel.isOpen());
                        if(panel.isOpen())presenter.inputShield(origin.x,origin.y);
                        else {presenter.visible(false);if(shown){User32.INSTANCE.ShowWindow(overlay,0);shown=false;}}
                    }else {
                        // Negotiate with a real swapchain before hiding ULW.
                        // A non-admitted/missing API never leaves an invisible modal page.
                        if(panel.isOpen())presenter.present(origin.x,origin.y);
                        else presenter.presentPassive(origin.x,origin.y);
                        presenter.probe(composition);
                    }
                    diagnostics.present(presenter,width,height);
                    diagnostics.work(drawNanos,System.nanoTime()-now,frame==null?-1:frame.fps(),cameraDriven,frame);
                    lyricGate.presented(drawStarted);
                    lastPresentedCamera=cameraDriven?frame:null;
                    if(!shown&&(!nativeMode||panel.isOpen())){User32.INSTANCE.ShowWindow(overlay,4);presenter.visible(true);shown=true;if(panel.isOpen()&&gameFocused)requestFocus=true;}
                    if(requestFocus)GLFW.glfwFocusWindow(window);
                    // Closing can finish inside NCMScreen.drawScreen. Publish
                    // its transparent bitmap before changing input mode/focus.
                    if(panel.finishScreenClose()) {
                        if(windowMode.update(false))diagnostics.mode(false);
                        diagnostics.closed();
                    }
                    if(!panel.isOpen()&&overlay.equals(User32.INSTANCE.GetForegroundWindow()))User32.INSTANCE.SetForegroundWindow(owner);
                    // One passive world presentation per available camera snapshot.
                    // Coalesced frames are skipped, never queued or replayed later.
                    if(cameraDriven){health.phase(HostWatchdog.Stage.IDLE);worldReader.awaitNext(frame,8_000_000L);continue;}
                    long interval=OverlayCadence.interval(panel.isOpen(),(cinematic.active()||settings.lyricsEnabled()&&settings.lyricsMode()>0)&&frame!=null,
                        frame==null?-1:frame.fps(),presenter.readbackPixels(),presenter.fullPixels(),drawNanos+presenter.lastPresentNanos());
                    long remaining=interval-(System.nanoTime()-now);
                    health.phase(HostWatchdog.Stage.IDLE);
                    if(remaining>0)LockSupport.parkNanos(remaining);
                }
            }
        } finally {
            RoomVoice.global().close();
            if(workspace!=null)workspace.close();
            HostToolsScreen.ordinarySettings(null);
            health.phase(HostWatchdog.Stage.CLEANUP);
            // On presentation failure, remove the owned layer before cleanup.
            if(windows!=null&&windows.display()!=0)User32.INSTANCE.ShowWindow(windows.handle(),0);
            ExternalMediaService.stop();
            HostFileDialogs.cancel();
            PartyBridge.close();
            if (CloudMusic.player != null) CloudMusic.player.close();
            CloudMusic.onStop(); SkijaHost.shutdown();
            for (var control : controls) if (control != null) Kernel32.INSTANCE.CloseHandle(control);
            if(presenter!=null)presenter.close();
            if(windows!=null)windows.close();
            GLFW.glfwTerminate(); errors.close();
        }
    }
    static void drawFrame(OverlaySession panel,Runnable passive) {
        var client=BedrockClient.getInstance();
        boolean interactive=panel.isOpen()&&client.gui.screen()!=null;
        boolean transparent=interactive&&client.gui.screen().transparentHost();
        // Win32 per-pixel layered windows pass alpha-zero pixels through even
        // without WS_EX_TRANSPARENT. A 1/255 black input shield keeps transparent
        // pages modal; passive HUD and the exact closing frame remain alpha zero.
        SkijaHost.beginFrame(interactive?(transparent?0x01000000:0xEE101015):0);
        try {
            if(interactive) {
                if(transparent)passive.run();
                client.gui.screen().drawHostFrame(0);
                // NCMScreen completes its animated close from inside drawScreen.
                // Never present that frame's almost-opaque panel backdrop after
                // its page disappeared or leave it visible until focus returns.
                if(client.gui.screen()==null){SkijaHost.canvas().clear(0);}
            }
            else passive.run();
        } finally {SkijaHost.endFrame();}
    }
    private static void applySettings(HostSettings s) {
        // JSON owns explicit edits, not unrelated controls changed inside the
        // original UI. A lyric/style edit must not reset playback preferences.
        HostSettings previous = appliedSettings;
        if(previous==null||previous.smtcPublishEnabled()!=s.smtcPublishEnabled())
            com.muoniumplayer.core.ncm.music.SmtcPublisher.configureHost(s.smtcPublishEnabled());
        if (previous == null || previous.volume() != s.volume()) CloudMusic.setVolume(s.volume() / 100f, false);
        if (previous == null || previous.shuffle() != s.shuffle() || previous.repeat() != s.repeat())
            CloudMusic.playMode = s.shuffle() ? CloudMusic.PlayMode.Random : s.repeat() == 2
                    ? CloudMusic.PlayMode.LoopSingle : s.repeat() == 1 ? CloudMusic.PlayMode.LoopInList : CloudMusic.PlayMode.Sequential;
        if (previous == null || previous.lyricsTimeOffsetMs() != s.lyricsTimeOffsetMs())
            CloudMusic.previewLyricOffsetMillis(s.lyricsTimeOffsetMs());
        if(previous==null||previous.lyricsEnabled()!=s.lyricsEnabled())HudConfig.worldLyricsEnabled = s.lyricsEnabled();
        applyHudSettings(s,previous);
        HudConfig.worldLyricsModernStyle = true;
        if(previous==null||previous.stageScalePercent()!=s.stageScalePercent())HudConfig.worldLyricsScale = s.stageScalePercent() / 100f;
        if(previous==null||previous.stageSpreadDeg()!=s.stageSpreadDeg())HudConfig.worldLyricsSpreadDeg = s.stageSpreadDeg();
        if(previous==null||previous.stageStyle()!=s.stageStyle()){
            HudConfig.worldLyricsCadenza = s.stageStyle() == 1;
            HudConfig.worldLyricsHuiguang = s.stageStyle() == 2;
        }
        appliedSettings = s;
    }
    static void renderPassiveOverlays(HostSettings s,boolean inWorld,Runnable lyrics,Runnable hud) {
        renderPassiveOverlays(s,inWorld,false,lyrics,hud);
    }
    static void renderPassiveOverlays(HostSettings s,boolean inWorld,boolean cinematicHidden,Runnable lyrics,Runnable hud) {
        if(!s.enabled())return;
        if(s.lyricsEnabled()&&(!cinematicHidden||inWorld&&s.lyricsMode()==2))lyrics.run();
        if(!cinematicHidden&&inWorld&&s.hudEnabled())hud.run();
    }
    static void applyHudSettings(HostSettings s,HostSettings previous) {
        // Apply only explicit changes, so the upstream settings screen can
        // still edit its own island preference until ModMenu changes it.
        if(previous==null || previous.dynamicIslandEnabled()!=s.dynamicIslandEnabled())
            HudConfig.dynamicIslandEnabled=s.dynamicIslandEnabled();
        HudElements.apply(s,previous);
    }
    private static void installInput(long window) {
        var client = BedrockClient.getInstance();
        GLFW.glfwSetWindowFocusCallback(window,(w,focused)->{
            if(!focused){HostInputState.suspend();if(client.gui.screen()!=null)client.gui.screen().suspendHostInput();}
        });
        GLFW.glfwSetCursorPosCallback(window, (w, x, y) -> Mouse.position(x, y));
        GLFW.glfwSetScrollCallback(window, (w, x, y) -> Mouse.pushScrollNotches(y));
        GLFW.glfwSetKeyCallback(window, (w, key, scan, action, mods) -> {
            if (HostInputState.keyEvent(key,action) && client.gui.screen() != null
                    && (key != GLFW.GLFW_KEY_ESCAPE || action == GLFW.GLFW_PRESS))
                dispatch(() -> client.gui.screen().keyEvent('\0', Keyboard.fromGlfw(key)));
        });
        GLFW.glfwSetCharCallback(window, (w, codepoint) -> {
            if (HostInputState.textAllowed() && client.gui.screen() != null) for (char ch : Character.toChars(codepoint)) dispatch(() -> client.gui.screen().keyEvent(ch, 0));
        });
        GLFW.glfwSetMouseButtonCallback(window, (w, button, action, mods) -> {
            boolean accepted=HostInputState.buttonEvent(button,action);
            var screen = client.gui.screen(); if (screen == null || !accepted) return;
            double scale = client.getWindow().getGuiScale();
            int x = (int) (Mouse.getX() / scale), y = (int) ((client.getWindow().getHeight() - Mouse.getY() - 1) / scale);
            if (action == GLFW.GLFW_PRESS) dispatch(() -> screen.clickEvent(x, y, button));
            else screen.releaseEvent(x, y, button);
        });
    }
    private static String option(String[] args, String key) {
        for (int i = 0; i + 1 < args.length; i++) if (key.equals(args[i])) return args[i + 1];
        throw new IllegalArgumentException("Missing " + key);
    }
    private interface Action { void run() throws Exception; }
    private static void dispatch(Action action) { try { action.run(); } catch (Exception e) { throw new RuntimeException(e); } }
}
