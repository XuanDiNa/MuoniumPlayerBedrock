package com.muoniumplayer.bedrock;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Bounded transition-only diagnostics, excluding music/account/window titles. */
final class OverlayDiagnostics {
    private final OverlayWindowMode mode;
    private int count;
    private String previous;
    private long presentCount,presentSum,presentMax,readPixels,fullPixels,transferPixels;
    private long workFrames,drawSum,drawMax,loopMax,workLoggedAt,costPresentSum,costPresentMax;
    private long workStartedAt,sourceSequence,sourceEpoch,skippedSourceFrames;private int sourceDimension;
    private long boundsSum,readSum,submitSum,environmentSum,cinematicSum,environmentMax,cinematicMax;
    private long cpuNanos(){return ProcessHandle.current().info().totalCpuDuration().orElse(java.time.Duration.ZERO).toNanos();}
    private long previousCpu;
    void parts(long environment,long cinematic){environmentSum+=environment;cinematicSum+=cinematic;environmentMax=Math.max(environmentMax,environment);cinematicMax=Math.max(cinematicMax,cinematic);}
    OverlayDiagnostics(OverlayWindows windows,OverlayWindowMode mode) {
        this.mode=mode;
        System.out.println("OVERLAY_RENDER version="+com.muoniumplayer.MuoniumPlayerClient.MOD_VERSION+" presentation=auto-native-d3d12 fallback=layered-window gameBackend=D3D11,D3D12 arke=exact-adapter legacyD3D11Hooks=disabled gl="+
            GL11.glGetString(GL11.GL_VERSION)+" vendor="+GL11.glGetString(GL11.GL_VENDOR)+
            " renderer="+GL11.glGetString(GL11.GL_RENDERER)+
            " displayApi="+GLFW.glfwGetWindowAttrib(windows.display(),GLFW.GLFW_CLIENT_API)+
            " renderVisible="+GLFW.glfwGetWindowAttrib(windows.render(),GLFW.GLFW_VISIBLE));
    }
    void present(LayeredPresenter presenter,int width,int height) {
        long ns=presenter.lastPresentNanos();presentSum+=ns;presentMax=Math.max(presentMax,ns);
        costPresentSum+=ns;costPresentMax=Math.max(costPresentMax,ns);
        boundsSum+=presenter.boundsNanos();readSum+=presenter.readNanos();submitSum+=presenter.submitNanos();
        transferPixels+=presenter.transferPixels();readPixels+=presenter.readbackPixels();fullPixels+=presenter.fullPixels();
        if(++presentCount%600==0) {
            log("COMPOSE transport="+presenter.transport()+" frames="+presenter.presentations()+" size="+width+"x"+height+
                " readbackAndSubmitAvgUs="+presentSum/600/1000+" maxUs="+presentMax/1000+
                " copiedPercent="+(fullPixels==0?0:readPixels*100/fullPixels)+" transferredPercent="+(fullPixels==0?0:transferPixels*100/fullPixels)+" regions="+presenter.regionCount());
            presentSum=presentMax=readPixels=fullPixels=transferPixels=0;
        }
    }
    void mode(boolean interactive) { log("mode interactive="+interactive+" win32="+mode.state()); }
    void work(long draw,long total,int gameFps,boolean cameraDriven,WorldFrame frame) {
        drawSum+=Math.max(0,draw);drawMax=Math.max(drawMax,draw);loopMax=Math.max(loopMax,total);workFrames++;
        long now=System.nanoTime();if(workStartedAt==0){workStartedAt=workLoggedAt=now;previousCpu=cpuNanos();}
        if(cameraDriven&&frame!=null){
            if(sourceSequence>0&&sourceEpoch==frame.epoch()&&sourceDimension==frame.dimension()&&frame.sequence()>sourceSequence+1)
                skippedSourceFrames+=frame.sequence()-sourceSequence-1;
            sourceSequence=frame.sequence();sourceEpoch=frame.epoch();sourceDimension=frame.dimension();
        }else sourceSequence=0;
        if(now-workLoggedAt>=5_000_000_000L){
            long processCpu=cpuNanos();
            System.out.println("OVERLAY_COST frames="+workFrames+" gameFps="+gameFps+" sync="+(cameraDriven?"camera-signal":"fps-target")
                +" actualPresentHz="+Math.round(workFrames*1_000_000_000.0/(now-workStartedAt))+" skippedSourceFrames="+skippedSourceFrames+" drawAvgUs="+drawSum/workFrames/1000
                +" drawMaxUs="+drawMax/1000+" presentAvgUs="+costPresentSum/workFrames/1000
                +" presentMaxUs="+costPresentMax/1000+" loopMaxUs="+loopMax/1000
                +" alphaAvgUs="+boundsSum/workFrames/1000+" readAvgUs="+readSum/workFrames/1000+" transferAvgUs="+submitSum/workFrames/1000
                +" environmentTotalUs="+environmentSum/1000+" environmentMaxUs="+environmentMax/1000
                +" cinematicTotalUs="+cinematicSum/1000+" cinematicMaxUs="+cinematicMax/1000
                +" companionCpuMs="+(processCpu-previousCpu)/1_000_000);
            previousCpu=processCpu;boundsSum=readSum=submitSum=environmentSum=cinematicSum=environmentMax=cinematicMax=0;
            workStartedAt=workLoggedAt=now;workFrames=drawSum=drawMax=loopMax=costPresentSum=costPresentMax=skippedSourceFrames=0;
        }
    }
    void state(boolean interactive,boolean visible,boolean gameFocused,boolean playerFocused) {
        String next="state interactive="+interactive+" visible="+visible+" gameFocus="+gameFocused+" playerFocus="+playerFocused
            +" input="+HostInputState.snapshot();
        if(!next.equals(previous)){previous=next;log(next);}
    }
    void closed() { log("close presented=transparent focusReturn=after-ULW"); }
    private void log(String text) {
        if(count++<200)System.out.println("OVERLAY_TRANSITION "+text);
    }
}
