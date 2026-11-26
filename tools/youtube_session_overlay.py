"""Use a published web context and the user's session; never edit pinned vendor."""


def apply(text):
    marker = '    private static JsonObject postMusic(String endpoint, JsonObject body, String clientName)'
    start = text.index(marker)
    end = text.index('    private static JsonObject send(', start)
    text = text[:start] + WEB + '\n' + text[end:]
    old = '''        return "1." + new java.text.SimpleDateFormat("yyyyMMdd", Locale.US)
                .format(new java.util.Date()) + ".01.00";'''
    assert text.count(old) == 1
    text = text.replace(old, '        return publishedWebContext().version;')
    # The old date-generator never threw; fetching an actual published context can.
    text = text.replace('private static String musicClientVersion(String clientName) {',
                        'private static String musicClientVersion(String clientName) throws IOException, InterruptedException {')
    old = '''            YouTubeSessionStore.markReauthRequired();
            return LoginResult.failure("Cookie 已保存，但账号接口没有回话——可能已失效，"
                    + "也可能是这个出口 IP 正在被 YouTube 验证");'''
    assert text.count(old) == 1
    text = text.replace(old, '''            if (YouTubeSessionStore.isReauthRequired())
                return LoginResult.failure("YouTube 官方页面未接受这份登录会话，请使用浏览器登录重新登录");
            // A timeout, changed API or absent account renderer does not prove expiry.
            return LoginResult.failure("Cookie 已保存，但账号尚未验证；请检查账号接口与代理线路，"
                    + "不会把这次失败直接标记为 Cookie 过期");''')
    marker = '''        try {
            JsonObject response = playerResponse(videoId);'''
    assert text.count(marker) == 1
    text = text.replace(marker, '''        try {
            if (com.muoniumplayer.bedrock.YouTubeBrowserAudio.preferred()) {
                // Its dedicated Chromium profile authenticates the official page
                // independently from the API cookie export used by account requests.
                System.out.println("YOUTUBE_SESSION stage=resolve route=official-browser result=selected");
                SourceFailureDetails.clear("youtube",videoId);
                return new ResolveResult(com.muoniumplayer.bedrock.YouTubeBrowserAudio.watchUrl(videoId),"m4a",0,0,0);
            }
            if (YouTubeSessionStore.isReauthRequired()) throw new SessionRejectedException(true);
            if (YouTubeSessionStore.isLoggedIn()) {
                ResolveResult auth = authenticatedAudio(videoId, -1, requestedQuality);
                if (auth!=null) {
                    System.out.println("YOUTUBE_SESSION stage=resolve route=web-auth result=selected");
                    SourceFailureDetails.clear("youtube",videoId);
                    return auth;
                }
            }
            System.out.println("YOUTUBE_SESSION stage=resolve route=android-anonymous credentials=not-used");
            JsonObject response = playerResponse(videoId);''')
    marker = '''        try {
            JsonObject streamingData = object(playerResponse(videoId), "streamingData");'''
    assert text.count(marker) == 1
    text = text.replace(marker, '''        try {
            if (YouTubeSessionStore.isReauthRequired()) throw new SessionRejectedException(true);
            if (YouTubeSessionStore.isLoggedIn()) {
                ResolveResult auth=authenticatedAudio(videoId,itag,Quality.STANDARD);
                if(auth!=null){SourceFailureDetails.clear("youtube",videoId);return auth.url;}
            }
            JsonObject streamingData = object(playerResponse(videoId), "streamingData");''')
    # Explicit rejection must not be swallowed by the anonymous fallback.
    for signature,end_marker,fallback in [
        ('    public static ResolveResult getSongUrl(', '    /** 一次', 'null'),
        ('    private static String refreshStreamUrl(', '    // ═', '""'),
    ]:
        begin=text.index(signature)
        end=text.index(end_marker,begin)
        part=text[begin:end]
        marker='        } catch (Throwable throwable) {'
        assert part.count(marker)==1
        part=part.replace(marker,'''        } catch (SessionRejectedException rejected) {
            if(rejected.currentSession)SourceFailureDetails.youtubeReauthRequired(videoId);
            System.out.println("YOUTUBE_SESSION stage=resolve result=session-rejected");
            return '''+fallback+''';
''' + marker)
        if fallback=='null':
            part=part.replace('            return toResolveResult(selected);',
                              '            SourceFailureDetails.clear("youtube",videoId);\n            return toResolveResult(selected);')
        text=text[:begin]+part+text[end:]
    return text


