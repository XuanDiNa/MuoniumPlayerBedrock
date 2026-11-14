package com.muoniumplayer.bedrock;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackFilesTest {
    @TempDir Path root;
    static byte[] wav()throws Exception {
        var bytes=new ByteArrayOutputStream();
        var format=new javax.sound.sampled.AudioFormat(44100,16,2,true,false);
        try(var audio=new javax.sound.sampled.AudioInputStream(new ByteArrayInputStream(new byte[44100*4]),format,44100)) {
            javax.sound.sampled.AudioSystem.write(audio,javax.sound.sampled.AudioFileFormat.Type.WAVE,bytes);
        }return bytes.toByteArray();
    }
    File source()throws Exception {Path file=root.resolve("owned.wav");Files.write(file,wav());return file.toFile();}
    @Test void originalTemporaryFilesPageExposesBothIndependentControls() {
        var policy=PlaybackFiles.global();policy.setPrefetch(false);policy.setRetain(false);
        var category=Arrays.stream(com.muoniumplayer.core.screens.hud.HudSettingsModel.categories())
            .filter(c->c.title.equals("临时文件")).findFirst().orElseThrow();
        var entries=Arrays.stream(category.groups).flatMap(g->Arrays.stream(g.entries)).toList();
        var next=entries.stream().filter(e->e.getLabel().equals("预先下载下一首")).findFirst().orElseThrow();
        var keep=entries.stream().filter(e->e.getLabel().equals("保留音乐缓存")).findFirst().orElseThrow();
        try {
            assertFalse(next.isOn());assertFalse(keep.isOn());next.activate();assertTrue(policy.prefetchEnabled());assertFalse(keep.isOn());
            keep.activate();assertTrue(policy.retainEnabled());assertTrue(next.isOn());
        }finally{policy.setPrefetch(false);policy.setRetain(false);}
    }
    @Test void defaultsDoNotCreateOrRetainMedia()throws Exception {
        var policy=new PlaybackFiles(root);assertFalse(policy.prefetchEnabled());assertFalse(policy.retainEnabled());
        policy.retain("source|track|quality",source());assertNull(policy.restore("source|track|quality",root.resolve("session")));
        assertFalse(Files.exists(root.resolve("MuoniumPlayer/retained-audio-v1")));
    }
    @Test void bothIndependentSettingsPersistAndReopen() {
        var policy=new PlaybackFiles(root);policy.setPrefetch(true);
        var reopened=new PlaybackFiles(root);assertTrue(reopened.prefetchEnabled());assertFalse(reopened.retainEnabled());
        reopened.setRetain(true);reopened.setPrefetch(false);
        var finalPolicy=new PlaybackFiles(root);assertFalse(finalPolicy.prefetchEnabled());assertTrue(finalPolicy.retainEnabled());
    }
    @Test void retainedCompleteMusicIsRestoredAcrossSessionsWithoutOriginalFile()throws Exception {
        var policy=new PlaybackFiles(root);policy.setRetain(true);File original=source();byte[] expected=Files.readAllBytes(original.toPath());
        policy.retain("ncm|1|lossless",original);Files.delete(original.toPath());
        File restored=new PlaybackFiles(root).restore("ncm|1|lossless",root.resolve("another-session"));
        assertNotNull(restored);assertArrayEquals(expected,Files.readAllBytes(restored.toPath()));
        try(var audio=javax.sound.sampled.AudioSystem.getAudioInputStream(restored)){assertEquals(44100,audio.getFrameLength());}
        assertNull(policy.restore("ncm|1|standard",root.resolve("another-session")),"Quality/source identity must stay separate");
    }
    @Test void disablingStopsReadsAndWritesAndExplicitClearOnlyRemovesOwnedRecords()throws Exception {
        var policy=new PlaybackFiles(root);policy.setRetain(true);policy.retain("one",source());policy.setRetain(false);
        assertNull(policy.restore("one",root.resolve("session")));policy.retain("two",source());
        policy.setRetain(true);assertNull(policy.restore("two",root.resolve("session")));
        Path cache=root.resolve("MuoniumPlayer/retained-audio-v1"),other=cache.resolve("personal.wav");
        Files.write(other,new byte[]{1,2,3});policy.clearRetained();assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(other));
        assertNull(policy.restore("one",root.resolve("session")));
    }
    @Test void corruptionAndPartialFilesCannotBecomePlaybackHits()throws Exception {
        var policy=new PlaybackFiles(root);policy.setRetain(true);policy.retain("one",source());
        Path cache=root.resolve("MuoniumPlayer/retained-audio-v1"),record;
        try(var files=Files.list(cache)){record=files.filter(p->p.toString().endsWith(".mpbc")).findFirst().orElseThrow();}
        byte[] bytes=Files.readAllBytes(record);bytes[bytes.length-1]^=1;Files.write(record,bytes);
        assertNull(policy.restore("one",root.resolve("session")));
        if(Files.exists(root.resolve("session")))try(var remaining=Files.list(root.resolve("session"))){assertTrue(remaining.findAny().isEmpty());}
        File part=root.resolve("partial.wav.part").toFile();Files.write(part.toPath(),wav());policy.retain("part",part);
        assertNull(policy.restore("part",root.resolve("session")));
    }
}
