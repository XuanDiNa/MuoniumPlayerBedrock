package com.muoniumplayer.bedrock;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameDepthCropTest {
    @Test void oversizedVisibleBoundsKeepRequestingDepthInsteadOfTurningCaptureOff(){
        var window=BedrockClient.getInstance().getWindow();window.size(1920,1080,1);
        try(var clip=new GameDepthClip()){
            clip.beginFrame();
            clip.begin(LyricVisibilityTest.camera(),new Matrix4f(),0,0,4,0,0,.04f,false);
            clip.include(-100,-100,2200,1300);clip.end(()->{});
            assertArrayEquals(new int[]{0,0,1920,1080},clip.crop(),"Large/animated rows must continue requesting their visible area");
            clip.beginFrame();assertNull(clip.crop(),"No lyrics must still stop capture");
        }finally{window.size(800,600,1);}
    }
}
