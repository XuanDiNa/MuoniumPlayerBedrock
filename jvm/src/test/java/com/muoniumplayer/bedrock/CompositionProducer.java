package com.muoniumplayer.bedrock;
import com.muoniumplayer.render.skija.SkijaHost;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;
import org.lwjgl.glfw.GLFW;
import java.nio.*;
/** Disposable real Skija/GL producer, launched only by the native fixture. */
public final class CompositionProducer {
    public static void main(String[] args)throws Exception{
        System.setProperty("muonium.bedrock.offlineTest","true");
        if(!GLFW.glfwInit())throw new IllegalStateException("GLFW");
        try(var windows=OverlayWindows.create();var target=new NativeComposition(args[0]);var presenter=new LayeredPresenter(windows.handle())){
            if(!target.available())throw new IllegalStateException("Native fixture endpoint");
            var client=BedrockClient.getInstance();client.bindWindow(windows.display());client.getWindow().size(64,64,1);
            com.muoniumplayer.MuoniumPlayerClient.getInstance().init();presenter.resize(64,64);SkijaHost.renderTarget(presenter.framebuffer());
            var packet=ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);long now=com.sun.jna.platform.win32.Kernel32.INSTANCE.GetTickCount64();
            packet.putInt(0,0x4357504d).putInt(4,1).putLong(8,1).putLong(16,now).putLong(24,7).putInt(32,1);
            packet.putFloat(64,1).putFloat(84,1).putFloat(104,1).putFloat(124,1);
            packet.putFloat(128,1).putFloat(148,1).putFloat(168,-1.01f).putFloat(172,-1).putFloat(184,-.2f);
            packet.putDouble(208,-1).putDouble(216,90);var world=WorldFrame.decode(packet.array(),now);if(world==null)throw new IllegalStateException("fixture world");
            target.beginFrame(world,true);SkijaHost.beginFrame(0);
            try(var stage=new StageLyrics();var paint=new Paint().setColor(0xFF00FF00)){
                stage.composition(target);stage.render(System.nanoTime(),world);
                stage.pushBillboard(0,0,-2,0,0,.02f);stage.drawPixelText("Skija",-18,-8,0xFFFFFFFF,false);stage.popBillboard();
                SkijaHost.canvas().drawRect(Rect.makeXYWH(4,8,16,16),paint);
            }finally{SkijaHost.endFrame();}
            presenter.compose(target,true,true,false);
            new OverlayWindowMode(windows.display()).update(false);
            presenter.present(0,0);
            // Negotiate using the DIB already submitted to ULW, even if a later
            // GL frame has different pixels. A second readback would export red.
            SkijaHost.beginFrame(0xFFFF0000);SkijaHost.endFrame();
            presenter.probe(target);
            System.out.println("SKIJA_COMPOSITION_PRODUCED planes-and-screen");
            System.out.println("SKIJA_COMPOSITION_PROBE reused-existing-DIB");
            SkijaHost.shutdown();
        }finally{SkijaHost.shutdown();SessionMedia.close();GLFW.glfwTerminate();}
        System.exit(0);
    }
}
