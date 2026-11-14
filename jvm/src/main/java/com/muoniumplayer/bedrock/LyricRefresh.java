package com.muoniumplayer.bedrock;

import com.google.gson.*;
import java.nio.file.*;
import java.io.IOException;

/** Owned HUD-editor preference, independent of the ModMenu settings schema. */
public final class LyricRefresh {
    private static final int[] RATES={60,120,144,165,240,0};
    private static final String[] LABELS={"60","120","144","165","240","无上限"};
    private static final class Holder {static final LyricRefresh INSTANCE=new LyricRefresh(Path.of("."));}
    public static LyricRefresh global(){return Holder.INSTANCE;}
    private final Path path;private volatile int index=5;
    public LyricRefresh(Path root){
        path=root.toAbsolutePath().normalize().resolve("config/muonium/lyric-refresh.json");
        try{
            SessionMedia.requirePlainRoot(path.getParent());
            if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&!SessionMedia.isLink(path)&&Files.size(path)<65536){
                var object=JsonParser.parseString(Files.readString(path)).getAsJsonObject();var value=object.get("choice");
                if(value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isNumber()){
                    int loaded=value.getAsBigDecimal().intValueExact();hertz(loaded);index=loaded;
                }
            }
        }catch(Exception invalid){index=5;}
    }
    public int index(){return index;}
    public String label(){return LABELS[index];}
    public static String[] labels(){return LABELS.clone();}
    static int hertz(int choice){if(choice<0||choice>=RATES.length)throw new IllegalArgumentException("3D frame-rate choice");return RATES[choice];}
    public void next(){setIndex((index+1)%RATES.length);}
    public void select(String label){for(int i=0;i<LABELS.length;i++)if(LABELS[i].equals(label)){setIndex(i);return;}throw new IllegalArgumentException("3D frame-rate choice");}
    public synchronized void setIndex(int choice){
        hertz(choice);Path temporary=null;
        try{
            SessionMedia.requirePlainRoot(path.getParent());Files.createDirectories(path.getParent());
            if(SessionMedia.isLink(path))throw new IOException("Lyric preference is a link");
            var object=new JsonObject();object.addProperty("choice",choice);
            temporary=Files.createTempFile(path.getParent(),".lyric-refresh-",".tmp");
            Files.writeString(temporary,new GsonBuilder().setPrettyPrinting().create().toJson(object)+"\n");
            Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            index=choice;
        }catch(IOException failure){throw new IllegalStateException("Could not save 3D frame-rate preference",failure);}
        finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}
    }
    /** A ceiling checked before every passive submission, including event-driven
     * frames. It skips intermediate snapshots and never accumulates frame debt. */
    static final class Gate {
        private int choice=-1;private boolean active;private long last;private boolean submitted;
        long remaining(boolean enabled,int selection,long now){
            int hz=hertz(selection);
            if(enabled!=active||choice!=selection){active=enabled;choice=selection;submitted=false;}
            if(!enabled||hz==0||!submitted)return 0;
            long elapsed=now-last;
            return elapsed<0?0:Math.max(0,(1_000_000_000L+hz-1)/hz-elapsed);
        }
        void presented(long started){last=started;submitted=true;}
    }
}
