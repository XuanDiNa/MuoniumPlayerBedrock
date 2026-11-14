package com.muoniumplayer.bedrock;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FragmentedAacTest {
    static int type(byte[] raw,String name) {
        byte[] word=name.getBytes(StandardCharsets.US_ASCII);
        for(int p=4;p<=raw.length-4;p++)if(Arrays.equals(raw,p,p+4,word,0,4))return p;
        throw new AssertionError("Fixture lacks "+name);
    }
    static byte[] words(int... values)throws IOException{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);for(int value:values)out.writeInt(value);return bytes.toByteArray();}
    static byte[] join(byte[]... values)throws IOException{var out=new ByteArrayOutputStream();for(byte[] value:values)out.write(value);return out.toByteArray();}
    static byte[] box(String type,byte[]... values)throws IOException{byte[] payload=join(values);return join(words(payload.length+8),type.getBytes(StandardCharsets.US_ASCII),payload);}
    @Test void defaultSizesExplicitBaseAndMultipleRunsDecodeThroughTheRealAacDecoder()throws Exception {
        byte[] silence=HexFormat.of().parseHex("211004608c1c");
        byte[] trun=box("trun",words(0,2));
        byte[] tfhd=box("tfhd",words(0x11,1,0,0,silence.length));
        byte[] moof=box("moof",box("traf",tfhd,trun,trun));
        ByteBuffer.wrap(tfhd).order(ByteOrder.BIG_ENDIAN).putLong(16,moof.length+8);
        moof=box("moof",box("traf",tfhd,trun,trun));
        byte[] media=join(moof,box("mdat",silence,silence,silence,silence));
        byte[] adts=FragmentedAac.initialization(Fmp4RadioTest.fixture("init.mp4")).fragment(media);
        assertEquals(4*(silence.length+7),adts.length);
        try(var frames=new RadioFrames(new ByteArrayInputStream(adts))) {
            int count=0;while(frames.next()!=null)count++;assertEquals(4,count);
        }
    }
    @Test void relativeOffsetsSurviveAnUnrelatedTopLevelPrefix()throws Exception {
        var parser=FragmentedAac.initialization(Fmp4RadioTest.fixture("init.mp4"));
        byte[] original=Fmp4RadioTest.fixture("segment0.m4s");
        assertArrayEquals(parser.fragment(original),parser.fragment(join(box("free",new byte[16]),original)));
    }
    @Test void backwardCompatibleHeAacSignallingIsRejectedRatherThanDiscarded()throws Exception {
        byte[] init=Fmp4RadioTest.fixture("init.mp4");boolean changed=false;
        for(int i=0;i<init.length-5;i++)if(init[i]==0x12&&init[i+1]==0x10&&init[i+2]==0x56&&init[i+3]==(byte)0xe5) {
            init[i+4]=(byte)0x80;changed=true;break; // syncExtensionType 0x2b7, AOT 5, sbrPresentFlag=1.
        }
        assertTrue(changed);assertThrows(IOException.class,()->FragmentedAac.initialization(init));
    }
    @Test void invalidSamplesProfilesEncryptionAndTruncationAreRejectedAsIoErrors()throws Exception {
        byte[] init=Fmp4RadioTest.fixture("init.mp4"),part=Fmp4RadioTest.fixture("segment0.m4s");
        var parser=FragmentedAac.initialization(init);
        for(int at=0;at<part.length;at+=17) {
            byte[] cut=Arrays.copyOf(part,at);assertThrows(IOException.class,()->parser.fragment(cut));
        }
        byte[] encrypted=init.clone();System.arraycopy("enca".getBytes(StandardCharsets.US_ASCII),0,encrypted,type(encrypted,"mp4a"),4);
        assertThrows(IOException.class,()->FragmentedAac.initialization(encrypted));
        byte[] wrong=part.clone();ByteBuffer.wrap(wrong).putInt(type(wrong,"trun")+12,0);
        assertThrows(IOException.class,()->parser.fragment(wrong));
        byte[] tooMany=part.clone();ByteBuffer.wrap(tooMany).putInt(type(tooMany,"trun")+8,4097);
        assertThrows(IOException.class,()->parser.fragment(tooMany));
        byte[] profile=init.clone();int asc=-1;
        for(int i=0;i<profile.length-4;i++)if(profile[i]==0x12&&profile[i+1]==0x10&&profile[i+2]==0x56){asc=i;break;}
        assertTrue(asc>=0);profile[asc]=0x2a;assertThrows(IOException.class,()->FragmentedAac.initialization(profile));
        byte[] extreme=join(words(1),"moov".getBytes(StandardCharsets.US_ASCII),words(0x7fffffff,0x7fffffff));
        assertThrows(IOException.class,()->FragmentedAac.initialization(extreme));
    }
    @Test void malformedPlaylistAttributesAndUnanchoredRangesStayBounded()throws Exception {
        assertEquals("a,b.mp4",LiveRadioTransport.attributes("#EXT-X-MAP:URI=\"a,b.mp4\",BYTERANGE=\"20@3\"").get("URI"));
        assertThrows(IOException.class,()->LiveRadioTransport.attributes("#EXT-X-MAP:URI=\"a\",URI=\"b\""));
        for(String range:List.of("0@0","4","-1@0","1@-1","8388609@0","2@9223372036854775807","2@3,4"))
            assertThrows(IOException.class,()->LiveRadioTransport.range(range,null));
        assertEquals(new LiveRadioTransport.Range(15,2),LiveRadioTransport.range("2",new LiveRadioTransport.Range(10,5)));
    }
}
