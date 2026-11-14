package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.utils.network.HttpUtils;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Bounded, ephemeral HLS transport. Unencrypted TS/ADTS and AAC-LC fMP4; no disk. */
public final class LiveRadioTransport {
    private static final int PLAYLIST_LIMIT=256*1024,SEGMENT_LIMIT=8*1024*1024;
    private static InputStream response(String url)throws IOException {
        URI u=URI.create(url);
        if(!Set.of("http","https").contains(u.getScheme()))throw new IOException("Unsupported radio URL scheme");
        return new FilterInputStream(HttpUtils.get(url,null,Map.of("User-Agent","Mozilla/5.0","Icy-MetaData","0","Connection","close"),10_000,15_000)) {
            @Override public int available(){return 0;}
        };
    }
    public static InputStream open(String url)throws IOException {
        var input=new BufferedInputStream(response(url),8192);input.mark(16);
        byte[] head=input.readNBytes(7);input.reset();
        if(new String(head,StandardCharsets.US_ASCII).startsWith("#EXTM3U")) {
            String text=new String(readBounded(input,PLAYLIST_LIMIT),StandardCharsets.UTF_8);input.close();
            return new Hls(url,text);
        }
        return input;
    }
    static byte[] readBounded(InputStream in,int max)throws IOException {
        var out=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;
        while((n=in.read(block))!=-1){if(out.size()+n>max)throw new IOException("Radio response exceeded bound");out.write(block,0,n);}
        return out.toByteArray();
    }
    static String resolve(String base,String child)throws IOException {
        try{String u=URI.create(base).resolve(child.trim()).toString();
            if(!Set.of("http","https").contains(URI.create(u).getScheme()))throw new IOException("Unsupported HLS URI");return u;
        }catch(IllegalArgumentException e){throw new IOException("Invalid HLS URI");}
    }
    private static final class Hls extends InputStream {
        String url,text;boolean ended;volatile boolean closed;long last=-1;int target=4,reloads,masterDepth;
        final ArrayDeque<Segment> segments=new ArrayDeque<>();
        volatile org.apache.http.client.methods.HttpGet activeRequest;
        final org.apache.http.impl.client.CloseableHttpClient client=org.apache.http.impl.client.HttpClients.custom()
            .disableContentCompression().disableAutomaticRetries().disableRedirectHandling()
            .setDefaultRequestConfig(org.apache.http.client.config.RequestConfig.custom()
                .setConnectTimeout(10_000).setSocketTimeout(15_000).build()).build();
        Init lastInit;FragmentedAac fmp4;
        ByteArrayInputStream current=new ByteArrayInputStream(new byte[0]);
        Hls(String url,String text)throws IOException {
            this.url=url;this.text=text;
            try{parse(true);}catch(IOException|RuntimeException failure){close();throw failure;}
        }
        void parse(boolean initial)throws IOException {
            if(!text.stripLeading().startsWith("#EXTM3U"))throw new IOException("Invalid HLS playlist");
            String[] lines=text.split("\\r?\\n");long sequence=0;boolean master=false;List<Segment> found=new ArrayList<>();
            Init init=null;String pendingRange=null,previousUrl=null;Range previousRange=null;
            for(String raw:lines){String s=raw.trim();
                if(s.startsWith("#EXT-X-KEY:")&&!"NONE".equals(attributes(s).get("METHOD")))throw new IOException("Encrypted HLS is not supported");
                if(s.startsWith("#EXT-X-MAP:")) {
                    var fields=attributes(s);String child=fields.get("URI");if(child==null||child.isBlank())throw new IOException("Missing HLS initialization URI");
                    init=new Init(resolve(url,child),fields.containsKey("BYTERANGE")?range(fields.get("BYTERANGE"),null):null);continue;
                }
                if(s.startsWith("#EXT-X-BYTERANGE:")){if(pendingRange!=null)throw new IOException("Duplicate HLS byte range");pendingRange=s.substring(s.indexOf(':')+1);continue;}
                if(s.startsWith("#EXT-X-STREAM-INF:")){master=true;continue;}
                if(s.startsWith("#EXT-X-MEDIA-SEQUENCE:"))sequence=number(s,sequence);
                if(s.startsWith("#EXT-X-TARGETDURATION:"))target=(int)Math.max(1,Math.min(10,number(s,4)));
                if(s.equals("#EXT-X-ENDLIST"))ended=true;
                if(s.isEmpty()||s.startsWith("#"))continue;
                if(master){if(++masterDepth>3)throw new IOException("HLS nesting exceeded bound");url=resolve(url,s);reload();parse(true);return;}
                String address=resolve(url,s);
                Range range=pendingRange==null?null:range(pendingRange,Objects.equals(address,previousUrl)?previousRange:null);
                found.add(new Segment(sequence++,address,range,init));previousUrl=address;previousRange=range;pendingRange=null;
                if(found.size()>1024)throw new IOException("HLS segment count exceeded bound");
            }
            if(pendingRange!=null)throw new IOException("Dangling HLS byte range");
            int start=initial&&!ended?Math.max(0,found.size()-2):0;
            for(int i=start;i<found.size();i++){Segment s=found.get(i);if(s.sequence>last)segments.add(s);}
        }
        long number(String s,long fallback){try{return Long.parseLong(s.substring(s.indexOf(':')+1).trim());}catch(Exception e){return fallback;}}
        void reload()throws IOException {
            text=new String(fetch(url,null,PLAYLIST_LIMIT),StandardCharsets.UTF_8);
        }
        boolean advance()throws IOException {
            while(!closed){
                Segment segment=segments.poll();
                if(segment!=null){last=segment.sequence;byte[] raw;
                    if(segment.init!=null&&!segment.init.equals(lastInit)) {
                        var next=FragmentedAac.initialization(fetch(segment.init.url,segment.init.range));
                        if(fmp4!=null&&!fmp4.sameAudio(next))throw new IOException("HLS audio configuration changed; reopen station");
                        fmp4=next;lastInit=segment.init;
                    }
                    raw=fetch(segment.url,segment.range);
                    byte[] audio=segment.init==null?demux(raw):fmp4.fragment(raw);
                    current=new ByteArrayInputStream(audio);reloads=0;if(audio.length>0)return true;continue;
                }
                if(ended)return false;
                if(++reloads>12)throw new IOException("HLS playlist stopped advancing");
                try{Thread.sleep(Math.min(2000,target*500L));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("Radio stopped");}
                if(closed)return false;reload();parse(false);
            }return false;
        }
        byte[] fetch(String url,Range range)throws IOException {
            return fetch(url,range,range==null?SEGMENT_LIMIT:range.length);
        }
        byte[] fetch(String url,Range range,int limit)throws IOException {
            for(int hop=0;hop<8;hop++) {
                if(closed)throw new InterruptedIOException("Radio stopped");
                URI uri;try{uri=URI.create(url);}catch(IllegalArgumentException bad){throw new IOException("Invalid HLS URI",bad);}
                if(!Set.of("http","https").contains(uri.getScheme()))throw new IOException("Unsupported HLS URI");
                var request=new org.apache.http.client.methods.HttpGet(uri);activeRequest=request;
                request.setHeader("User-Agent","Mozilla/5.0");request.setHeader("Accept-Encoding","identity");
                request.setHeader("Connection","close");
                if(range!=null)request.setHeader("Range","bytes="+range.start+"-"+(range.start+range.length-1));
                if(closed){request.abort();throw new InterruptedIOException("Radio stopped");}
                try(var reply=client.execute(request)) {
                    int code=reply.getStatusLine().getStatusCode();String location=header(reply,"Location");
                    if(Set.of(301,302,303,307,308).contains(code)&&location!=null){url=resolve(url,location);request.abort();continue;}
                    String encoding=header(reply,"Content-Encoding");
                    if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))throw new IOException("Encoded HLS response unsupported");
                    if(range!=null) {
                        var match=Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matcher(Objects.toString(header(reply,"Content-Range"),""));
                        if(code!=206||!match.matches()||!match.group(1).equals(Long.toString(range.start))
                            ||!match.group(2).equals(Long.toString(range.start+range.length-1))
                            ||!match.group(3).equals("*")&&Long.parseLong(match.group(3))<=range.start+range.length-1
                            ||reply.getEntity()==null||reply.getEntity().getContentLength()!=range.length)
                            throw new IOException("Invalid HLS byte range response");
                    }else if(code!=200)throw new IOException("HLS response rejected");
                    if(reply.getEntity()==null)throw new EOFException("Missing HLS response body");
                    byte[] data=readBounded(reply.getEntity().getContent(),limit);
                    if(range!=null&&data.length!=range.length)throw new EOFException("Truncated HLS range");
                    if(closed)throw new InterruptedIOException("Radio stopped");return data;
                }catch(IllegalArgumentException invalid){throw new IOException("Invalid HLS response",invalid);}
                finally{request.abort();if(activeRequest==request)activeRequest=null;}
            }
            throw new IOException("HLS redirect bound exceeded");
        }
        static String header(org.apache.http.HttpResponse response,String name){var h=response.getFirstHeader(name);return h==null?null:h.getValue();}
        @Override public int read()throws IOException {if(closed)return -1;int n=current.read();while(n<0&&advance())n=current.read();return n;}
        @Override public int read(byte[] b,int o,int n)throws IOException {Objects.checkFromIndexSize(o,n,b.length);if(n==0)return 0;if(closed)return -1;int r=current.read(b,o,n);while(r<0&&advance())r=current.read(b,o,n);return r;}
        @Override public void close()throws IOException {
            closed=true;var request=activeRequest;if(request!=null)request.abort();client.close();
        }
    }
    record Range(long start,int length){}
    record Init(String url,Range range){}
    record Segment(long sequence,String url,Range range,Init init){}
    static Range range(String text,Range previous)throws IOException {
        try {
            var match=Pattern.compile("(\\d+)(?:@(\\d+))?").matcher(text.trim());if(!match.matches())throw new IOException("Invalid HLS byte range");
            long n=Long.parseLong(match.group(1)),at=match.group(2)!=null?Long.parseLong(match.group(2)):previous==null?-1:previous.start+previous.length;
            if(n<=0||n>SEGMENT_LIMIT||at<0||at>Long.MAX_VALUE-n)throw new IOException("HLS byte range exceeded bound");return new Range(at,(int)n);
        }catch(NumberFormatException bad){throw new IOException("HLS byte range exceeded bound",bad);}
    }
    static Map<String,String> attributes(String text)throws IOException {
        var values=new HashMap<String,String>();var matcher=Pattern.compile("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]+)(?:,|$)").matcher(text.substring(text.indexOf(':')+1));int end=0;
        while(matcher.find()) {
            if(matcher.start()!=end)throw new IOException("Malformed HLS attributes");end=matcher.end();
            String value=matcher.group(2);if(value.startsWith("\""))value=value.substring(1,value.length()-1);
            if(values.put(matcher.group(1),value)!=null)throw new IOException("Duplicate HLS attribute");
        }
        if(end!=text.length()-text.indexOf(':')-1)throw new IOException("Malformed HLS attributes");return values;
    }
    static byte[] demux(byte[] ts)throws IOException {
        if(ts.length>1&&(ts[0]&255)==255&&(ts[1]&224)==224)return ts; // ADTS/raw audio segment.
        var audio=new ByteArrayOutputStream();int audioPid=-1,remaining=-1;
        for(int p=0;p+188<=ts.length;p+=188){
            if(ts[p]!=71)throw new IOException("Unsupported HLS segment transport");
            if((ts[p+1]&128)!=0)continue;
            int pid=((ts[p+1]&31)<<8)|(ts[p+2]&255),flags=ts[p+3]&255;
            if((flags&192)!=0)throw new IOException("Scrambled HLS transport");
            if((flags&16)==0)continue;
            int start=p+4;if((flags&32)!=0)start+=1+(ts[start]&255);int end=p+188;
            if(start>=end)continue;
            if((ts[p+1]&64)!=0){
                if(start+9>end||ts[start]!=0||ts[start+1]!=0||ts[start+2]!=1)continue;
                int stream=ts[start+3]&255;
                if(stream<192||stream>223)continue;
                if(audioPid!=-1&&pid!=audioPid)continue;audioPid=pid;
                int pes=((ts[start+4]&255)<<8)|(ts[start+5]&255),header=3+(ts[start+8]&255);
                remaining=pes==0?-1:pes-header;start+=6+header;
            }
            if(pid!=audioPid||start>=end)continue;
            int n=remaining<0?end-start:Math.min(end-start,remaining);
            if(n>0)audio.write(ts,start,n);if(remaining>=0)remaining-=n;
        }
        if(audio.size()==0)throw new IOException("HLS has no supported audio PES");return audio.toByteArray();
    }
}
