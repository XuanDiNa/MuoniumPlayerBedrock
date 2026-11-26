package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.ncm.music.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import static org.junit.jupiter.api.Assertions.*;

/** Replaces only the final HTTP client in this isolated test JVM, not service logic. */
class BilibiliPlaybackLoginTest {
    static final String ID="BV18LHj6XEDR";
    static final String COOKIE="SESSDATA=owned-session-secret; buvid3=owned-device-secret";
    final List<HttpRequest> requests=new ArrayList<>();
    boolean anonymousAllowed, rejectPlayurl, rejectView;
    Object originalClient,originalData;
    boolean originalLoaded;
    Class<?> session;
    java.lang.reflect.Field httpField,dataField,loadedField;
    sun.misc.Unsafe unsafe;
    @BeforeEach void isolateNetworkAndSession()throws Exception {
        var access=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");access.setAccessible(true);unsafe=(sun.misc.Unsafe)access.get(null);
        httpField=BilibiliMusicService.class.getDeclaredField("HTTP");httpField.setAccessible(true);originalClient=httpField.get(null);
        unsafe.putObjectVolatile(unsafe.staticFieldBase(httpField),unsafe.staticFieldOffset(httpField),new FixtureClient());
        session=Class.forName("com.muoniumplayer.core.ncm.music.BilibiliSessionStore");
        dataField=session.getDeclaredField("data");dataField.setAccessible(true);originalData=dataField.get(null);
        loadedField=session.getDeclaredField("loaded");loadedField.setAccessible(true);originalLoaded=loadedField.getBoolean(null);
        var type=Class.forName(session.getName()+"$Data");var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);Object data=ctor.newInstance();
        var cookies=type.getDeclaredField("cookies");cookies.setAccessible(true);cookies.set(data,new LinkedHashMap<>(Map.of("SESSDATA","owned-session-secret","buvid3","owned-device-secret")));
        dataField.set(null,data);loadedField.setBoolean(null,true);
    }
    @AfterEach void restore()throws Exception {
        unsafe.putObjectVolatile(unsafe.staticFieldBase(httpField),unsafe.staticFieldOffset(httpField),originalClient);
        dataField.set(null,originalData);loadedField.setBoolean(null,originalLoaded);
    }
    @Test void ordinaryPlaybackCarriesTheSavedAccountAndDeviceThroughBothApiSteps() {
        var result=BilibiliMusicService.getSongUrl(ID,Quality.STANDARD);
        assertNotNull(result,"Logged-in playback must not silently send anonymous view/playurl requests");
        assertEquals("https://cdn.example/owned.m4s",result.url);assertEquals(2,requests.size());
        for(var request:requests) {
            assertEquals(COOKIE,request.headers().firstValue("Cookie").orElse(""));
            assertEquals("api.bilibili.com",request.uri().getHost());
        }
        assertTrue(requests.get(0).uri().getPath().endsWith("/view"));
        assertTrue(requests.get(1).uri().getPath().endsWith("/playurl"));
        assertEquals(120000,result.track.durationMillis);
    }
    @Test void anonymousFreePlaybackStillWorksWithoutInventingAnAccountCookie()throws Exception {
        var data=dataField.get(null);var cookies=data.getClass().getDeclaredField("cookies");cookies.setAccessible(true);cookies.set(data,new LinkedHashMap<>());
        anonymousAllowed=true;
        assertNotNull(BilibiliMusicService.getSongUrl(ID,Quality.STANDARD));
        assertEquals(2,requests.size());assertTrue(requests.stream().allMatch(q->q.headers().firstValue("Cookie").isEmpty()));
    }
    @Test void authenticatedRejectionDoesNotRetryAnonymouslyOrHideTheFailingStage() {
        rejectPlayurl=true;
        assertNull(BilibiliMusicService.getSongUrl(ID,Quality.STANDARD));
        assertEquals(2,requests.size(),"One view and one rejected playurl; no identity cycling");
        String reason=SourceFailureDetails.lookup("bilibili",ID);
        assertTrue(reason.contains("412"));assertTrue(reason.contains("播放地址"),reason);
    }
    @Test void rejectedViewStopsBeforePlayurlAndIdentifiesItsStage() {
        rejectView=true;
        assertNull(BilibiliMusicService.getSongUrl(ID,Quality.STANDARD));
        assertEquals(1,requests.size());
        assertTrue(SourceFailureDetails.lookup("bilibili",ID).contains("影片信息"));
    }
    final class FixtureClient extends HttpClient {
        @Override public <T> HttpResponse<T> send(HttpRequest request,HttpResponse.BodyHandler<T> handler) {
            requests.add(request);boolean view=request.uri().getPath().endsWith("/view");
            boolean authorized=anonymousAllowed||request.headers().firstValue("Cookie").orElse("").equals(COOKIE);
            int status=authorized&&!(rejectPlayurl&&!view)&&!(rejectView&&view)?200:412;
            String body=view?"{\"code\":0,\"data\":{\"aid\":1,\"cid\":11,\"bvid\":\""+ID+"\",\"title\":\"Owned\",\"duration\":120,\"pages\":[{\"page\":1,\"cid\":11,\"duration\":120}]}}"
                    :"{\"code\":0,\"data\":{\"dash\":{\"audio\":[{\"baseUrl\":\"https://cdn.example/owned.m4s\",\"codecs\":\"mp4a.40.2\",\"bandwidth\":192000}]}}}";
            return (HttpResponse<T>)new Response(request,status,body);
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h){throw new UnsupportedOperationException();}
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p){throw new UnsupportedOperationException();}
        public Optional<CookieHandler> cookieHandler(){return Optional.empty();}public Optional<Duration> connectTimeout(){return Optional.of(Duration.ofSeconds(1));}
        public Redirect followRedirects(){return Redirect.NEVER;}public Optional<ProxySelector> proxy(){return Optional.empty();}
        public SSLContext sslContext(){throw new UnsupportedOperationException();}public SSLParameters sslParameters(){return new SSLParameters();}
        public Optional<Authenticator> authenticator(){return Optional.empty();}public Version version(){return Version.HTTP_1_1;}
        public Optional<Executor> executor(){return Optional.empty();}
    }
    record Response(HttpRequest request,int statusCode,String body) implements HttpResponse<String> {
        public Optional<HttpResponse<String>> previousResponse(){return Optional.empty();}
        public HttpHeaders headers(){return HttpHeaders.of(Map.of(),(k,v)->true);}
        public Optional<SSLSession> sslSession(){return Optional.empty();}public URI uri(){return request.uri();}
        public HttpClient.Version version(){return HttpClient.Version.HTTP_1_1;}
    }
}
