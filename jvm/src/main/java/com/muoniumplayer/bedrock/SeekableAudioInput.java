package com.muoniumplayer.bedrock;

import java.io.*;

/** Logical input cursor for JAAD. No sparse encoded file or whole-file cache. */
public final class SeekableAudioInput extends InputStream {
    final AudioRanges remote;InputStream current;long position;
    SeekableAudioInput(AudioRanges remote,InputStream initial){this.remote=remote;current=initial;}
    public boolean canSeek(){return remote.canSeek();}
    public void seek(long offset)throws IOException {
        if(offset==position)return;
        if(offset<0||remote.length>=0&&offset>remote.length)throw new IOException("Invalid audio offset");
        if(offset>position&&offset-position<65536){skipNBytes(offset-position);return;}
        if(!canSeek())throw new AudioRanges.Unsupported();
        current.close();current=remote.from(offset);position=offset;
    }
    public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}
    public int read(byte[] b,int o,int n)throws IOException {
        int got=current.read(b,o,n);if(got>0)position+=got;return got;
    }
    public long skip(long count)throws IOException {
        if(count<=0)return 0;
        if(count>65536){seek(Math.addExact(position,count));return count;}
        byte[] bytes=new byte[(int)Math.min(count,8192)];long read=0;
        while(read<count){int n=read(bytes,0,(int)Math.min(bytes.length,count-read));if(n<0)throw new EOFException();read+=n;}return read;
    }
    public void close()throws IOException{current.close();}
}