def apply_store(text):
    """Compare and mark inside the same lock used by cookie import/logout."""
    marker='    private static boolean loaded;'
    assert text.count(marker)==1
    text=text.replace(marker,marker+'\n    private static long sessionRevision;')
    marker='        public final boolean reauthRequired;'
    assert text.count(marker)==1
    text=text.replace(marker,marker+'\n        public final long revision;')
    marker='            this.reauthRequired = source.reauthRequired;'
    assert text.count(marker)==1
    text=text.replace(marker,marker+'\n            this.revision = sessionRevision;')
    marker='            data.cookies = cookies;'
    assert text.count(marker)==1
    text=text.replace(marker,marker+'\n            sessionRevision++;')
    marker='            data = new Data();'
    assert text.count(marker)==1
    text=text.replace(marker,marker+'\n            sessionRevision++;')
    marker='    public static void markReauthRequired() {'
    assert text.count(marker)==1
    text=text.replace(marker,'''    public static boolean markReauthRequired(long requestRevision) {
        synchronized (LOCK) {
            ensureLoaded();
            if(sessionRevision!=requestRevision||!snapshotLocked().loggedIn)return false;
            markReauthRequired();
            return true;
        }
    }

''' + marker)
    return text


WEB = '''    private static final class SessionRejectedException extends IOException {
        final boolean currentSession;
        SessionRejectedException(boolean currentSession) {
            super("YouTube official page did not accept the saved session");this.currentSession=currentSession;
        }
    }
    private static final class PublishedWebContext {
        final HttpClient owner;
        final String credentialKey,version,apiKey,authUser,pageId;
        final JsonObject context;
        final long created=System.nanoTime();
        PublishedWebContext(HttpClient owner,String key,JsonObject config) throws IOException {
            this.owner=owner;credentialKey=key;
            context=object(config,"INNERTUBE_CONTEXT");
            JsonObject client=object(context,"client");
            version=string(client,"clientVersion");
            if(!"WEB_REMIX".equals(string(client,"clientName"))||!version.matches("1\\\\.[0-9]{8}\\\\.[0-9]{2}\\\\.[0-9]{2}"))
                throw new IOException("YouTube Music published web context unavailable");
            String declared=string(config,"INNERTUBE_CLIENT_VERSION");
            if(!declared.isEmpty()&&!declared.equals(version))throw new IOException("YouTube Music inconsistent published context");
            String keyValue=string(config,"INNERTUBE_API_KEY");
            apiKey=keyValue.matches("[A-Za-z0-9_-]{10,100}")?keyValue:YouTubeSessionStore.INNERTUBE_API_KEY;
            String user=string(config,"SESSION_INDEX");authUser=user.matches("[0-9]{1,2}")?user:"0";
            String page=string(config,"DELEGATED_SESSION_ID");pageId=page.matches("[0-9]{1,30}")?page:"";
        }
    }
    private static volatile PublishedWebContext cachedWebContext;
    private static final Object WEB_CONTEXT_LOCK=new Object();
    private static PublishedWebContext publishedWebContext() throws IOException,InterruptedException {
        HttpClient owner=httpClient();
        YouTubeSessionStore.Session session=YouTubeSessionStore.snapshot();
        if(session.reauthRequired)throw new SessionRejectedException(true);
        String cookie=session.loggedIn?session.cookieHeader:"";
        String key;
        try {key=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(cookie.getBytes(StandardCharsets.UTF_8)))+":"+session.revision;}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IOException("SHA-256 unavailable",impossible);}
        PublishedWebContext cached=cachedWebContext;
        if(cached!=null&&cached.owner==owner&&cached.credentialKey.equals(key)
                &&System.nanoTime()-cached.created<900_000_000_000L)return cached;
        synchronized(WEB_CONTEXT_LOCK) {
            cached=cachedWebContext;
            if(cached!=null&&cached.owner==owner&&cached.credentialKey.equals(key)
                    &&System.nanoTime()-cached.created<900_000_000_000L)return cached;
            var request=HttpRequest.newBuilder(URI.create("https://music.youtube.com/"))
                .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS)).header("User-Agent",MUSIC_USER_AGENT)
                .header("Cookie",cookie.isEmpty()?"SOCS=CAI":"SOCS=CAI; "+cookie).GET().build();
            var response=exchange(request,METADATA_MAX_BYTES,REQUEST_TIMEOUT_MS);
            if(response.statusCode()!=200)throw new IOException("YouTube Music context HTTP "+response.statusCode());
            String html=new String(response.body(),StandardCharsets.UTF_8);
            int at=0;
            while((at=html.indexOf("ytcfg.set(",at))>=0) {
                at+="ytcfg.set(".length();
                try {
                    var reader=new com.google.gson.stream.JsonReader(new java.io.StringReader(html.substring(at)));
                    JsonElement parsed=JsonParser.parseReader(reader);
                    if(parsed.isJsonObject()&&parsed.getAsJsonObject().has("INNERTUBE_CONTEXT")) {
                        JsonObject config=parsed.getAsJsonObject();
                        JsonElement loggedIn=config.get("LOGGED_IN");
                        if(!cookie.isEmpty()&&loggedIn!=null&&loggedIn.isJsonPrimitive()
                                &&loggedIn.getAsJsonPrimitive().isBoolean()&&!loggedIn.getAsBoolean()) {
                            boolean current=YouTubeSessionStore.markReauthRequired(session.revision);
                            throw new SessionRejectedException(current);
                        }
                        cached=new PublishedWebContext(owner,key,config);cachedWebContext=cached;
                        System.out.println("YOUTUBE_SESSION stage=context source=published-web version="+cached.version);
                        return cached;
                    }
                }catch(com.google.gson.JsonParseException invalid){ /* Skip non-JSON script calls, never execute page scripts. */ }
            }
            throw new IOException("YouTube Music page did not publish a usable web context");
        }
    }
    private static JsonObject postMusic(String endpoint, JsonObject body, String clientName)
            throws IOException, InterruptedException {
        boolean mobile=MOBILE_MUSIC_CLIENT_NAME.equals(clientName);
        PublishedWebContext web=mobile?null:publishedWebContext();
        String version=mobile?MOBILE_MUSIC_CLIENT_VERSION:web.version;
        body.add("context",mobile?musicClientContext(clientName,version):web.context.deepCopy());
        String key=mobile?YouTubeSessionStore.INNERTUBE_API_KEY:web.apiKey;
        String url=MUSIC_API_ENDPOINT+endpoint+"?prettyPrint=false&alt=json&key="+key;
        HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS)).header("Accept","application/json")
            .header("Content-Type","application/json").header("User-Agent",MUSIC_USER_AGENT)
            .header("Origin",YouTubeSessionStore.ORIGIN).header("X-Origin",YouTubeSessionStore.ORIGIN)
            .header("X-YouTube-Client-Name",mobile?"21":"67").header("X-YouTube-Client-Version",version)
            .setHeader("Cookie","SOCS=CAI")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toString().getBytes(StandardCharsets.UTF_8)));
        String cookie=YouTubeSessionStore.cookieHeader();
        if(!cookie.isEmpty()) {
            builder.setHeader("Cookie","SOCS=CAI; "+cookie);
            String authorization=YouTubeSessionStore.authorization(mobile?YouTubeSessionStore.MOBILE_ORIGIN:YouTubeSessionStore.ORIGIN);
            if(!authorization.isEmpty())builder.header("Authorization",authorization).header("X-Goog-AuthUser",mobile?"0":web.authUser);
            if(!mobile&&!web.pageId.isEmpty())builder.header("X-Goog-PageId",web.pageId);
        }
        if(!mobile) {
            String visitor=string(object(web.context,"client"),"visitorData");
            if(!visitor.isEmpty())builder.header("X-Goog-Visitor-Id",visitor);
        }
        return send(builder.build(),METADATA_MAX_BYTES);
    }
    private static ResolveResult authenticatedAudio(String videoId,int itag,Quality quality) throws SessionRejectedException {
        try {
            JsonObject body=new JsonObject();body.addProperty("videoId",videoId.trim());
            body.addProperty("contentCheckOk",true);body.addProperty("racyCheckOk",true);
            JsonObject response=postMusic("player",body,MUSIC_CLIENT_NAME);
            if(!"OK".equals(string(object(response,"playabilityStatus"),"status"))) {
                System.out.println("YOUTUBE_SESSION stage=resolve route=web-auth result=playability-unavailable");return null;
            }
            JsonObject streaming=object(response,"streamingData");JsonArray formats=array(streaming,"adaptiveFormats");
            if(formats==null)formats=array(streaming,"formats");
            if(formats==null){System.out.println("YOUTUBE_SESSION stage=resolve route=web-auth result=no-formats");return null;}
            JsonObject selected=null;
            if(itag<0)selected=selectAudioFormat(formats,quality);
            else for(JsonElement value:formats)if(value.isJsonObject()&&longValue(value.getAsJsonObject(),"itag")==itag){selected=value.getAsJsonObject();break;}
            if(selected==null){System.out.println("YOUTUBE_SESSION stage=resolve route=web-auth result=no-direct-aac");return null;}
            String url=string(selected,"url").trim();
            if(!url.isEmpty()&&isDecodableAudioFormat(selected))return toResolveResult(selected);
        }catch(SessionRejectedException rejected) {throw rejected;}
        catch(Exception failure) {
            System.out.println("YOUTUBE_SESSION stage=resolve route=web-auth result=unavailable reason="+failure.getClass().getSimpleName());
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
        }
        return null;
    }
'''
