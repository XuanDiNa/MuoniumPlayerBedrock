"""Keep YouTube's verified CDN object across windows; never edit pinned sources."""


def apply(text):
    marker = '    private static final class ChunkedAudioStream extends InputStream {'
    begin = text.index(marker)
    end = text.index('\n    }\n', begin) + len('\n    }\n')
    text = text[:begin] + STREAM + text[end:]
    # Enforce the existing response limit while receiving, not after an
    # unbounded ofByteArray allocation. Timeout/cancellation still cover the body.
    old = 'httpClient().sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());'
    assert text.count(old) == 1
    text = text.replace(old, '''httpClient().sendAsync(request, info -> new java.net.http.HttpResponse.BodySubscriber<byte[]>() {
                    final java.net.http.HttpResponse.BodySubscriber<byte[]> delegate =
                            HttpResponse.BodySubscribers.ofByteArray();
                    java.util.concurrent.Flow.Subscription subscription;
                    long received;
                    boolean stopped;
                    public java.util.concurrent.CompletionStage<byte[]> getBody() { return delegate.getBody(); }
                    public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
                        subscription=value;delegate.onSubscribe(value);
                    }
                    public void onNext(List<java.nio.ByteBuffer> buffers) {
                        if(stopped)return;
                        for(var buffer:buffers)received+=buffer.remaining();
                        if(received>maxBytes){stopped=true;subscription.cancel();delegate.onError(new IOException("YouTube response exceeds byte limit"));}
                        else delegate.onNext(buffers);
                    }
                    public void onError(Throwable failure) {if(!stopped){stopped=true;delegate.onError(failure);}}
                    public void onComplete() {if(!stopped){stopped=true;delegate.onComplete();}}
                });''')
    from youtube_session_overlay import apply as session_overlay
    return session_overlay(text)


