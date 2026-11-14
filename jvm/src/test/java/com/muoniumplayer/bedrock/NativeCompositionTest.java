package com.muoniumplayer.bedrock;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
class NativeCompositionTest {
    @Test void staleOrFailedNativeLeaseFallsBackOnceInsteadOfFlashingBetweenOutputs(){
        var route=new NativeComposition.Route();
        assertFalse(route.nativeOutput(true,false));
        assertTrue(route.nativeOutput(true,true));
        assertTrue(route.nativeOutput(true,true));
        assertFalse(route.nativeOutput(true,false));
        for(int frame=0;frame<200;frame++)assertFalse(route.nativeOutput(true,true),
            "After output loss the working external renderer owns the session");
    }
    @Test void intentionalFocusSuspensionDoesNotCountAsPresentationFailure(){
        var route=new NativeComposition.Route();assertTrue(route.nativeOutput(true,true));
        route.suspend();assertFalse(route.nativeOutput(true,false));
        assertTrue(route.nativeOutput(true,true));
    }
    @Test void unavailableCompositionDoesNotDuplicateEveryPlaybackFrame(){
        var gate=new NativeComposition.ProbeGate();int probes=0;
        for(int frame=0;frame<720;frame++)if(gate.ready(1000+frame*1000L/360))probes++;
        assertEquals(2,probes,"A refused compositor must not copy at the music/lyric frame rate");
        assertTrue(gate.ready(3000));
    }
    @Test void negotiationReusesTheAlreadyReadBottomUpDibCrop(){
        var source=ByteBuffer.allocateDirect(4*5*4);
        for(int y=0;y<5;y++)for(int x=0;x<4;x++)source.putInt((y*4+x)*4,y*10+x);
        source.position(3);source.limit(source.capacity()-1);
        var crop=ByteBuffer.allocate(2*3*4);
        NativeComposition.copyTopLeft(source,4*4+4,4*4,2,3,crop);
        int[] actual=new int[6];crop.flip().asIntBuffer().get(actual);
        assertArrayEquals(new int[]{31,32,21,22,11,12},actual);
        assertEquals(3,source.position());assertEquals(source.capacity()-1,source.limit());
    }
    @Test void packedLayoutIsBoundedAndUsesOnlyCurrentDisjointBands(){
        var top=new AlphaBounds.Region(10,250,110,270);var bottom=new AlphaBounds.Region(0,0,200,10);
        var packed=NativeComposition.Packed.of(400,300,java.util.List.of(top,bottom));
        assertEquals(200,packed.width());assertEquals(30,packed.height());
        assertThrows(IllegalArgumentException.class,()->NativeComposition.Packed.of(400,300,java.util.List.of(top,top)));
        assertThrows(IllegalArgumentException.class,()->NativeComposition.Packed.of(400,300,java.util.List.of(new AlphaBounds.Region(0,0,401,1))));
        assertEquals(0,NativeComposition.Packed.of(400,300,java.util.List.of()).height());
    }
    @Test void worldPlaneUsesAbsoluteAnchorAndOriginalBillboardBasis(){
        var p=NativeComposition.plane(100,70,-20,2,3,-4,90,0,.1f);
        assertArrayEquals(new double[]{102,73,-24},p.position(),1e-6);
        assertArrayEquals(new float[]{0,0,-.1f},p.right(),1e-5f);
        assertArrayEquals(new float[]{0,-.1f,0},p.down(),1e-5f);
    }
    @Test void missingNativeEndpointDoesNotClaimInGameComposition(){
        try(var endpoint=new NativeComposition("Local\\MuoniumBedrock-00000000000000000000000000000000")){
            assertFalse(endpoint.available());
        }
    }
}
