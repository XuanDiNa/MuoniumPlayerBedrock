package com.muoniumplayer.bedrock;

import com.google.gson.*;
import com.muoniumplayer.core.ncm.music.*;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.screens.ncm.LyricParser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** Official response shapes through the real service and loader; dummy local HTTP only. */
@Timeout(12)
class YouTubeLyricsTest {
    private final YouTubeAccountRouteTest fixture=new YouTubeAccountRouteTest();
    @BeforeEach void save() throws Exception { fixture.save(); }
    @AfterEach void restore() throws Exception { fixture.restore(); }
    private static JsonObject response(String rows) {
        return JsonParser.parseString("{\"contents\":{\"timedLyricsData\":"+rows+"}}").getAsJsonObject();
    }
    private static String lrc(JsonObject response) throws Exception {
        var m=YouTubeMusicService.class.getDeclaredMethod("buildLrc",JsonObject.class);
        m.setAccessible(true); return (String)m.invoke(null,response);
    }
    private void route(YouTubeWindowRecoveryTest.Origin origin, JsonObject response) throws Exception {
        origin.server.createContext("/music", e->{try(e){
            String body=e.getRequestURI().getPath().endsWith("/next")
                ? "{\"browseEndpoint\":{\"browseId\":\"owned-lyrics\",\"browseEndpointContextSupportedConfigs\":{\"browseEndpointContextMusicConfig\":{\"pageType\":\"MUSIC_PAGE_TYPE_TRACK_LYRICS\"}}}}"
                : response.toString();
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);
        }});
        fixture.installClient(origin);
    }
    private static Object loadOfficial() throws Exception {
        var m=Class.forName("com.muoniumplayer.core.ncm.music.LyricLoadService").getDeclaredMethod("youTubeLyrics",Music.class);
        m.setAccessible(true);
        var constructor=YouTubeMusicService.Track.class.getDeclaredConstructor(String.class,String.class,String.class,long.class,String.class);
        constructor.setAccessible(true);
        return m.invoke(null,Music.fromYouTubeMusicTrack(constructor.newInstance("owned-lyrics","Owned song","Owned artist",120000L,"")));
    }
    @Test void textOnlyTimedDataRemainsUntimedAndFallsThroughLoader() throws Exception {
        try(var origin=new YouTubeWindowRecoveryTest.Origin(new byte[0])) {
            route(origin,response("[{\"lyricLine\":\"First\"},{\"lyricLine\":\"Last\"}]"));
            var result=YouTubeMusicService.getLyrics("owned-lyrics");
            assertFalse(result.timed,"Missing cueRange must not invent zero timestamps");
            assertEquals("First\nLast",result.plainText);
            assertTrue(result.lrc.isEmpty());
            assertNull(loadOfficial(),"Untimed official text must allow the timed fallback chain");
        }
    }
    @Test void normalTimelineKeepsExplicitZeroAndLaterCues() throws Exception {
        var response=response("[{\"lyricLine\":\"First\",\"cueRange\":{\"startTimeMilliseconds\":\"0\"}},"
            +"{\"lyricLine\":\"Last\",\"cueRange\":{\"startTimeMilliseconds\":12500}}]");
        assertEquals("[00:00.000]First\n[00:12.500]Last",lrc(response));
        try(var origin=new YouTubeWindowRecoveryTest.Origin(new byte[0])) {
            route(origin,response);
            assertTrue(YouTubeMusicService.getLyrics("owned-lyrics").timed);
            assertNotNull(loadOfficial(),"An actual timed official result retains priority");
        }
    }
    @ParameterizedTest @ValueSource(strings={"null","{}","[]","true","\"bad\"","-1","0.5","9223372036854775808"})
    void invalidStartCannotBecomeZero(String value) throws Exception {
        assertTrue(lrc(response("[{\"lyricLine\":\"First\",\"cueRange\":{\"startTimeMilliseconds\":"+value+"}},"
            +"{\"lyricLine\":\"Last\",\"cueRange\":{\"startTimeMilliseconds\":12000}}]")).isEmpty());
    }
    @Test void partialTimestampsCannotPublishAnIncompleteTimeline() throws Exception {
        assertTrue(lrc(response("[{\"lyricLine\":\"First\",\"cueRange\":{\"startTimeMilliseconds\":5000}},"
            +"{\"lyricLine\":\"Last\"}]")).isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"0","5000"})
    void collapsedMultiLineTimelineCannotSelectLastLineAtStart(String value) throws Exception {
        assertTrue(lrc(response("[{\"lyricLine\":\"First\",\"cueRange\":{\"startTimeMilliseconds\":"+value+"}},"
            +"{\"lyricLine\":\"Last\",\"cueRange\":{\"startTimeMilliseconds\":"+value+"}}]")).isEmpty());
    }
    @Test void simultaneousLinesWithLaterCuesRemainValid() throws Exception {
        String rows="[{\"lyricLine\":\"A\",\"cueRange\":{\"startTimeMilliseconds\":0}},"
            +"{\"lyricLine\":\"B\",\"cueRange\":{\"startTimeMilliseconds\":0}},"
            +"{\"lyricLine\":\"C\",\"cueRange\":{\"startTimeMilliseconds\":12000}}]";
        var json=new JsonObject();var payload=new JsonObject();payload.addProperty("lyric",lrc(response(rows)));json.add("lrc",payload);
        var lines=LyricParser.parse(json);
        assertEquals(3,lines.size());assertEquals(12000,lines.getLast().getTimestamp());
    }
    @Test void descriptionOnlyTextAlsoFallsThroughLoader() throws Exception {
        try(var origin=new YouTubeWindowRecoveryTest.Origin(new byte[0])) {
            route(origin,JsonParser.parseString("{\"descriptionShelfRenderer\":{\"description\":{\"simpleText\":\"First\\nLast\"}}}").getAsJsonObject());
            assertFalse(YouTubeMusicService.getLyrics("owned-lyrics").timed);
            assertNull(loadOfficial());
        }
    }
}
