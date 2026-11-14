package com.muoniumplayer.bedrock;
import com.muoniumplayer.render.skija.SkijaHost;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import java.nio.file.Path;
import java.util.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
/** The shipped Skija producer -> mailbox -> production D3D12 hook -> real GPU pixels. */
class NativeD3D12RenderTest {
    @Test void nativeOutputSurvivesSparseHudInputFocusAndResize() throws Exception {
        exercise(false);exercise(true);
    }
    private void exercise(boolean warp) throws Exception {
        boolean baseline=Boolean.getBoolean("muonium.transferBaseline");
        assertTrue(GLFW.glfwInit());
        var args=new ArrayList<String>();args.add(Path.of("../../../build/windows/x64/release/D3D12NativeTests.exe").toAbsolutePath().normalize().toString());args.add("--serve");if(warp)args.add("--warp");
        var process=new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var response=new BufferedReader(new InputStreamReader(process.getInputStream()));var command=new PrintWriter(process.getOutputStream(),true);
        var ready=response.readLine();assertNotNull(ready);assertTrue(ready.startsWith("READY "),ready);var values=ready.split(" ");
        try(var windows=OverlayWindows.create();var presenter=new LayeredPresenter(windows.handle());var composition=new NativeComposition(values[2])) {
            System.out.println("NATIVE_PRODUCER_GL renderer="+org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));
            try {
                var client=BedrockClient.getInstance();client.bindWindow(windows.display());
                var mode=new OverlayWindowMode(windows.display());mode.update(false);
                if(!com.muoniumplayer.MuoniumPlayerClient.getInstance().isReady())com.muoniumplayer.MuoniumPlayerClient.getInstance().init();
                int pixelsChecked=0,transitions=0;
                for(int size=0;size<2;size++) {
                    int w=size==0?400:513,h=size==0?300:385;
                    if(size>0){command.println("RESIZE "+w+" "+h);assertEquals("OK",response.readLine());assertFalse(composition.acknowledged(true),"resize renegotiates, never latches fallback");}
                    presenter.resize(w,h);client.getWindow().size(w,h,1);SkijaHost.renderTarget(presenter.framebuffer());
                    boolean selected=false;long composeNs=0;int measuredFrames=0;
                    for(int frame=0;frame<32;frame++) {
                        GLFW.glfwPollEvents();composition.beginFrame(null,false);SkijaHost.beginFrame(0);
                        try(var paint=new Paint().setColor(0x80FF0000)) {
                            SkijaHost.canvas().drawRect(Rect.makeXYWH(4,8,w-8,32),paint);
                            SkijaHost.canvas().drawRect(Rect.makeXYWH(4,h-40,w-8,32),paint.setColor(0x8000FF00));
                            int dotX=frame%2==0?w/2:w/2+64;
                            SkijaHost.canvas().drawRect(Rect.makeXYWH(dotX,h/2,1,1),paint.setColor(0x01FF0000));
                        }finally{SkijaHost.endFrame();}
                        selected=composition.acknowledged(true);
                        if(selected){long started=System.nanoTime();presenter.compose(composition,true,false,true);
                            if(frame>=4){composeNs+=System.nanoTime()-started;measuredFrames++;}
                            mode.update(frame%2==0);if(frame%2==0)presenter.inputShield(40,40);else presenter.visible(false);}
                        else{presenter.presentPassive(40,40);presenter.probe(composition);}
                        command.println("DRAW");var line=response.readLine();assertNotNull(line);assertTrue(line.startsWith("PIXELS "),line);
                        if(selected){
                            command.println("META");var meta=response.readLine().split(" ");
                            long transfer=Long.parseLong(meta[2])*Long.parseLong(meta[3]);
                            System.out.println("NATIVE_TRANSFER version="+meta[1]+" pixels="+transfer+" full="+(w*h));
                            if(!baseline){assertEquals("2",meta[1],"D3D12 capability selects the explicit packed protocol");
                                assertTrue(transfer<(long)w*h/2,"Sparse native frames must omit the large transparent gap");}
                            var rgb=Arrays.stream(line.substring(7).split(" ")).mapToInt(Integer::parseInt).toArray();
                            assertEquals(128,rgb[0],2);assertEquals(0,rgb[1],1);assertEquals(127,rgb[2],2);pixelsChecked++;
                            assertEquals(0,rgb[3],1);assertEquals(128,rgb[4],2);assertEquals(127,rgb[5],2);pixelsChecked++;
                            int dotX=frame%2==0?w/2:w/2+64,oldX=frame%2==0?w/2+64:w/2;
                            command.println("SAMPLE "+dotX+" "+h/2+" "+oldX+" "+h/2+" 1 "+h/2);
                            var extra=Arrays.stream(response.readLine().substring(8).split(" ")).mapToInt(Integer::parseInt).toArray();
                            assertEquals(1,extra[0],"isolated alpha=1 texel survives packing");assertEquals(254,extra[2],1);
                            assertEquals(0,extra[4],"previous dot position is fully clear");assertEquals(255,extra[6]);
                            assertEquals(0,extra[8]);assertEquals(255,extra[10],"transparent gap preserves game pixels");
                        }
                        if(frame==15&&selected){assertFalse(composition.acknowledged(false));transitions++;}
                        Thread.sleep(4);
                    }
                    assertTrue(selected,"real accepted Presents must replace ULW");
                    System.out.println("NATIVE_PRODUCER_BENCH baseline="+baseline+" warp="+warp+" size="+w+"x"+h+" frames="+measuredFrames+" cpuAvgUs="+(composeNs/measuredFrames/1000));
                    for(boolean full:new boolean[]{true,false}){
                        composition.beginFrame(null,false);SkijaHost.beginFrame(full?0x800000FF:0);SkijaHost.endFrame();
                        presenter.compose(composition,true,false,!full);
                        command.println("SAMPLE 0 0 "+(w-1)+" "+(h-1)+" "+w/2+" "+h/2);
                        var samples=Arrays.stream(response.readLine().substring(8).split(" ")).mapToInt(Integer::parseInt).toArray();
                        for(int k=0;k<samples.length;k+=4){assertEquals(0,samples[k]);assertEquals(0,samples[k+1]);assertEquals(255,samples[k+2]);}
                        if(!baseline){assertEquals(full?(long)w*h:1,presenter.readbackPixels(),"fresh counters track full/empty output");
                            assertEquals("native-d3d12",presenter.transport());}
                    }
                }
                assertTrue(pixelsChecked>70,"native pixels were measured during sustained output");
                System.out.println("NATIVE_D3D12_SKIJA warp="+warp+" pixelSamples="+pixelsChecked+" focusSuspensions="+transitions+" resize=true sparseBands=true inputShield=true");
            } catch(Throwable error){error.printStackTrace();throw error;} finally {SkijaHost.shutdown();}
        } finally {
            command.println("STOP");command.close();try{assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue());}finally{process.destroyForcibly();}
            SessionMedia.close();GLFW.glfwTerminate();
        }
    }
}
