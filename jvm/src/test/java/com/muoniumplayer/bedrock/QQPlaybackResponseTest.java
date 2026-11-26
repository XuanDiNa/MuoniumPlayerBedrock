package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.music.source.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real QQ resolver, with only its HTTP boundary replaced inside this test JVM. */
class QQPlaybackResponseTest {
    static int httpStatus;
    static String response;
    static boolean requireLoginEnvelope;
    static JsonObject sentBody;
    static String sentCookie, sentMethod, sentPath, sentQuery, sentPayload;
    static final List<String> messages = new ArrayList<>();
    static final String detail = "{\"code\":0,\"data\":[{\"type\":0,\"file\":{\"media_mid\":\"owned-mid\",\"size_128mp3\":100}}]}";
    @BeforeAll static void httpBoundary() {
        URL.setURLStreamHandlerFactory(protocol -> protocol.equals("https") ? new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) {
                return new HttpURLConnection(url) {
                    final ByteArrayOutputStream body=new ByteArrayOutputStream();
                    public void connect() {} public void disconnect() {} public boolean usingProxy(){return false;}
                    boolean isDetail(){return url.getPath().contains("fcg_play_single_song");}
                    @Override public OutputStream getOutputStream(){return body;}
                    @Override public int getResponseCode(){return isDetail()?200:httpStatus;}
                    @Override public InputStream getInputStream(){
                        if(isDetail())return new ByteArrayInputStream(detail.getBytes(StandardCharsets.UTF_8));
                        String json=body.toString(StandardCharsets.UTF_8);
                        if(json.isEmpty())for(String part:url.getQuery().split("&"))if(part.startsWith("data="))json=URLDecoder.decode(part.substring(5),StandardCharsets.UTF_8);
                        sentPayload=json;sentQuery=url.getQuery();
                        sentBody=JsonParser.parseString(json).getAsJsonObject();sentCookie=getRequestProperty("Cookie");sentMethod=getRequestMethod();sentPath=url.getPath();
                        var comm=sentBody.has("comm")?sentBody.getAsJsonObject("comm"):new JsonObject();
                        boolean valid=comm.has("authst")&&comm.get("authst").getAsString().equals("secret-key-not-for-logs")
                                &&comm.has("qq")&&comm.get("qq").getAsString().equals("12345");
                        String result=requireLoginEnvelope&&!valid?"{\"code\":0,\"req_0\":{\"code\":0,\"data\":{\"midurlinfo\":[{\"result\":104003,\"purl\":\"\"}]}}}":response;
                        return new ByteArrayInputStream(result.getBytes(StandardCharsets.UTF_8));
                    }
                    @Override public InputStream getErrorStream(){return getInputStream();}
                    @Override public Map<String,List<String>> getHeaderFields(){return Map.of();}
                };
            }
        } : null);
    }
    @BeforeEach void reset() {
        httpStatus=200;messages.clear();requireLoginEnvelope=false;sentBody=null;
        MuLog.setLogger(new MuLog.Logger(){@Override public void info(String message){messages.add(message);}});
    }
    SongUrl resolve() {
        var qq=new MuQQApi();qq.setMusicid("12345");qq.setMusicKey("secret-key-not-for-logs");
        return qq.getSongUrl(new Track(MusicSource.QQ,"id","owned-mid","Owned","Artist","Album",1000,"",false), AudioQuality.STANDARD);
    }
    @Test void requestLevelRejectionIsNotLostBehindSuccessfulItemCodes() {
        response="{\"code\":0,\"req_0\":{\"code\":104009,\"data\":{\"midurlinfo\":[{\"result\":0,\"purl\":\"\"}]}}}";
        var result=resolve();assertFalse(result.getAvailable());assertEquals(104009,result.getResultCode());
    }
    @Test void rootRejectionAlsoSurvivesWithoutMidurlinfo() {
        response="{\"code\":1000}";
        assertEquals(1000,resolve().getResultCode());
    }
    @Test void httpRejectionIsNotAnUnexplainedEmptyVkey() {
        httpStatus=403;response="forbidden";
        assertTrue(resolve().getReason().contains("403"));
    }
    @Test void validVkeyStillReturnsTheRequestedRepresentation() {
        response="{\"code\":0,\"req_0\":{\"code\":0,\"data\":{\"sip\":[\"https://cdn.example/\"],\"midurlinfo\":[{\"result\":0,\"purl\":\"owned.mp3\"}]}}}";
        var result=resolve();assertTrue(result.getAvailable());assertEquals("https://cdn.example/owned.mp3",result.getUrl());
    }
    @Test void diagnosticsNeverPrintEvenAPrefixOfTheCredential() {
        response="{\"code\":1000}";resolve();
        assertFalse(String.join("\n",messages).contains("secret"));
    }
    @Test void authenticatedPlaybackDoesNotLoseItsLoginAtTheRpcBoundary() {
        requireLoginEnvelope=true;
        response="{\"code\":0,\"req_0\":{\"code\":0,\"data\":{\"sip\":[\"https://cdn.example/\"],\"midurlinfo\":[{\"result\":0,\"purl\":\"owned.mp3\"}]}}}";
        assertTrue(resolve().getAvailable(),"A cookie login without the RPC authst envelope is rejected as 104003");
        assertTrue(sentCookie.contains("qm_keyst=secret-key-not-for-logs"));
        assertEquals("POST",sentMethod);assertEquals("/cgi-bin/musics.fcg",sentPath);
        var req=sentBody.getAsJsonObject("req_0");
        assertEquals("music.vkey.GetVkey",req.get("module").getAsString());
        assertEquals("UrlGetVkey",req.get("method").getAsString());
        assertEquals(0,req.getAsJsonObject("param").get("ctx").getAsInt());
        assertTrue(sentQuery.contains("sign="+MuSign.sign(sentPayload)),"The signature must cover the exact transmitted account envelope");
        assertFalse(req.getAsJsonObject("param").has("loginflag"));
    }
    @Test void genuineItemPermissionRejectionRemainsUnavailableDespiteAnAccountEnvelope() {
        response="{\"code\":0,\"req_0\":{\"code\":0,\"data\":{\"midurlinfo\":[{\"result\":104003,\"purl\":\"\"}]}}}";
        var result=resolve();assertFalse(result.getAvailable());assertEquals(104003,result.getResultCode());
        assertEquals("secret-key-not-for-logs",sentBody.getAsJsonObject("comm").get("authst").getAsString());
    }
}
