package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.ncm.music.acoustics.SpeakerState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import io.github.humbleui.skija.*;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;

class SpeakerMarkerRenderTest {
    @Test void markerProjectsAtSourceAndLeavesRestOfFrameTransparent() throws Exception {
        Class<?> type;
        try{type=Class.forName("com.muoniumplayer.bedrock.SpeakerMarker");}
        catch(ClassNotFoundException e){fail("Placed sound source has no world marker");return;}
        var draw=type.getDeclaredMethod("draw",Canvas.class,WorldFrame.class,SpeakerState.class,int.class,int.class,boolean.class);
        try(var surface=Surface.makeRasterN32Premul(1280,720)) {
            var canvas=surface.getCanvas();canvas.clear(0);
            draw.invoke(null,canvas,WorldSpeakerTest.camera(7,0),SpeakerState.place(.5,.5,4.5,"0"),1280,720,true);
            try(var image=surface.makeImageSnapshot();var data=image.encodeToData(EncodedImageFormat.PNG)) {
                var png=ImageIO.read(new ByteArrayInputStream(data.getBytes()));int ink=0;
                for(int y=0;y<720;y++)for(int x=0;x<1280;x++)if((png.getRGB(x,y)>>>24)>0){ink++;assertTrue(x>560&&x<720&&y>280&&y<440);}
                assertTrue(ink>100&&ink<5000);
            }
            canvas.clear(0);
            draw.invoke(null,canvas,WorldSpeakerTest.camera(7,0),SpeakerState.place(.5,.5,-4.5,"0"),1280,720,true);
            try(var image=surface.makeImageSnapshot();var data=image.encodeToData(EncodedImageFormat.PNG)) {
                var png=ImageIO.read(new ByteArrayInputStream(data.getBytes()));
                for(int y=0;y<720;y++)for(int x=0;x<1280;x++)assertEquals(0,png.getRGB(x,y)>>>24);
            }
        }
    }
}
