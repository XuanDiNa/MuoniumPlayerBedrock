"""Narrow source-failure handling; keep pinned upstream files unchanged."""

def once(text, old, new):
    assert text.count(old) == 1, old[:100]
    return text.replace(old, new)


def apply(name, text, method_body):
    feedback = 'com.muoniumplayer.core.ncm.music.SourceFailureDetails'
    if name == 'core/music/source/MuQQApi.java':
        text = once(text, '        JsonObject data = vkeyRequest(param);', '''        JsonObject data;
        try { data = vkeyRequest(param); }
        catch (VkeyResponseException failure) {
            System.err.println("MUSIC_SOURCE_FAILURE source=qq stage=vkey http="+failure.httpStatus+" api="+failure.resultCode);
            return SongUrl.unavailable(quality, failure.getMessage(), failure.resultCode);
        }''')
        signature = '    private JsonObject vkeyRequest(Map<String, Object> param)'
        text = once(text, signature, '''    private static final class VkeyResponseException extends RuntimeException {
        final int httpStatus, resultCode;
        VkeyResponseException(int httpStatus, int resultCode) {
            super(httpStatus != 200 ? "QQ 音乐取流 HTTP " + httpStatus
                    : resultCode != -1 ? "QQ 音乐取流接口拒绝请求（代码 " + resultCode + "）"
                    : "QQ 音乐取流响应格式无效");
            this.httpStatus=httpStatus; this.resultCode=resultCode;
        }
    }
''' + signature)
        text = method_body(text, 'private JsonObject vkeyRequest(Map<String, Object> param)', '''        // Never log any portion of keys, cookies or the signed request body.
        MuLog.info("QQ vkey req: loggedIn="+getLoggedIn());
        JsonObject body = new JsonObject();
        // Carry the same account envelope used by the other authenticated RPCs.
        body.add("comm", GSON.toJsonTree(buildComm(null)));
        JsonObject request = new JsonObject();
        request.addProperty("module", "music.vkey.GetVkey");
        request.addProperty("method", "UrlGetVkey");
        Map<String,Object> playbackParam=new LinkedHashMap<>(param);
        playbackParam.remove("loginflag");playbackParam.remove("platform");playbackParam.put("ctx",0);
        request.add("param", GSON.toJsonTree(playbackParam));
        body.add("req_0", request);
        String payload=GSON.toJson(body);
        Map<String,String> query=linkedStr("_",loginNonce(),"sign",MuSign.sign(payload));
        MuHttp.Response resp = http.postJson("https://u.y.qq.com/cgi-bin/musics.fcg", payload, query,
                linkedStr("Referer", "https://y.qq.com/"), credentialCookies());
        if (resp.getStatus()!=200) throw new VkeyResponseException(resp.getStatus(), -1);
        try {
            JsonObject json = JsonParser.parseString(resp.getBody()).getAsJsonObject();
            int rootCode=intOr(json,"code",0);
            if(rootCode!=0)throw new VkeyResponseException(200,rootCode);
            JsonObject req0=JsonExt.objOrNull(json,"req_0");
            if(req0==null)throw new VkeyResponseException(200,-1);
            int requestCode=intOr(req0,"code",-1);
            if(requestCode!=0)throw new VkeyResponseException(200,requestCode);
            JsonObject data=JsonExt.objOrNull(req0,"data");
            if(data==null)throw new VkeyResponseException(200,-1);
            return data;
        } catch (VkeyResponseException failure) { throw failure; }
        catch (RuntimeException invalid) { throw new VkeyResponseException(200,-1); }''')
        text = once(text, '        MuLog.info("QQ vkey " + mid + ": 无 purl, result=" + result + " → " + reason);', '''        System.err.println("MUSIC_SOURCE_FAILURE source=qq stage=vkey-item http=200 api="+result
                +" loggedIn="+getLoggedIn()+" candidates="+cands.size());
        MuLog.info("QQ vkey " + mid + ": 无 purl, result=" + result + " → " + reason);''')
    elif name == 'core/ncm/spotify/SpotifyMusicService.java':
        text = once(text, '            logFailure("搜索", throwable);', '''            logFailure("搜索", throwable);
            if(out.isEmpty())com.muoniumplayer.core.ncm.music.SourceFailureDetails.spotifySearchFailed(throwable);''')
    elif name == 'core/ncm/music/BilibiliMusicService.java':
        marker = 'throw new IllegalStateException("HTTP " + response.statusCode());'
        assert text.count(marker)==2
        text = text.replace(marker, 'throw new '+feedback+'.ApiHttpException("bilibili", response.statusCode());')
        marker = '            System.err.println("[Music/Bilibili] Song URL failed for "'
        # Preserve the upstream catch/log. Record only structured, bounded evidence.
        start = text.index(marker)
        text = text[:start] + '            '+feedback+'.bilibiliFailed(sourceId, throwable);\n' + text[start:]
        marker = '        DirectVideo direct = parseSourceId(sourceId);'
        # Resolve has its own sourceId parameter. No changes to search/listing.
        resolve = text.index('private static ResolveResult resolve(')
        at = text.index(marker, resolve)
        text = text[:at] + '        '+feedback+'.clear("bilibili", sourceId);\n' + text[at:]
        text = once(text, '''            Track track = loadVideo(direct.bvid, direct.page);
            if (track == null) return null;''', '''            Track track = loadVideoForPlayback(direct.bvid, direct.page);
            if (track == null) return null;''')
        text = once(text, '            JsonObject response = getJson(url.toString(), hires);',
                    '            JsonObject response = playbackJson(url.toString());')
        marker = '    private static Track loadVideo(String videoId, int page) throws Exception {'
        text = once(text, marker, '''    private static Track loadVideoForPlayback(String videoId,int page)throws Exception {
        JsonObject response=playbackJson(API_ROOT+"/view?"+viewIdQuery(videoId));
        requireSuccessful(response,"view");
        return trackFromViewData(object(response,"data"),videoId,page);
    }

''' + marker)
        marker = '    private static JsonObject getJson(String url) throws Exception {'
        text = once(text, marker, '''    private static JsonObject playbackJson(String url)throws Exception {
        String stage=URI.create(url).getPath().endsWith("/view")?"view":"playurl";
        String cookie=BilibiliSessionStore.getCookieHeader();
        // Only fixed API URLs enter here; never send account cookies to audio CDNs.
        HttpRequest request=jsonRequest(url,cookie);
        boolean account=java.util.Arrays.stream(cookie.split(";\\\\s*")).anyMatch(v->v.startsWith("SESSDATA="));
        boolean device=java.util.Arrays.stream(cookie.split(";\\\\s*")).anyMatch(v->v.startsWith("buvid3="));
        System.out.println("BILIBILI_PLAYBACK_REQUEST stage="+stage+" accountCookie="+account+" deviceCookie="+device);
        HttpResponse<String> response=HTTP.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if(response.statusCode()!=200)
            throw new SourceFailureDetails.ApiHttpException("bilibili",response.statusCode(),stage);
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

''' + marker)
        marker = '''    private static HttpRequest jsonRequest(String url, boolean useCookie) {
        HttpRequest.Builder builder'''
        text = once(text, marker, '''    private static HttpRequest jsonRequest(String url, boolean useCookie) {
        return jsonRequest(url,useCookie?BilibiliSessionStore.getCookieHeader():"");
    }

    private static HttpRequest jsonRequest(String url,String cookie) {
        HttpRequest.Builder builder''')
        text = once(text, '''        if (useCookie) {
            String cookie = BilibiliSessionStore.getCookieHeader();
            if (!cookie.isEmpty()) builder.header("Cookie", cookie);
        }''', '''        if (!cookie.isEmpty()) builder.header("Cookie", cookie);''')
    elif name == 'core/ncm/kugou/KuGouMusicApi.java':
        marker = '            String reason = failProcessReason(root);'
        text = once(text, marker, marker+'\n            '+feedback+'.kugouDenied(track.hash, root);')
        marker = '        System.err.println("[Music/KuGou] 跳过 "'
        at = text.index(marker)
        text = text[:at]+'        '+feedback+'.kugouRestricted(track.hash);\n'+text[at:]
    elif name == 'core/ncm/music/KuGouMusicService.java':
        marker = '            KuGouMusicApi.Track track = music.toKuGouTrack();'
        at = text.index(marker, text.index('public static Tuple<String, String> getSongUrl('))
        end = at+len(marker)
        text = text[:end]+'\n            '+feedback+'.clear("kugou", track.hash);'+text[end:]
        marker = '            return stream == null ? null : new Tuple<String, String>(stream.url, stream.format);'
        text = once(text, marker, '            if(stream!=null)'+feedback+'.clear("kugou", track.hash);\n'+marker)
    elif name == 'core/ncm/music/CloudMusic.java':
        text = method_body(text, 'private void handleUnplayableSong(Music song)', '''            String reason=SourceFailureDetails.forSong(song);
            api.printMessage(EnumChatColor.RED + "无法播放: " + song.getName() + " - " + song.getArtistsName()+" · "+reason);
            DownloadDynamicIsland.showPlaybackFailure(song.getName(), reason);
            System.err.println("MUSIC_PLAYBACK_UNAVAILABLE source="+song.getSource()+" reason="+reason);''')
        marker = '        private String describePlayerInitializationFailure(Throwable e, String rawMessage) {'
        text = once(text, marker, marker+'''
            for(Throwable current=e;current!=null;current=current.getCause())
                if(current instanceof com.muoniumplayer.bedrock.ProgressiveAudio.SourceHttpException http)
                    return "音频服务器拒绝或暂时无法提供音频（HTTP "+http.status+"），请稍后重试";''')
    return text
