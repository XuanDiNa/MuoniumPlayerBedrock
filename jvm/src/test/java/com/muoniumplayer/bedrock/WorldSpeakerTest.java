package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import java.nio.*;
import org.joml.Matrix4f;
import com.muoniumplayer.core.ncm.music.acoustics.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldSpeakerTest {
    // Hand-built world: eye (.5,.5,.5), looking +Z; stone face at Z=4.
    static EnvironmentFrame environment() {
        byte[] raw=EnvironmentFrameTest.packet();
        for(int y=-1;y<=2;y++)for(int x=-2;x<=2;x++)cell(raw,x,y,4,1);
        return EnvironmentFrame.decode(raw,1001,7);
    }
    static void cell(byte[] raw,int x,int y,int z,int material) {
        raw[256+((y+16)*33+z+16)*33+x+16]=(byte)material;
    }
    static WorldFrame camera(long epoch,int dimension) {
        var raw=WorldFrameTest.packet(1000);var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(24,epoch).putInt(36,dimension).putDouble(40,.5).putDouble(48,.5).putDouble(56,.5);
        float[] a=new float[16];new Matrix4f().lookAt(.5f,.5f,.5f,.5f,.5f,1.5f,0,1,0).get(a);
        for(int i=0;i<16;i++)b.putFloat(64+i*4,a[i]);
        return WorldFrame.decode(raw,1001);
    }
    static Class<?> speakerType() {
        try{return Class.forName("com.muoniumplayer.bedrock.WorldSpeaker");}
        catch(ClassNotFoundException e){fail("World speaker placement/lifecycle is not connected");return null;}
    }
    static Object speaker() throws Exception {return speakerType().getDeclaredConstructor().newInstance();}
    static Object call(Object speaker,String name,Class<?>[] types,Object... args) throws Exception {
        return speakerType().getDeclaredMethod(name,types).invoke(speaker,args);
    }
    static void update(Object s,WorldFrame frame,boolean enabled) throws Exception {
        call(s,"update",new Class<?>[]{WorldFrame.class,boolean.class},frame,enabled);
    }
    static boolean toggle(Object s,EnvironmentFrame f) throws Exception {
        return (boolean)call(s,"toggle",new Class<?>[]{EnvironmentFrame.class},f);
    }
    static SpeakerState anchor(Object s) throws Exception {
        return (SpeakerState)call(s,"anchor",new Class<?>[0]);
    }
    static ListenerSample sample(EnvironmentFrame f,SpeakerState speaker) throws Exception {
        try{return (ListenerSample)EnvironmentGeometry.class.getDeclaredMethod("sample",EnvironmentFrame.class,SpeakerState.class).invoke(null,f,speaker);}
        catch(NoSuchMethodException e){fail("Geometry still emits only listener-origin sound");return null;}
    }
    @Test void placeOutsideKnownFaceThenAimAgainToRemove() throws Exception {
        var s=speaker();update(s,camera(7,0),true);assertTrue(toggle(s,environment()));
        var a=anchor(s);assertNotNull(a);assertEquals(.5,a.x(),1e-5);assertEquals(.5,a.y(),1e-5);assertEquals(3.5,a.z(),1e-5);
        assertTrue(toggle(s,environment()));assertNull(anchor(s));
    }
    @Test void invalidUnknownForeignAndNoHitMustNotPlace() throws Exception {
        var s=speaker();assertFalse(toggle(s,environment()));
        update(s,camera(7,0),true);assertFalse(toggle(s,null));
        assertFalse(toggle(s,EnvironmentFrame.decode(EnvironmentFrameTest.packet(),1001,7)));
        var distant=EnvironmentFrameTest.packet();cell(distant,0,0,8,1);
        assertFalse(toggle(s,EnvironmentFrame.decode(distant,1001,7)),"Placement cannot reach beyond six blocks");
        var raw=EnvironmentFrameTest.packet();cell(raw,0,0,1,255);cell(raw,0,0,3,1);
        assertFalse(toggle(s,EnvironmentFrame.decode(raw,1001,7)));
        update(s,camera(8,0),true);assertFalse(toggle(s,environment()));
        update(s,camera(7,1),true);assertFalse(toggle(s,environment()));
    }
    @Test void placementDoesNotEnterPartlyOccupiedMetalCell() throws Exception {
        var raw=EnvironmentShapesTest.packet();
        cell(raw,0,0,0,0);
        cell(raw,0,0,3,10);cell(raw,0,0,4,1);
        int shapeIds=256+33*33*33,dict=256+2*33*33*33;
        raw[shapeIds+((16*33+19)*33+16)]=2;
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putShort(dict+24+8,(short)1024);
        var f=EnvironmentFrame.decode(raw,1001,7);assertNotNull(f);
        assertEquals(4,EnvironmentGeometry.cast(f,.5,.5,.5,0,0,1,6).z(),1e-6,
            "The sight ray passes above the low metal shape and reaches the stone face");
        var s=speaker();update(s,camera(7,0),true);
        assertFalse(toggle(s,f),"The outward placement cell is occupied by metal, not empty air");
        assertNull(anchor(s));
    }
    @Test void hudEpochDisableAndDimensionChangesClearLocalAnchor() throws Exception {
        for(var next:new WorldFrame[]{null,camera(8,0),camera(7,1)}) {
            var s=speaker();update(s,camera(7,0),true);toggle(s,environment());assertNotNull(anchor(s));
            update(s,next,true);assertNull(anchor(s));
        }
        var s=speaker();update(s,camera(7,0),true);toggle(s,environment());
        update(s,camera(7,0),false);assertNull(anchor(s));assertFalse(toggle(s,environment()));
    }
    @Test void originalPlanReceivesDistanceAndHandCheckedStereoDirection() throws Exception {
        var f=EnvironmentFrame.decode(EnvironmentFrameTest.packet(),1001,7);
        var right=sample(f,SpeakerState.place(-7.5,.5,.5,"0"));
        assertTrue(right.hasDirectSource());assertEquals(8,right.directDistanceMeters(),1e-5);
        // Looking +Z, camera right is -X in the pinned native/Java convention.
        assertEquals(1,right.directPan(),1e-5);
        var plan=SceneResponse.respond(right,1);assertTrue(plan.hasSpeakerAnchor);
        assertEquals(1,plan.directPan,1e-5);assertEquals(1,plan.directGain,1e-5);
        assertTrue(SceneResponse.respond(sample(f,SpeakerState.place(-11.5,.5,.5,"0")),1).directGain<1);
        assertEquals(-7.5,plan.speakerX,1e-5);
        var left=sample(f,SpeakerState.place(8.5,.5,.5,"0"));assertEquals(-1,left.directPan(),1e-5);
    }
    @Test void unknownSourceAndUnknownDirectPathCannotSoundLikeClearSpace() throws Exception {
        var raw=EnvironmentFrameTest.packet();cell(raw,-3,0,0,255);
        var unknown=sample(EnvironmentFrame.decode(raw,1001,7),SpeakerState.place(-7.5,.5,.5,"0"));
        assertTrue(SceneResponse.respond(unknown,1).directGain<.0001);
        var outside=sample(EnvironmentFrame.decode(EnvironmentFrameTest.packet(),1001,7),SpeakerState.place(60,.5,.5,"0"));
        assertTrue(outside.hasDirectSource());assertEquals(0,outside.tapCount());
        assertTrue(SceneResponse.respond(outside,1).directGain<.0001);
        var pending=sample(null,SpeakerState.place(3,.5,.5,"0"));
        assertTrue(pending.hasDirectSource());assertTrue(SceneResponse.respond(pending,1).directGain<.0001);
    }
    @Test void stoneBetweenSourceAndEarChangesOriginalDirectOutputPlan() throws Exception {
        var raw=EnvironmentFrameTest.packet();var source=SpeakerState.place(-7.5,.5,.5,"0");
        var clear=sample(EnvironmentFrame.decode(raw,1001,7),source);cell(raw,-3,0,0,1);
        var blocked=sample(EnvironmentFrame.decode(raw,1001,7),source);
        assertEquals(1,blocked.directOcclusion(),1e-5);
        assertTrue(blocked.hasDiffraction(),"A single freestanding block has a known route around it");
        assertTrue(SceneResponse.respond(blocked,1).directGain<SceneResponse.respond(clear,1).directGain);
        for(int y=-16;y<=16;y++)for(int z=-16;z<=16;z++)cell(raw,-3,y,z,1);
        var wall=sample(EnvironmentFrame.decode(raw,1001,7),source);
        assertFalse(wall.hasDiffraction());
        assertTrue(SceneResponse.respond(wall,1).directGain<SceneResponse.respond(clear,1).directGain*.1);
        assertTrue(SceneResponse.respond(blocked,1).directGain>SceneResponse.respond(wall,1).directGain);
    }
    @Test void sourceOriginReflectionsUseExtraPathLengthAndRemainFinite() throws Exception {
        var raw=EnvironmentFrameTest.packet();
        for(int y=-4;y<=4;y++)for(int z=-4;z<=4;z++)for(int x=-4;x<=4;x++)
            if(Math.abs(x)==4||Math.abs(y)==4||Math.abs(z)==4)cell(raw,x,y,z,1);
        var sample=sample(EnvironmentFrame.decode(raw,1001,7),SpeakerState.place(-1.5,.5,.5,"0"));
        assertTrue(sample.tapCount()>0);assertTrue(sample.enclosure()>.9);
        for(float d:sample.tapDelaysSec())assertTrue(Float.isFinite(d)&&d>=0);
        assertEquals(2,sample.directDistanceMeters(),1e-5);
    }
    @Test void realOriginalPcmOutputMovesEnergyToSourceSide() throws Exception {
        var f=EnvironmentFrame.decode(EnvironmentFrameTest.packet(),1001,7);
        try {
            SceneAcoustics.applyTarget(SceneResponse.respond(sample(f,SpeakerState.place(-7.5,.5,.5,"0")),1));
            SceneAcoustics.flush();double left=0,right=0;
            for(int block=0;block<100;block++) {
                double[] pcm=new double[2048];
                for(int n=0;n<1024;n++)pcm[2*n]=pcm[2*n+1]=Math.sin((block*1024+n)*.075)*.12;
                SceneAcoustics.render(pcm,2,48000);
                if(block>70)for(int n=0;n<1024;n++){assertTrue(Double.isFinite(pcm[2*n]));assertTrue(Double.isFinite(pcm[2*n+1]));left+=pcm[2*n]*pcm[2*n];right+=pcm[2*n+1]*pcm[2*n+1];}
            }
            assertTrue(right>left*2,"Actual original audio output must localize, not just publish a source field");
        }finally{SceneAcoustics.clearTarget();SceneAcoustics.flush();}
    }
    @Test void unknownGeometryAlsoMutesActualStereoSideSignal() throws Exception {
        try {
            SceneAcoustics.applyTarget(SceneResponse.respond(sample(null,SpeakerState.place(3,.5,.5,"0")),1));
            SceneAcoustics.flush();double output=0,input=0;
            for(int block=0;block<100;block++) {
                double[] pcm=new double[2048];
                for(int n=0;n<1024;n++){double v=Math.sin((block*1024+n)*.075)*.12;pcm[2*n]=v;pcm[2*n+1]=-v;if(block>70)input+=2*v*v;}
                SceneAcoustics.render(pcm,2,48000);
                if(block>70)for(double v:pcm){assertTrue(Double.isFinite(v));output+=v*v;}
            }
            assertTrue(output<input*.0001,"A fixed speaker with unavailable geometry cannot leak the original stereo side at headset volume");
        }finally{SceneAcoustics.clearTarget();SceneAcoustics.flush();}
    }
    @Test void normalHeadsetModeStillPreservesStereoSamples() throws Exception {
        SceneAcoustics.clearTarget();SceneAcoustics.flush();
        double[] signal={.1,-.2,.3,-.4,-.1,.2,-.3,.4};var original=signal.clone();
        SceneAcoustics.render(signal,2,48000);assertArrayEquals(original,signal,0);
    }
}
