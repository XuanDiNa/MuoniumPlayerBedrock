package com.muoniumplayer.bedrock;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Session-owned HTTP object. Range responses must describe the same object. */
final class AudioRanges implements AutoCloseable {
    static final class Unsupported extends IOException {}
    private static final ScheduledThreadPoolExecutor cancellationTimer=new ScheduledThreadPoolExecutor(1,r->{var t=new Thread(r,"Muonium-stream-cancel");t.setDaemon(true);return t;});
    static { cancellationTimer.setRemoveOnCancelPolicy(true); }
    volatile ScheduledFuture<?> preparation;
    final String url;final long length;final String etag,modified;
    private final Set<org.apache.http.client.methods.HttpGet> requests=Collections.synchronizedSet(new HashSet<>());
    private final org.apache.http.impl.client.CloseableHttpClient client=org.apache.http.impl.client.HttpClients.custom()
        .disableContentCompression().disableAutomaticRetries().disableRedirectHandling()
        .setDefaultRequestConfig(org.apache.http.client.config.RequestConfig.custom().setConnectTimeout(10000).setSocketTimeout(15000).build()).build();
    volatile boolean closed;final InputStream initial;
    AudioRanges(String url,BooleanSupplier cancelled)throws IOException {
        this.url=url;ProgressiveAudio.transports.add(this);
        preparation=cancellationTimer.scheduleWithFixedDelay(()->{if(cancelled.getAsBoolean())close();},0,40,TimeUnit.MILLISECONDS);
        Response response=null;
        try{response=connect(0,false);long n=response.length();
            if(n<=0||n>Integer.MAX_VALUE)throw new IOException("Unknown or excessive audio length");
            length=n;etag=response.header("ETag");modified=response.header("Last-Modified");initial=sequential(body(response));
        }catch(IOException|RuntimeException e){if(response!=null)response.close();close();throw e;}
    }
    private final class Response implements AutoCloseable {
        final org.apache.http.client.methods.HttpGet request;final org.apache.http.client.methods.CloseableHttpResponse response;
        Response(org.apache.http.client.methods.HttpGet q,org.apache.http.client.methods.CloseableHttpResponse r){request=q;response=r;}
        String header(String key){var h=response.getFirstHeader(key);return h==null?null:h.getValue();}
        long length(){return response.getEntity()==null?-1:response.getEntity().getContentLength();}
        public void close(){requests.remove(request);request.abort();try{response.close();}catch(IOException ignored){}}
    }
    private Response connect(long start,boolean range)throws IOException {
        return connect(start,range,false);
    }
    private Response connect(long start,boolean range,boolean sequential)throws IOException {
        if(range&&((etag==null||etag.startsWith("W/"))&&modified==null))throw new Unsupported();
        String address=url;
        for(int hop=0;hop<8;hop++){
            URI uri=URI.create(address);if(!Set.of("http","https").contains(uri.getScheme()))throw new IOException("Invalid audio protocol");
            var request=new org.apache.http.client.methods.HttpGet(uri);requests.add(request);
            if(closed){requests.remove(request);request.abort();throw new InterruptedIOException("Audio released");}
            request.setHeader("User-Agent","Mozilla/5.0");request.setHeader("Accept-Encoding","identity");request.setHeader("Connection","close");
            if(range){request.setHeader("Range","bytes="+start+"-"+(sequential?"":Math.min(length-1,start+65535)));
                if(etag!=null&&!etag.startsWith("W/"))request.setHeader("If-Range",etag);
                else if(modified!=null)request.setHeader("If-Range",modified);}
            Response c;
            try{c=new Response(request,client.execute(request));}catch(IOException e){requests.remove(request);request.abort();throw e;}
            int code=c.response.getStatusLine().getStatusCode();String location=c.header("Location");
            if(Set.of(301,302,303,307,308).contains(code)&&location!=null){address=uri.resolve(location).toString();c.close();continue;}
            try{
                if(!range&&code!=200)throw new IOException("Audio response rejected");
                if(range){
                    boolean strong=etag!=null&&!etag.startsWith("W/");
                    if(strong?!Objects.equals(etag,c.header("ETag")):!Objects.equals(modified,c.header("Last-Modified")))throw new IOException("Audio object changed");
                    if(code==200)throw new Unsupported();
                    Matcher m=Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(Objects.toString(c.header("Content-Range"),""));
                    long end=sequential?length-1:Math.min(length-1,start+65535);
                    if(code!=206||!m.matches()||Long.parseLong(m.group(1))!=start||Long.parseLong(m.group(2))!=end||Long.parseLong(m.group(3))!=length||c.length()!=end-start+1)throw new IOException("Invalid audio byte range");
                }
                String encoding=c.header("Content-Encoding");if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))throw new IOException("Encoded byte ranges unsupported");
                return c;
            }catch(IOException|RuntimeException e){c.close();throw e;}
        }throw new IOException("Audio redirect bound exceeded");
    }
    private InputStream body(Response c)throws IOException {
        return new FilterInputStream(c.response.getEntity().getContent()){
            @Override public int available(){return 0;}
            @Override public void close(){c.close();}
        };
    }
    /** Continue a broken body only at its delivered byte offset, with the same
     * validator checked by connect(). Never splice a new object or restart at 0. */
    private InputStream sequential(InputStream first) {
        return new InputStream() {
            InputStream block=first;long cursor;int continuations;boolean stopped;
            public int read()throws IOException{byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;}
            public int read(byte[] bytes,int offset,int count)throws IOException {
                Objects.checkFromIndexSize(offset,count,bytes.length);if(count==0)return 0;
                while(true) {
                    if(stopped||closed)throw new InterruptedIOException("Audio released");
                    if(cursor==length)return -1;
                    try {
                        int n=block.read(bytes,offset,(int)Math.min(count,length-cursor));
                        if(n<0)throw new EOFException("Truncated sequential audio");
                        cursor+=n;return n;
                    } catch(IOException failure) {
                        boolean transport=failure instanceof SocketException || failure instanceof SocketTimeoutException
                            || failure instanceof EOFException || failure instanceof org.apache.http.ConnectionClosedException
                            || failure instanceof org.apache.http.TruncatedChunkException;
                        boolean validator=etag!=null&&!etag.startsWith("W/") || modified!=null;
                        if(!transport||!validator||cursor==0||continuations==2||stopped||closed)throw failure;
                        // A sequential decoder needs the complete remaining body.
                        // at() intentionally opens small windows for random seeks;
                        // using it here creates a TCP/TLS request every 64 KiB and
                        // exposes normal playback to repeated network starvation.
                        block.close();block=body(connect(cursor,true,true));continuations++;
                        System.out.println("PROGRESSIVE_AUDIO transport=resume attempt="+continuations+" offsetBytes="+cursor);
                    }
                }
            }
            public void close()throws IOException{stopped=true;block.close();}
        };
    }
    InputStream at(long position){
        return new InputStream(){long cursor=position,left;InputStream block;boolean stopped;
            void next()throws IOException{if(block!=null)block.close();block=body(connect(cursor,true));left=Math.min(65536,length-cursor);}
            public int read()throws IOException{byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;}
            public int read(byte[] b,int o,int n)throws IOException{Objects.checkFromIndexSize(o,n,b.length);if(n==0)return 0;if(stopped||closed)throw new InterruptedIOException("Audio released");if(cursor>=length)return -1;if(left==0)next();int r=block.read(b,o,(int)Math.min(n,left));if(r<0)throw new EOFException("Truncated audio range");cursor+=r;left-=r;return r;}
            public void close()throws IOException{stopped=true;if(block!=null)block.close();}
        };
    }
    void prepared(){var task=preparation;if(task!=null)task.cancel(false);}
    public void close(){closed=true;prepared();ProgressiveAudio.transports.remove(this);synchronized(requests){for(var q:requests)q.abort();requests.clear();}try{client.close();}catch(IOException ignored){}}
}