STREAM = '''    private static final class ChunkedAudioStream extends InputStream {
        private final String videoId;
        private final int itag;
        private String url;
        private long total, offset, windowLeft, waitedMillis;
        private int windows, windowStep;
        private InputStream current;
        private boolean progressive, queryRange;
        private volatile boolean closed;
        private String etag;

        private static final class WindowFailure extends IOException {
            final int status;
            final String reason;
            final long retryAfterMillis;
            WindowFailure(String reason,int status) {this(reason,status,0);}
            WindowFailure(String reason,int status,long delay) {
                super("YouTube stream "+reason);this.reason=reason;this.status=status;retryAfterMillis=delay;
            }
        }
        ChunkedAudioStream(String videoId,int itag,String url,long total) {
            this.videoId=videoId;this.itag=itag;this.url=url;this.total=total;
        }
        private void active()throws IOException {
            if(closed||Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("YouTube stream released");
        }
        @Override public int read()throws IOException {
            byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;
        }
        @Override public int read(byte[] bytes,int at,int count)throws IOException {
            java.util.Objects.checkFromIndexSize(at,count,bytes.length);
            if(count==0)return 0;
            active();
            if(current==null||windowLeft==0)if(!nextWindow())return -1;
            int n=current.read(bytes,at,(int)Math.min(count,windowLeft));
            if(n<0)throw new WindowFailure("internal-truncation",0);
            windowLeft-=n;offset+=n;return n;
        }
        @Override public int available() {return availableTotal(total);}
        @Override public void close()throws IOException {closed=true;closeWindow();}

        private long high() {
            if(!progressive)return windowHighFor(offset,total,windowStep);
            long span=windowStep==0?1024L*1024:windowStep==1?256L*1024:64L*1024;
            if(windows==1)span=Math.min(span,256L*1024);
            return Math.min(total>0?total-1:Long.MAX_VALUE,offset+span-1);
        }
        private void diagnostic(String action,int attempt,IOException failure) {
            String reason=failure instanceof WindowFailure f?f.reason:"transport";
            int status=failure instanceof WindowFailure f?f.status:0;
            System.out.println("YOUTUBE_STREAM stage=window action="+action+" reason="+reason+" status="+status
                    +" window="+windows+" attempt="+attempt+" offsetBytes="+offset+" totalBytes="+total
                    +" sourceClient="+sourceClient()+" proof="+(!queryParam(url,"pot").isEmpty()?"present":"absent")
                    +" urlRange="+(!queryParam(url,"range").isEmpty()?"present":"absent"));
        }
        private String sourceClient() {
            String client=queryParam(url,"c");return client.matches("[A-Z0-9_]{1,32}")?client:"unspecified";
        }
        private boolean refresh()throws IOException {
            active();String fresh=refreshStreamUrl(videoId,itag);active();
            if(fresh.isEmpty())return false;
            long format=longParam(queryParam(fresh,"itag")), length=longParam(queryParam(fresh,"clen"));
            if(format!=itag||total>0&&length>0&&length!=total)throw new WindowFailure("refreshed-object-changed",0);
            url=fresh;return true;
        }
        private boolean nextWindow()throws IOException {
            closeWindow();active();
            if(total>0&&offset>=total)return false;
            if(++windows>MAX_STREAM_WINDOWS)throw new WindowFailure("window-limit",0);
            int transportRetries=0,waits=0,attempt=0;long windowWaitMillis=0;boolean refreshed=false;
            boolean useQueryRange=queryRange,alternateTried=false;
            WindowFailure preferredFailure=null;
            while(true) {
                active();attempt++;
                // Keep the window size that this CDN actually accepted. Going
                // back to 1 MiB on every segment repeats rejected requests and
                // metadata refreshes throughout an otherwise playable track.
                try {
                    openWindow(high(),useQueryRange);
                    if(queryRange!=useQueryRange)System.out.println("YOUTUBE_STREAM stage=window action=range-selected transport="
                            +(useQueryRange?"url-query":"header")+" window="+windows+" offsetBytes="+offset+" totalBytes="+total);
                    queryRange=useQueryRange;return true;
                }
                catch(IOException failure) {
                    active();
                    if(failure instanceof WindowFailure rejected) {
                        WindowFailure f=rejected;
                        // A query fallback which itself gets a client error
                        // must not poison an otherwise supported header route.
                        // Validation failures on 200/206 remain terminal.
                        if(alternateTried&&preferredFailure!=null&&preferredFailure.status==403
                                &&f.status>=400&&f.status<500&&f.status!=429) {
                            diagnostic("range-fallback",attempt,failure);
                            f=preferredFailure;failure=f;useQueryRange=queryRange;
                        }
                        boolean quota=f.status==403||f.status==429;
                        boolean expired=f.status==410;
                        // Retry-After describes the earliest retry, not an
                        // expired URL. Do not issue refresh/shrink probes first.
                        if((quota||f.status==503)&&f.retryAfterMillis>0) {
                            long delay=f.retryAfterMillis;
                            if(windowWaitMillis+delay>MAX_WINDOW_WAITS*WINDOW_RETRY_DELAY_MS)
                                throw new WindowFailure("retry-after-limit",f.status);
                            pause(delay,attempt,failure);windowWaitMillis+=delay;waits++;continue;
                        }
                        // YouTube also supports byte windows in the URL query
                        // (used by yt-dlp's fragments). Try it on 403 without
                        // touching the signed parameters or changing the song.
                        if(!alternateTried&&(f.status==403||useQueryRange&&(f.status==400||f.status==404))) {
                            preferredFailure=f;alternateTried=true;
                            if(useQueryRange&&f.status!=403)queryRange=false;
                            useQueryRange=!useQueryRange;
                            diagnostic(useQueryRange?"url-range":"header-range",attempt,failure);continue;
                        }
                        useQueryRange=queryRange;
                        if((f.status==403||expired)&&!refreshed) {
                            refreshed=true;
                            if(refresh()){alternateTried=false;preferredFailure=null;diagnostic("refresh",attempt,failure);continue;}
                        }
                        if(quota) {
                            long previous=high();
                            int last=progressive?2:WINDOW_LADDER.length-1;
                            while(windowStep<last&&high()==previous)windowStep++;
                            if(high()<previous){alternateTried=false;preferredFailure=null;diagnostic("smaller-window",attempt,failure);continue;}
                            // A 64 KiB AAC window is only a few seconds of
                            // music: an unconditional 20 s pause starves a
                            // 12 s progressive buffer. Probe with short bounded
                            // backoff while retaining the original wait budget.
                            long delay=Math.min(4000L,1000L<<Math.min(waits,2));
                            if(windowWaitMillis+delay<=MAX_WINDOW_WAITS*WINDOW_RETRY_DELAY_MS) {
                                pause(delay,attempt,failure);windowWaitMillis+=delay;waits++;
                                alternateTried=false;preferredFailure=null;continue;
                            }
                        } else if((f.status==408||f.status>=500)&&transportRetries++<2) {
                            diagnostic("retry-same-byte",attempt,failure);continue;
                        }
                    } else if(transportRetries++<2) {
                        // The complete window is staged before any byte is
                        // exposed. Retry exactly its undelivered start offset.
                        diagnostic("retry-same-byte",attempt,failure);continue;
                    }
                    diagnostic("failed",attempt,failure);throw failure;
                }
            }
        }
        private void pause(long delay,int attempt,IOException failure)throws IOException {
            if(delay>MAX_STREAM_WAIT_MS-waitedMillis)throw new WindowFailure("wait-limit",0);
            diagnostic("wait",attempt,failure);
            long until=System.nanoTime()+delay*1_000_000L;
            while(true) {
                active();long remaining=until-System.nanoTime();if(remaining<=0)break;
                try {Thread.sleep(Math.min(100L,Math.max(1L,remaining/1_000_000L)));}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException("YouTube stream released");}
            }
            waitedMillis+=delay;
        }
        private String requestUrl(long high,boolean useQueryRange) {
            if(!useQueryRange)return url;
            // Preserve raw signed values (including %2B, %2F and order).
            int fragment=url.indexOf('#');String tail=fragment<0?"":url.substring(fragment);
            String raw=fragment<0?url:url.substring(0,fragment);int query=raw.indexOf('?');
            var parts=new java.util.ArrayList<String>();
            if(query>=0)for(String part:raw.substring(query+1).split("&",-1)) {
                String key=part.split("=",2)[0];
                if(!java.net.URLDecoder.decode(key,java.nio.charset.StandardCharsets.UTF_8).equals("range"))parts.add(part);
            }
            parts.add("range="+offset+"-"+high);
            return (query<0?raw:raw.substring(0,query))+"?"+String.join("&",parts)+tail;
        }
        private static long retryAfter(HttpResponse<?> response) {
            String value=response.headers().firstValue("retry-after").orElse("").trim();
            if(value.isEmpty())return 0;
            try {
                long seconds=Long.parseLong(value);
                return seconds<0?0:seconds>=MAX_STREAM_WAIT_MS/1000?MAX_STREAM_WAIT_MS+1:Math.max(1000,seconds*1000);
            }catch(NumberFormatException ignored){}
            try {
                long millis=java.time.Duration.between(java.time.Instant.now(),java.time.ZonedDateTime.parse(value,
                        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis();
                return Math.max(1000,Math.min(MAX_STREAM_WAIT_MS+1,millis));
            }catch(java.time.DateTimeException|ArithmeticException ignored){return 0;}
        }
        private void openWindow(long high,boolean useQueryRange)throws IOException {
            HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(requestUrl(high,useQueryRange)))
                    .timeout(Duration.ofMillis(STREAM_REQUEST_TIMEOUT_MS))
                    .header("Accept","*/*").header("Accept-Encoding","identity")
                    .header("User-Agent",USER_AGENT);
            // URL ranges already select the desired bytes. Applying the same
            // absolute HTTP range to that selected entity can return 416 (or
            // select a second, incorrect slice). Exactly one range selector.
            if(!useQueryRange)builder.header("Range","bytes="+offset+"-"+high);
            HttpResponse<byte[]> response=exchange(builder.GET().build(),AUDIO_EXCHANGE_MAX_BYTES,WINDOW_EXCHANGE_TIMEOUT_MS);
            active();int status=response.statusCode();
            if(status!=200&&status!=206)throw new WindowFailure("http-rejected",status,retryAfter(response));
            byte[] body=response.body()==null?new byte[0]:response.body();
            String encoding=response.headers().firstValue("content-encoding").orElse("identity");
            if(!encoding.equalsIgnoreCase("identity"))throw new WindowFailure("encoded-range",status);
            long declared=total;
            // A URL-selected slice can use 200 with Content-Range. Require
            // the identical offset/length/total checks; an unlabelled 200 at a
            // later offset remains unsafe and cannot reach the decoder.
            if(status==206||useQueryRange&&response.headers().firstValue("content-range").isPresent()) {
                var range=java.util.regex.Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
                        .matcher(response.headers().firstValue("content-range").orElse(""));
                if(!range.matches())throw new WindowFailure("invalid-range",status);
                long low,end;
                try {low=Long.parseLong(range.group(1));end=Long.parseLong(range.group(2));declared=Long.parseLong(range.group(3));}
                catch(NumberFormatException invalid){throw new WindowFailure("invalid-range",status);}
                if(low!=offset||end<low||end>high||declared<=end||total>0&&declared!=total||body.length!=end-low+1)
                    throw new WindowFailure("object-or-range-changed",status);
            } else {
                // A 200 body is a complete object, never bytes at a later
                // offset. Appending it would replay the prefix inside AAC.
                if(offset!=0||total>0&&body.length!=total)throw new WindowFailure("range-ignored",status);
                declared=body.length;
            }
            if(body.length==0)throw new WindowFailure("empty-window",status);
            String tag=response.headers().firstValue("etag").orElse(null);
            if(etag!=null&&!etag.equals(tag))throw new WindowFailure("object-validator-changed",status);
            if(etag==null&&tag!=null&&!tag.startsWith("W/"))etag=tag;
            total=declared;current=new ByteArrayInputStream(body);windowLeft=body.length;
        }
        private void closeWindow()throws IOException {
            windowLeft=0;if(current!=null){InputStream owned=current;current=null;owned.close();}
        }
    }
'''
