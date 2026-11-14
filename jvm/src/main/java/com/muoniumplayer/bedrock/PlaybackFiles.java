package com.muoniumplayer.bedrock;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import com.google.gson.*;
import com.muoniumplayer.core.settings.JsonConfigStorage;

/** Player-owned audio policy. Both optional behaviours default off. */
public final class PlaybackFiles {
    private static final class Holder {static final PlaybackFiles INSTANCE=new PlaybackFiles(Path.of("."));}
    public static PlaybackFiles global(){return Holder.INSTANCE;}
    private static final long FILE_LIMIT=512L*1024*1024,DEFAULT_LIMIT=1024L*1024*1024;
    private static final int MAGIC=0x4d504231;
    private static final Set<String> EXTENSIONS=Set.of("wav","mp3","flac","m4a","aac","ogg","opus");
    final Path preferences;
    private final Path defaultParent;
    private volatile Path parent,cache;
    private volatile long capacity=DEFAULT_LIMIT;
    private volatile boolean prefetch,retention;
    private volatile long epoch;
    private final Object commit=new Object();
    private final Map<Path,Integer> leases=new HashMap<>();
    private final Map<String,String[]> labels=new LinkedHashMap<>();
    public record Entry(String id,String title,String artist,long bytes,long lastUsed) {}
    public PlaybackFiles(Path workingRoot) {
        Path root=workingRoot.toAbsolutePath().normalize();
        defaultParent=root;parent=root;preferences=root.resolve("config/muonium/playback-files.json");cache=cacheFor(root);
        try {
            SessionMedia.requirePlainRoot(preferences.getParent());
            if(Files.isRegularFile(preferences,LinkOption.NOFOLLOW_LINKS)&&!SessionMedia.isLink(preferences)&&Files.size(preferences)<65536) {
                var object=JsonConfigStorage.readObject(preferences.toFile(),new Gson());
                prefetch=flag(object,"prefetchNext");retention=flag(object,"retainMusicCache");
                if(object!=null&&object.has("cacheLimitBytes")) {
                    long value=object.get("cacheLimitBytes").getAsLong();
                    if(value>=0&&value<=4096L*1073741824L)capacity=value;
                }
                if(object!=null&&object.has("cacheParent")) {
                    Path selected=Path.of(object.get("cacheParent").getAsString());
                    if(selected.isAbsolute()) {SessionMedia.requirePlainRoot(selected);SessionMedia.requirePlainRoot(cacheFor(selected));parent=selected.normalize();cache=cacheFor(parent);}
                }
            }
        }catch(Exception ignored){prefetch=false;retention=false;}
    }
    private static boolean flag(JsonObject object,String key) {
        var value=object==null?null:object.get(key);
        return value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isBoolean()&&value.getAsBoolean();
    }
    public boolean prefetchEnabled(){return prefetch;}
    public boolean retainEnabled(){return retention;}
    private static Path cacheFor(Path parent){return parent.resolve("MuoniumPlayer/retained-audio-v1");}
    public Path cacheDirectory(){return cache;}
    public Path cacheParent(){return parent;}
    public long limitBytes(){return capacity;}
    public double limitGiB(){return capacity/1073741824.0;}
    private void persist(boolean next,boolean keep) {
        synchronized(commit) {
            try {
                SessionMedia.requirePlainRoot(preferences.getParent());
                if(SessionMedia.isLink(preferences)||SessionMedia.isLink(preferences.resolveSibling(preferences.getFileName()+".tmp")))
                    throw new IOException("Playback policy path is a link");
                var object=Files.isRegularFile(preferences,LinkOption.NOFOLLOW_LINKS)
                    ?JsonConfigStorage.readObject(preferences.toFile(),new Gson()):new JsonObject();
                if(object==null)object=new JsonObject();
                object.addProperty("prefetchNext",next);object.addProperty("retainMusicCache",keep);
                object.addProperty("cacheParent",parent.toString());object.addProperty("cacheLimitBytes",capacity);
                JsonConfigStorage.writeObject(preferences.toFile(),new GsonBuilder().setPrettyPrinting().create(),object);
                prefetch=next;retention=keep;epoch++;
            }catch(IOException failure){throw new IllegalStateException("Could not save playback-file settings",failure);}
        }
    }
    public void setPrefetch(boolean enabled){persist(enabled,retention);}
    public void setRetain(boolean enabled){persist(prefetch,enabled);}
    public void setLimitGiB(double gib) {
        if(!Double.isFinite(gib)||gib<0||gib>4096||(gib>0&&gib*1073741824L<1))throw new IllegalArgumentException("容量须为 0～4096 GiB；0 表示不限");
        synchronized(commit) {
            long old=capacity;capacity=(long)(gib*1073741824L);
            try{persist(prefetch,retention);}catch(RuntimeException e){capacity=old;throw e;}
            try{trim();}catch(IOException e){throw new IllegalStateException("容量已保存，但清理暂不可用",e);}
        }
    }
    public void setCacheParent(Path selected)throws IOException {
        if(selected==null||!selected.isAbsolute())throw new IOException("请选择绝对路径的文件夹");
        selected=selected.normalize();SessionMedia.requirePlainRoot(selected);
        Path target=cacheFor(selected);SessionMedia.requirePlainRoot(target);Files.createDirectories(target);
        Path probe=Files.createTempFile(target,".write-check-",".tmp");Files.delete(probe);
        synchronized(commit) {
            Path oldParent=parent,oldCache=cache;parent=selected;cache=target;
            try{persist(prefetch,retention);}catch(RuntimeException e){parent=oldParent;cache=oldCache;throw e;}
        }
    }
    public void resetCacheParent()throws IOException{setCacheParent(defaultParent);}
    public void rememberLabel(String key,String title,String artist) {
        if(key==null)return;
        synchronized(commit){if(labels.size()>=256)labels.remove(labels.keySet().iterator().next());labels.put(key,new String[]{label(title),label(artist)});}
    }
    private static String label(String value) {
        if(value==null)return "";
        value=value.replaceAll("[\\p{Cntrl}]", " ").strip();return value.substring(0,Math.min(256,value.length()));
    }
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private Path record(String key,Path directory) {
        if(key==null||key.isBlank()||key.length()>16384)return null;
        return directory.resolve(HexFormat.of().formatHex(sha().digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)))+".mpbc");
    }
    private static String extension(File file) {
        String name=file.getName();int at=name.lastIndexOf('.');return at<0?"":name.substring(at+1).toLowerCase(Locale.ROOT);
    }
    public void retain(String key,File complete) {
        if(!retention||complete==null||!EXTENSIONS.contains(extension(complete)))return;
        long generation=epoch;Path directory=cache;Path temp=null;
        try {
            Path target=record(key,directory);if(target==null)return;
            Path source=complete.toPath();long length=Files.size(source);
            if(length<=0||length>FILE_LIMIT||!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)||SessionMedia.isLink(source))return;
            SessionMedia.requirePlainRoot(directory);Files.createDirectories(directory);
            temp=Files.createTempFile(directory,target.getFileName()+"-",".part");
            try(var out=new DataOutputStream(Files.newOutputStream(temp));var input=Files.newInputStream(source)) {
                out.writeInt(MAGIC);out.writeUTF(extension(complete));out.writeLong(length);
                var hash=sha();byte[] block=new byte[65536];long copied=0;int n;
                while((n=input.read(block))!=-1) {
                    if(!retention||epoch!=generation||copied+n>length)throw new IOException("Cache save cancelled");
                    out.write(block,0,n);hash.update(block,0,n);copied+=n;
                }
                if(copied!=length)throw new EOFException("Incomplete cache input");out.write(hash.digest());
            }
            synchronized(commit) {
                if(!retention||epoch!=generation)return;
                SessionMedia.requirePlainRoot(directory);if(leases.containsKey(target))return;
                Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);temp=null;
                String[] display=labels.get(key);if(display!=null)try{writeLabel(target,display);}catch(IOException ignored){}
                trim();
            }
            System.out.println("MEDIA_CACHE retained=complete");
        }catch(IOException|RuntimeException ignored){System.out.println("MEDIA_CACHE retain=unavailable");}
        finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(IOException ignored){}}
    }
    public File restore(String key,Path session) {
        if(!retention)return null;long generation=epoch;Path directory=cache;Path output=null,record=null;boolean leased=false;
        try {
            record=record(key,directory);if(record==null)return null;
            synchronized(commit){if(!retention||epoch!=generation)return null;SessionMedia.requirePlainRoot(directory);leases.merge(record,1,Integer::sum);leased=true;}
            if(!Files.isRegularFile(record,LinkOption.NOFOLLOW_LINKS)||SessionMedia.isLink(record)||Files.size(record)>FILE_LIMIT+64)return null;
            try(var in=new DataInputStream(Files.newInputStream(record))) {
                if(in.readInt()!=MAGIC)return null;String ext=in.readUTF();long length=in.readLong();
                if(!EXTENSIONS.contains(ext)||length<=0||length>FILE_LIMIT)return null;
                SessionMedia.requirePlainRoot(session);Files.createDirectories(session);output=Files.createTempFile(session,"retained-","."+ext);
                var hash=sha();byte[] block=new byte[65536];long left=length;
                try(var out=Files.newOutputStream(output)) {
                    while(left>0) {
                        if(!retention||epoch!=generation)throw new IOException("Cache restore cancelled");
                        int n=in.read(block,0,(int)Math.min(left,block.length));if(n<0)throw new EOFException("Truncated music cache");
                        out.write(block,0,n);hash.update(block,0,n);left-=n;
                    }
                }
                if(!MessageDigest.isEqual(hash.digest(),in.readNBytes(32))||in.read()!=-1)throw new IOException("Invalid music cache checksum");
            }
            if(!retention||epoch!=generation)throw new IOException("Cache restore cancelled");
            Files.setLastModifiedTime(record,java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            System.out.println("MEDIA_CACHE hit=complete");File result=output.toFile();output=null;return result;
        }catch(IOException|RuntimeException ignored){return null;}
        finally{if(leased)synchronized(commit){leases.computeIfPresent(record,(k,n)->n<=1?null:n-1);}if(output!=null)try{Files.deleteIfExists(output);}catch(IOException ignored){}}
    }
    private List<Path> records()throws IOException {
        SessionMedia.requirePlainRoot(cache);if(!Files.isDirectory(cache))return List.of();
        try(var files=Files.list(cache)) {
            var found=new ArrayList<Path>();
            for(Path path:files.toList())if(path.getFileName().toString().matches("[0-9a-f]{64}\\.mpbc")
                &&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&!SessionMedia.isLink(path)&&validRecord(path))found.add(path);
            return found;
        }
    }
    private void trim()throws IOException {
        var records=new ArrayList<>(records());records.sort(Comparator.comparingLong(p->p.toFile().lastModified()));
        long total=0;for(Path p:records)total+=Files.size(p);
        for(Path p:records){if(capacity==0||total<=capacity)break;if(leases.containsKey(p))continue;long n=Files.size(p);remove(p);total-=n;}
    }
    private static boolean validRecord(Path p) {
        try(var in=new DataInputStream(Files.newInputStream(p))) {
            if(in.readInt()!=MAGIC)return false;String ext=in.readUTF();long n=in.readLong();
            return EXTENSIONS.contains(ext)&&n>0&&n<=FILE_LIMIT&&Files.size(p)==4+2+ext.length()+8+n+32;
        }catch(IOException e){return false;}
    }
    private static Path metadata(Path record){return record.resolveSibling(record.getFileName()+".json");}
    private void writeLabel(Path record,String[] display)throws IOException {
        Path file=metadata(record);if(SessionMedia.isLink(file)||SessionMedia.isLink(file.resolveSibling(file.getFileName()+".tmp")))return;
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)&&readLabel(record)==null)return;
        var data=new JsonObject();data.addProperty("mpbcMetadataVersion",1);data.addProperty("title",display[0]);data.addProperty("artist",display[1]);
        JsonConfigStorage.writeObject(file.toFile(),new Gson(),data);
    }
    private static JsonObject readLabel(Path record) {
        Path p=metadata(record);
        try{if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||SessionMedia.isLink(p)||Files.size(p)>8192)return null;
            var data=JsonConfigStorage.readObject(p.toFile(),new Gson());return data!=null&&data.has("mpbcMetadataVersion")&&data.get("mpbcMetadataVersion").getAsInt()==1?data:null;
        }catch(Exception e){return null;}
    }
    private void remove(Path p)throws IOException {
        if(leases.containsKey(p))throw new IOException("曲目正在读取，请稍后重试");
        boolean ownedLabel=readLabel(p)!=null;Files.deleteIfExists(p);if(ownedLabel)Files.deleteIfExists(metadata(p));
    }
    public List<Entry> entries()throws IOException {
        synchronized(commit) {
            var result=new ArrayList<Entry>();
            for(Path p:records()) {
                String id=p.getFileName().toString();var meta=readLabel(p);
                String title="已缓存音频 · "+id.substring(0,8),artist="";
                try{if(meta!=null){title=label(meta.get("title").getAsString());artist=label(meta.get("artist").getAsString());}}catch(Exception ignored){}
                result.add(new Entry(id,title,artist,Files.size(p),p.toFile().lastModified()));
            }
            result.sort(Comparator.comparingLong(Entry::lastUsed).reversed());return List.copyOf(result);
        }
    }
    public boolean deleteEntry(String id)throws IOException {
        if(id==null||!id.matches("[0-9a-f]{64}\\.mpbc"))return false;
        synchronized(commit){SessionMedia.requirePlainRoot(cache);Path p=cache.resolve(id);
            if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||SessionMedia.isLink(p)||!validRecord(p))return false;
            remove(p);return true;}
    }
    public void clearRetained() {
        synchronized(commit) {
            epoch++;
            try{for(Path path:records())if(!leases.containsKey(path))remove(path);System.out.println("MEDIA_CACHE cleared=owned-records");}
            catch(IOException ignored){System.out.println("MEDIA_CACHE clear=deferred");}
        }
    }
}
