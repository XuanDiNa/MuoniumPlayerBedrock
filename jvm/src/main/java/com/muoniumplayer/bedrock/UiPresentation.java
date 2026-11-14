package com.muoniumplayer.bedrock;

import com.google.gson.*;
import java.nio.file.*;
import java.io.IOException;

/** Presentation only: opening snapshots never own or restart playback. */
public final class UiPresentation {
    private static final String[] LAYOUTS={"旧版浮动面板","新版设置中心"};
    private static final String[] MOTIONS={"原版动画","新版分层动画","减少动态"};
    private static final class Holder {static final UiPresentation INSTANCE=new UiPresentation(Path.of("."));}
    public static UiPresentation global(){return Holder.INSTANCE;}
    public record Selection(int layout,int motion) {
        public Selection {if(layout<0||layout>=LAYOUTS.length||motion<0||motion>=MOTIONS.length)throw new IllegalArgumentException("UI selection");}
        public boolean modern(){return layout==1;}
        public boolean reduced(){return motion==2;}
        public double progress(double value){
            double t=Math.max(0,Math.min(1,value));
            return reduced()?1:motion==1?1-Math.pow(1-t,1.35):t;
        }
    }
    private final Path path;
    private volatile Selection selected=new Selection(0,0);
    public UiPresentation(Path root){
        path=root.toAbsolutePath().normalize().resolve("config/muonium/ui-presentation.json");
        try {
            SessionMedia.requirePlainRoot(path.getParent());
            if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&!SessionMedia.isLink(path)&&Files.size(path)<=65536){
                String text=Files.readString(path);HostSettings.parse(text);
                var object=JsonParser.parseString(text).getAsJsonObject();
                selected=new Selection(integer(object,"layout"),integer(object,"motion"));
            }
        }catch(Exception invalid){selected=new Selection(0,0);}
    }
    private static int integer(JsonObject object,String key){
        if(!object.has(key))return 0;
        var value=object.get(key);
        if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException(key);
        return value.getAsBigDecimal().intValueExact();
    }
    public Selection snapshot(){return selected;}
    public static String[] layouts(){return LAYOUTS.clone();}
    public static String[] motions(){return MOTIONS.clone();}
    public String layoutLabel(){return LAYOUTS[selected.layout];}
    public String motionLabel(){return MOTIONS[selected.motion];}
    public synchronized void selectLayout(String label){save(new Selection(index(LAYOUTS,label),selected.motion));}
    public synchronized void selectMotion(String label){save(new Selection(selected.layout,index(MOTIONS,label)));}
    public void nextLayout(){selectLayout(LAYOUTS[(selected.layout+1)%LAYOUTS.length]);}
    public void nextMotion(){selectMotion(MOTIONS[(selected.motion+1)%MOTIONS.length]);}
    private static int index(String[] choices,String label){for(int i=0;i<choices.length;i++)if(choices[i].equals(label))return i;throw new IllegalArgumentException("UI choice");}
    private void save(Selection value){
        Path temporary=null;
        try {
            SessionMedia.requirePlainRoot(path.getParent());Files.createDirectories(path.getParent());
            if(SessionMedia.isLink(path))throw new IOException("UI preference is a link");
            var json=new JsonObject();json.addProperty("layout",value.layout);json.addProperty("motion",value.motion);
            temporary=Files.createTempFile(path.getParent(),".ui-presentation-",".tmp");
            Files.writeString(temporary,new GsonBuilder().setPrettyPrinting().create().toJson(json)+"\n");
            Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);selected=value;
        }catch(IOException failure){throw new IllegalStateException("Could not save UI presentation",failure);}
        finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}
    }
}
