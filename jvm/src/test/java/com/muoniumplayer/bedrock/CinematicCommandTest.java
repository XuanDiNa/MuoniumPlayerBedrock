package com.muoniumplayer.bedrock;
import java.nio.*;
import com.muoniumplayer.core.cine.CinePose;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CinematicCommandTest {
    @Test void nativeCommandHasOwnedPoseAndExactWorldAndInputLease(){
        var state=CinematicState.decode(CinematicStateTest.packet(),1010);
        var p=CinematicCommand.encode(state,new CinePose(16,67,-6,90,30,0,8),3,1010,true,true);
        var b=ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(88,p.length);assertEquals(0x4350434d,b.getInt(0));assertEquals(1,b.getInt(40));
        assertEquals(8,b.getLong(24));assertEquals(77,b.getLong(32));assertEquals(1,b.getInt(44));
        assertEquals(16,b.getDouble(48));assertEquals(90,b.getFloat(72));assertEquals(8,b.getFloat(84));
    }
    @Test void panelFocusHostLossAndInvalidPoseAlwaysRelease(){
        var state=CinematicState.decode(CinematicStateTest.packet(),1010);var pose=CinePose.of(16,67,-6,90,30,0);
        for(byte[] raw:new byte[][]{
            CinematicCommand.encode(state,pose,1,1010,true,false),CinematicCommand.encode(null,pose,1,1010,true,true),
            CinematicCommand.encode(state,null,1,1010,true,true),CinematicCommand.encode(state,pose,1,1300,true,true),
            CinematicCommand.encode(state,CinePose.of(100,67,-6,90,30,0),1,1010,true,true),
            CinematicCommand.encode(state,CinePose.of(16,67,-6,Float.NaN,30,0),1,1010,true,true)}){
            assertEquals(0,ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt(40));
        }
    }
}
