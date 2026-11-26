package com.muoniumplayer.core.ncm.music;

import java.io.IOException;
import java.util.Arrays;

/** The original authorized Qishui per-sample CTR operation, without a whole song. */
public final class QishuiStreamingCipher implements AutoCloseable {
    private final byte[] key;private byte[][] ivs;
    public QishuiStreamingCipher(String auth)throws IOException{key=QishuiAudioDecryptor.bedrockStreamKey(auth);}
    public void metadata(byte[] init,int count)throws IOException{ivs=QishuiAudioDecryptor.bedrockStreamIvs(init,count);}
    public byte[] decode(byte[] packet,int index)throws IOException {
        if(ivs==null||index<0||index>=ivs.length)throw new IOException("Missing authorized sample IV");
        return QishuiAudioDecryptor.bedrockStreamDecrypt(packet,key,ivs[index]);
    }
    public void close(){Arrays.fill(key,(byte)0);if(ivs!=null)for(var iv:ivs)Arrays.fill(iv,(byte)0);ivs=null;}
}
