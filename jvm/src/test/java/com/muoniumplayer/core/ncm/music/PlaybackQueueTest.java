package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.muoniumplayer.core.ncm.music.dto.Music;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackQueueTest {
    static Music song(int id) { return new Gson().fromJson("{\"id\":"+id+",\"name\":\"Fixture\",\"dt\":180000}",Music.class); }
    static void playlist(int... ids) {
        CloudMusic.clearQueuedNext();
        CloudMusic.playList=new java.util.concurrent.CopyOnWriteArrayList<>(Arrays.stream(ids).mapToObj(PlaybackQueueTest::song).toList());
        CloudMusic.curIdx=0;CloudMusic.currentlyPlaying=CloudMusic.playList.getFirst();
    }
    static List<Long> visible() { return CloudMusic.getQueuedNextSongs().stream().map(Music::getId).toList(); }
    @AfterEach void cleanup() { CloudMusic.clearQueuedNext();CloudMusic.playList=new ArrayList<>();CloudMusic.currentlyPlaying=null;CloudMusic.curIdx=0; }

    @Test void playlistPlaybackExposesRemainingSongsWithoutManualQueueClicks() {
        playlist(10,20,30,40);
        assertEquals(List.of(20L,30L,40L),visible());
        assertEquals(3,CloudMusic.getQueuedNextCount());
        assertFalse(CloudMusic.hasQueuedNext(),"Displaying a playlist must not turn single-repeat into sequential playback");
        CloudMusic.curIdx=2;CloudMusic.currentlyPlaying=CloudMusic.playList.get(2);
        assertEquals(List.of(40L),visible());
    }
    @Test void removingOneLegacyDuplicateKeepsOtherSongsVisible() {
        playlist(10,20,20,30,40);
        CloudMusic.playNextLedger.remember(CloudMusic.playList.get(1));
        CloudMusic.playNextLedger.remember(CloudMusic.playList.get(3));
        assertTrue(CloudMusic.removeQueuedNext(0));
        assertEquals(List.of(20L,30L,40L),visible(),"One X removes one row, not the shared identity of all rows");
        assertEquals(10,CloudMusic.currentlyPlaying.getId());
        assertTrue(CloudMusic.hasQueuedNext(),"A remaining explicit duplicate must retain its priority");
    }
    @Test void playlistEditsPreserveCurrentSongAndSourceList() {
        playlist(10,20,30,40);
        List<Music> source=List.copyOf(CloudMusic.playList);
        assertTrue(CloudMusic.moveQueuedNext(2,0));
        assertEquals(List.of(40L,20L,30L),visible());
        assertTrue(CloudMusic.removeQueuedNext(1));
        assertEquals(List.of(40L,30L),visible());
        assertFalse(CloudMusic.removeQueuedNext(2));
        assertEquals(2,CloudMusic.clearQueuedNextSongs());
        assertEquals(List.of(10L),CloudMusic.playList.stream().map(Music::getId).toList());
        assertEquals(4,source.size());assertEquals(0,CloudMusic.curIdx);
    }
}
