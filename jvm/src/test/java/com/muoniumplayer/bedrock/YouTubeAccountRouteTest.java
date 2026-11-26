package com.muoniumplayer.bedrock;

import com.google.gson.*;
import com.muoniumplayer.core.ncm.music.*;
import org.junit.jupiter.api.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real owned HTTP origin, dummy cookies only. Never visits YouTube. */
@Timeout(12)
class YouTubeAccountRouteTest {
    Object oldClient,oldEndpoint,oldData,oldLoaded;
    byte[] priorAuth;Path authFile;
    final java.util.concurrent.atomic.AtomicInteger contexts=new java.util.concurrent.atomic.AtomicInteger();
    static Field field(Class<?> c,String name)throws Exception {var f=c.getDeclaredField(name);f.setAccessible(true);return f;}
    @BeforeEach void save()throws Exception {
        oldClient=field(YouTubeMusicService.class,"cachedClient").get(null);
        oldEndpoint=field(YouTubeMusicService.class,"cachedProxyEndpoint").get(null);
        oldData=field(YouTubeSessionStore.class,"data").get(null);
        oldLoaded=field(YouTubeSessionStore.class,"loaded").get(null);
        authFile=com.muoniumplayer.core.settings.ConfigPaths.YOUTUBE_AUTH.toPath();
        priorAuth=Files.exists(authFile)?Files.readAllBytes(authFile):null;
        assertTrue(YouTubeSessionStore.importCookie("__Secure-3PAPISID=owned-dummy; SAPISID=owned-dummy; SID=owned-session"));
    }
    @AfterEach void restore()throws Exception {
        field(YouTubeMusicService.class,"cachedClient").set(null,oldClient);
        field(YouTubeMusicService.class,"cachedProxyEndpoint").set(null,oldEndpoint);
        field(YouTubeSessionStore.class,"data").set(null,oldData);
        field(YouTubeSessionStore.class,"loaded").set(null,oldLoaded);
        if(priorAuth==null)Files.deleteIfExists(authFile);else Files.write(authFile,priorAuth);
    }
    @Test void accountRequestsUseTheVersionActuallyPublishedByTheWebsite()throws Exception {
        try(var origin=new YouTubeWindowRecoveryTest.Origin(new byte[10])) {
            install(origin);
            var account=YouTubeMusicService.getAccountInfo();
            assertNotNull(account,"A valid website context must replace the invented date-based version");
            assertEquals("Owned account",account.name);
        }
    }
    @Test void apiCookieRejectionCannotPreemptTheSeparateOfficialBrowserSession()throws Exception {
        try(var origin=new YouTubeWindowRecoveryTest.Origin(new byte[10])) {
            install(origin);
            YouTubeSessionStore.markReauthRequired();
            String offline=System.getProperty("muonium.bedrock.offlineTest");
            String browser=System.getProperty("muonium.youtube.experimentalBrowserAudio");
            try {
                System.setProperty("muonium.bedrock.offlineTest","false");
                System.setProperty("muonium.youtube.experimentalBrowserAudio","true");
                assertTrue(YouTubeBrowserAudio.preferred(),"This Windows fixture requires the installed browser");
                SourceFailureDetails.youtubeReauthRequired("ownedAuth01");
                var result=YouTubeMusicService.getSongUrl("ownedAuth01",Quality.STANDARD);
                assertNotNull(result,"API cookie status must not reject a separately authenticated official-page route");
                assertEquals(YouTubeBrowserAudio.watchUrl("ownedAuth01"),result.url);
                assertTrue(YouTubeSessionStore.snapshot().reauthRequired,"Do not falsify the API cookie status");
                assertEquals("",SourceFailureDetails.lookup("youtube","ownedAuth01"));
                assertEquals(0,contexts.get());assertEquals(0,origin.resolves.get(),"No anonymous API fallback");
                System.setProperty("muonium.youtube.experimentalBrowserAudio","false");
                assertNull(YouTubeMusicService.getSongUrl("ownedAuth01",Quality.STANDARD),"The rejected direct API route stays blocked");
            }finally {
                if(offline==null)System.clearProperty("muonium.bedrock.offlineTest");else System.setProperty("muonium.bedrock.offlineTest",offline);
                if(browser==null)System.clearProperty("muonium.youtube.experimentalBrowserAudio");else System.setProperty("muonium.youtube.experimentalBrowserAudio",browser);
            }
        }
    }
    @Test void failureToValidateAccountDoesNotAssertThatSavedCookiesExpired()throws Exception {
        try(var origin=new YouTubeWindowRecoveryTest.Origin(new byte[10])) {
            origin.server.createContext("/music",e->{try(e){e.sendResponseHeaders(503,-1);}});
            installClient(origin);
            var result=YouTubeMusicService.loginWithCookie("__Secure-3PAPISID=owned-dummy; SID=owned-session");
            assertFalse(result.ok);
            assertFalse(YouTubeSessionStore.snapshot().reauthRequired,
                "An unavailable/changed account endpoint cannot prove cookie expiry");
        }
    }
    @Test void anExplicitOfficialSignedOutPageRequestsReauthenticationWithoutErasingCookies()throws Exception {
        try(var origin=new YouTubeWindowRecoveryTest.Origin(new byte[10])) {
            install(origin);
            origin.server.removeContext("/music");
            origin.server.createContext("/music",e->{try(e){
                String body="<script>ytcfg.set({\"LOGGED_IN\":false,\"INNERTUBE_CONTEXT\":{\"client\":{\"clientName\":\"WEB_REMIX\",\"clientVersion\":\"1.20260101.06.00\"}}});</script>";
                byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);
            }});
            String original=YouTubeSessionStore.cookieHeader();
            var result=YouTubeMusicService.getSongUrl("signed-out-owned",Quality.STANDARD);
            assertTrue(YouTubeSessionStore.snapshot().reauthRequired,"Explicit official logout must request sign-in");
            assertNull(result,"A rejected saved session must not silently continue with anonymous playback");
            assertEquals(0,origin.resolves.get());
            assertEquals(original,YouTubeSessionStore.cookieHeader(),"Keep credentials for the user's next login attempt");
            assertTrue(SourceFailureDetails.lookup("youtube","signed-out-owned").contains("浏览器登录"));
        }
    }
    @Test void rejectionOfAnOlderRequestCannotInvalidateANewlyImportedSession()throws Exception {
        try(var origin=new YouTubeWindowRecoveryTest.Origin(new byte[10])) {
            install(origin);origin.server.removeContext("/music");
            origin.server.createContext("/music",e->{try(e){
                assertTrue(YouTubeSessionStore.importCookie("__Secure-3PAPISID=new-session; SID=new-session"));
                byte[] bytes="<script>ytcfg.set({\"LOGGED_IN\":false,\"INNERTUBE_CONTEXT\":{\"client\":{\"clientName\":\"WEB_REMIX\",\"clientVersion\":\"1.20260101.06.00\"}}});</script>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);
            }});
            assertNull(YouTubeMusicService.getSongUrl("old-request-owned",Quality.STANDARD));
            assertFalse(YouTubeSessionStore.snapshot().reauthRequired,"An old request cannot reject the replacement session");
            assertTrue(YouTubeSessionStore.cookieHeader().contains("new-session"));
            assertEquals(0,origin.resolves.get());
        }
    }
    @Test void importingAReplacementSessionRecoversAfterAnExplicitRejection()throws Exception {
        try(var origin=new YouTubeWindowRecoveryTest.Origin(new byte[10])) {
            install(origin);origin.server.removeContext("/music");
            origin.server.createContext("/music",e->{try(e){
                byte[] bytes="<script>ytcfg.set({\"LOGGED_IN\":false,\"INNERTUBE_CONTEXT\":{\"client\":{\"clientName\":\"WEB_REMIX\",\"clientVersion\":\"1.20260101.06.00\"}}});</script>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);
            }});
            assertNull(YouTubeMusicService.getSongUrl("relogin-owned",Quality.STANDARD));
            assertTrue(YouTubeSessionStore.snapshot().reauthRequired);
            origin.server.removeContext("/music");install(origin);
            assertTrue(YouTubeSessionStore.importCookie("__Secure-3PAPISID=replacement-session; SID=replacement-session"));
            assertNotNull(YouTubeMusicService.getSongUrl("relogin-owned",Quality.STANDARD));
            assertFalse(YouTubeSessionStore.snapshot().reauthRequired);
            assertEquals("",SourceFailureDetails.lookup("youtube","relogin-owned"));
        }
    }
    @Test void loggedInPlaybackTriesItsAuthenticatedSessionBeforeAnonymousAndroid()throws Exception {
        try(var origin=new YouTubeWindowRecoveryTest.Origin(new byte[10])) {
            install(origin);
            var result=YouTubeMusicService.getSongUrl("owned",Quality.STANDARD);
            assertNotNull(result);
            assertTrue(result.url.contains("owned-auth"),"Playback must actually use the imported account session");
            assertEquals(140,result.itag);assertEquals(128000,result.bitrate);assertEquals(10,result.contentLength);
            assertEquals(0,origin.resolves.get(),"An authenticated direct stream needs no anonymous resolve");
        }
    }
    @Test void refreshPreservesTheAuthenticatedRouteAndRequestedFormat()throws Exception {
        try(var origin=new YouTubeWindowRecoveryTest.Origin(new byte[10])) {
            install(origin);
            var refresh=YouTubeMusicService.class.getDeclaredMethod("refreshStreamUrl",String.class,int.class);
            refresh.setAccessible(true);
            assertTrue(((String)refresh.invoke(null,"owned",140)).contains("owned-auth"));
            assertEquals(0,origin.resolves.get());
        }
    }
    @Test void changingTheImportedSessionInvalidatesThePublishedContext()throws Exception {
        try(var origin=new YouTubeWindowRecoveryTest.Origin(new byte[10])) {
            install(origin);assertNotNull(YouTubeMusicService.getAccountInfo());
            assertNotNull(YouTubeMusicService.getAccountInfo());assertEquals(1,contexts.get());
            assertTrue(YouTubeSessionStore.importCookie("__Secure-3PAPISID=owned-other; SAPISID=owned-other; SID=owned-other"));
            assertNotNull(YouTubeMusicService.getAccountInfo());assertEquals(2,contexts.get());
        }
    }
    @Test void authenticatedPlaybackDecodesPastSixtyFourSecondsToTheActualEnd()throws Exception {
        try(var origin=new YouTubeWindowRecoveryTest.Origin(AllSourceStreamingTest.fixture("youtube.m4a"))) {
            install(origin);origin.segmentLimit=262144;origin.rejectDuplicateRange=true;
            origin.queryOnlyAfter=1048576;
            var result=YouTubeMusicService.getSongUrl("owned",Quality.STANDARD);assertNotNull(result);
            var token=ProgressiveAudio.prepare(result.url,()->false,null,true,80000,"owned");assertNotNull(token);
            try {
                var sample=ProgressiveAudio.claim(token);assertNotNull(sample);
                long deadline=System.nanoTime()+6_000_000_000L;
                while(ProgressiveAudio.analysisFile(token)==null&&ProgressiveAudio.state(token,64000)!=3
                        &&System.nanoTime()<deadline)Thread.sleep(10);
                assertNotNull(ProgressiveAudio.analysisFile(token));
                for(int second:new int[]{64,76}) {
                    int at=(int)(second*sample.getFrameRate())*sample.getChannelsPerFrame();double energy=0;
                    for(int i=0;i<1000;i++){double x=sample.readDouble(at+i);energy+=x*x;}
                    assertTrue(energy>.1,"Decoded non-silent PCM at "+second+"s");
                }
                assertEquals(2,ProgressiveAudio.state(token,ProgressiveAudio.durationMillis(token)+1));
                System.out.println("YOUTUBE_SESSION_PCM route=web-auth audibleSeconds=64,76 end=decoded fixture=owned");
            }finally{ProgressiveAudio.discard(token);}
        }
    }
    @Test void aConsentOrVerificationPageCannotTurnIntoAnInventedClientVersion()throws Exception {
        try(var origin=new YouTubeWindowRecoveryTest.Origin(new byte[10])) {
            origin.server.createContext("/music",e->{try(e){
                byte[] bytes="<html>Verification required</html>".getBytes();
                e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);
            }});
            installClient(origin);
            assertNull(YouTubeMusicService.getAccountInfo());
            assertFalse(YouTubeSessionStore.snapshot().reauthRequired);
        }
    }
    @Test void unavailableAuthenticatedSourcePreservesAnonymousPlayback()throws Exception {
        try(var origin=new YouTubeWindowRecoveryTest.Origin(new byte[10])) {
            origin.server.createContext("/music",e->{try(e){e.sendResponseHeaders(503,-1);}});
            origin.server.removeContext("/resolve");
            origin.server.createContext("/resolve",e->{try(e){
                origin.resolves.incrementAndGet();
                byte[] body=("{\"playabilityStatus\":{\"status\":\"OK\"},\"streamingData\":{\"adaptiveFormats\":[{\"itag\":140,\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.2\\\"\",\"url\":\""+origin.url("owned-anonymous")+"\",\"bitrate\":128000}]}}").getBytes();
                e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);
            }});
            installClient(origin);
            var result=YouTubeMusicService.getSongUrl("owned",Quality.STANDARD);
            assertNotNull(result);assertTrue(result.url.contains("owned-anonymous"));
            assertEquals(1,origin.resolves.get());
            assertFalse(YouTubeSessionStore.snapshot().reauthRequired);
        }
    }
    void install(YouTubeWindowRecoveryTest.Origin origin) {
        origin.server.createContext("/music",e->{try(e){
            String path=e.getRequestURI().getPath();String body;
            if(e.getRequestMethod().equals("GET")){
                contexts.incrementAndGet();
                body="<script>ytcfg.set({\"INNERTUBE_CLIENT_VERSION\":\"1.20260101.06.00\",\"INNERTUBE_CONTEXT\":{\"client\":{\"clientName\":\"WEB_REMIX\",\"clientVersion\":\"1.20260101.06.00\",\"visitorData\":\"owned-visitor\"},\"user\":{}},\"SESSION_INDEX\":\"0\"});</script>";
            }
            else {
                var payload=JsonParser.parseString(new String(e.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                var client=payload.getAsJsonObject("context").getAsJsonObject("client");
                boolean valid=client.get("clientVersion").getAsString().equals("1.20260101.06.00")
                    && e.getRequestHeaders().getFirst("Authorization")!=null;
                if(!valid){e.sendResponseHeaders(400,-1);return;}
                if(path.endsWith("/player"))body="{\"playabilityStatus\":{\"status\":\"OK\"},\"streamingData\":{\"adaptiveFormats\":[{\"itag\":140,\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.2\\\"\",\"url\":\""+origin.url("owned-auth")+"\",\"contentLength\":\"10\",\"bitrate\":128000}]}}";
                else body="{\"data\":{\"activeAccountHeaderRenderer\":{\"accountName\":{\"simpleText\":\"Owned account\"},\"channelHandle\":{\"simpleText\":\"@owned\"}}}}";
            }
            byte[] data=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(200,data.length);e.getResponseBody().write(data);
        }});
        try{installClient(origin);}catch(Exception e){throw new RuntimeException(e);}
    }
    void installClient(YouTubeWindowRecoveryTest.Origin origin)throws Exception {
        var client=new YouTubeWindowRecoveryTest.RoutedClient(origin) {
            @Override java.net.http.HttpRequest route(java.net.http.HttpRequest r) {
                if(!r.uri().getHost().equals("music.youtube.com"))return super.route(r);
                var b=java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin.base()+"/music"+r.uri().getPath()));
                r.headers().map().forEach((k,v)->v.forEach(x->b.header(k,x)));
                return b.method(r.method(),r.bodyPublisher().orElse(java.net.http.HttpRequest.BodyPublishers.noBody())).build();
            }
        };
        field(YouTubeMusicService.class,"cachedClient").set(null,client);
        field(YouTubeMusicService.class,"cachedProxyEndpoint").set(null,"");
    }
}
