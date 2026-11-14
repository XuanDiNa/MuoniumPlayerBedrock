package com.muoniumplayer.bedrock;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CacheManagementTest {
    @TempDir Path root;
    static Object call(Object target,String name,Class<?>[] types,Object... args) throws Exception {
        Method method=assertDoesNotThrow(()->target.getClass().getMethod(name,types),"Missing cache-management API: "+name);
        try{return method.invoke(target,args);}catch(InvocationTargetException e){
            if(e.getCause() instanceof Exception ex)throw ex;throw e;
        }
    }
    static Object call(Object target,String name)throws Exception{return call(target,name,new Class<?>[0]);}
    void limit(PlaybackFiles p,double gib)throws Exception{call(p,"setLimitGiB",new Class<?>[]{double.class},gib);}
    Path directory(PlaybackFiles p)throws Exception{return (Path)call(p,"cacheDirectory");}
    List<?> entries(PlaybackFiles p)throws Exception{return (List<?>)call(p,"entries");}
    Path audio()throws Exception {Path p=root.resolve("song.wav");Files.write(p,PlaybackFilesTest.wav());return p;}
    @Test void hudHasARealCacheBrowserEntry() {
        var c=Arrays.stream(com.muoniumplayer.core.screens.hud.HudSettingsModel.categories())
            .filter(v->v.title.equals("临时文件")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(c.groups).flatMap(g->Arrays.stream(g.entries))
            .anyMatch(e->e.getLabel().equals("查看与自定义音乐缓存")));
    }
    @Test void customParentAndExactLimitPersistWithoutEnablingRetention()throws Exception {
        var p=new PlaybackFiles(root);Path parent=root.resolve("user-storage");Files.createDirectories(parent);
        Files.writeString(parent.resolve("personal.txt"),"untouched");
        call(p,"setCacheParent",new Class<?>[]{Path.class},parent);limit(p,2.75);
        var reopened=new PlaybackFiles(root);
        assertEquals(parent.resolve("MuoniumPlayer/retained-audio-v1"),directory(reopened));
        assertEquals((long)(2.75*1073741824L),call(reopened,"limitBytes"));
        assertFalse(reopened.retainEnabled());assertFalse(reopened.prefetchEnabled());
        assertEquals("untouched",Files.readString(parent.resolve("personal.txt")));
        for(double n:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY,4097})
            assertThrows(IllegalArgumentException.class,()->limit(reopened,n));
        assertEquals((long)(2.75*1073741824L),call(reopened,"limitBytes"));
    }
    @Test void browserLabelsSingleDeleteAndUnrelatedFilesAreIndependent()throws Exception {
        var p=new PlaybackFiles(root);p.setRetain(true);
        call(p,"rememberLabel",new Class<?>[]{String.class,String.class,String.class},"one","第一首","歌手");
        p.retain("one",audio().toFile());p.retain("two",audio().toFile());
        Path unrelated=directory(p).resolve("personal.wav");Files.writeString(unrelated,"preserve");
        var items=entries(new PlaybackFiles(root));assertEquals(2,items.size());
        Object one=items.stream().filter(e->{try{return call(e,"title").equals("第一首");}catch(Exception ex){throw new RuntimeException(ex);}}).findFirst().orElseThrow();
        assertEquals("歌手",call(one,"artist"));assertTrue((long)call(one,"bytes")>0);
        String id=(String)call(one,"id");
        assertEquals(false,call(p,"deleteEntry",new Class<?>[]{String.class},"../personal.wav"));
        assertEquals(true,call(p,"deleteEntry",new Class<?>[]{String.class},id));
        assertNull(p.restore("one",root.resolve("session")));assertNotNull(p.restore("two",root.resolve("session")));
        assertEquals(1,entries(p).size());assertEquals("preserve",Files.readString(unrelated));
    }
    @Test void changingStorageNeverMovesOrClearsPreviousRecords()throws Exception {
        var p=new PlaybackFiles(root);p.setRetain(true);p.retain("one",audio().toFile());Path old=directory(p);
        call(p,"setCacheParent",new Class<?>[]{Path.class},root.resolve("new-parent"));
        assertTrue(entries(p).isEmpty());assertNull(p.restore("one",root.resolve("session")));
        try(var files=Files.list(old)){assertTrue(files.anyMatch(f->f.toString().endsWith(".mpbc")));}
        call(p,"setCacheParent",new Class<?>[]{Path.class},root);assertEquals(old,directory(p));
        assertNotNull(p.restore("one",root.resolve("session")));
    }
    @Test void capacityEvictsOldRecordsAndUnlimitedIsExplicit()throws Exception {
        var p=new PlaybackFiles(root);p.setRetain(true);Path source=audio();
        limit(p,(Files.size(source)*1.5)/1073741824.0);
        p.retain("one",source.toFile());Thread.sleep(20);p.retain("two",source.toFile());
        assertNull(p.restore("one",root.resolve("session")));assertNotNull(p.restore("two",root.resolve("session")));
        assertEquals(1,entries(p).size());limit(p,0);assertEquals(0L,call(p,"limitBytes"));
        p.retain("three",source.toFile());assertEquals(2,entries(p).size());
    }
    @Test void nonDirectorySelectionIsRejectedAndSettingsRemainReadable()throws Exception {
        var p=new PlaybackFiles(root);Path old=directory(p),file=root.resolve("file");Files.writeString(file,"data");
        assertThrows(Exception.class,()->call(p,"setCacheParent",new Class<?>[]{Path.class},file));
        assertEquals(old,directory(new PlaybackFiles(root)));
    }
    @Test void unrelatedMetadataAndInvalidRecordsAreNeverOverwrittenOrDeleted()throws Exception {
        var p=new PlaybackFiles(root);p.setRetain(true);p.retain("one",audio().toFile());
        String id=(String)call(entries(p).getFirst(),"id");Path meta=directory(p).resolve(id+".json");Files.writeString(meta,"personal data");
        call(p,"rememberLabel",new Class<?>[]{String.class,String.class,String.class},"one","曲目","歌手");
        p.retain("one",audio().toFile());assertEquals("personal data",Files.readString(meta));
        Path invalid=directory(p).resolve("f".repeat(64)+".mpbc");Files.writeString(invalid,"unrelated");
        p.clearRetained();assertEquals("unrelated",Files.readString(invalid));assertEquals("personal data",Files.readString(meta));
    }
    @Test void customFolderRejectsWindowsJunctionWithoutTouchingTarget()throws Exception {
        var p=new PlaybackFiles(root);Path target=root.resolve("target"),link=root.resolve("junction");Files.createDirectories(target);
        Files.writeString(target.resolve("personal.txt"),"protected");
        var process=new ProcessBuilder("powershell.exe","-NoProfile","-Command",
            "New-Item -ItemType Junction -Path '"+link.toString().replace("'","''")+"' -Target '"+target.toString().replace("'","''")+"' | Out-Null").start();
        assertEquals(0,process.waitFor());
        try {assertThrows(Exception.class,()->call(p,"setCacheParent",new Class<?>[]{Path.class},link));assertEquals("protected",Files.readString(target.resolve("personal.txt")));}
        finally{Files.delete(link);}
    }
}
