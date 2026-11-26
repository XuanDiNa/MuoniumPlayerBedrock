package com.muoniumplayer.bedrock;

import com.google.gson.*;
import java.nio.file.*;
import java.io.IOException;

/** Settings-page appearance only; never owns audio, login, downloads or HUD positions. */
public final class SettingsAppearance {
    private static final String[] THEMES={"PolyGlass Dark","PolyGlass Light","Minecraft Dark","Minecraft Light"};
    public record State(int theme,int accent,boolean animations) {
        public State {if(theme<0||theme>=4||accent<0||accent>0xFFFFFF)throw new IllegalArgumentException("Settings appearance");}
    }
    public record Palette(int panel,int edge,int nav,int hover,int selected,int card,int cardEdge,
                          int rowHover,int text,int secondary,int tertiary,int track,int off,int button,int buttonHover){}
    private static final class Holder {static final SettingsAppearance INSTANCE=new SettingsAppearance(Path.of("."));}
    public static SettingsAppearance global(){return Holder.INSTANCE;}
    private final Path path;
    private volatile State selected=new State(0,0x2B4FFF,true);
    private boolean dirty;
    public SettingsAppearance(Path root){
        path=root.toAbsolutePath().normalize().resolve("config/muonium/settings-appearance.json");
        try {
            SessionMedia.requirePlainRoot(path.getParent());
            if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&!SessionMedia.isLink(path)&&Files.size(path)<=65536){
                String text=Files.readString(path);HostSettings.parse(text);
                var o=JsonParser.parseString(text).getAsJsonObject();
                int theme=number(o,"theme",0);
                int accent=number(o,"accent",0x2B4FFF);
                boolean animations=true;
                if(o.has("animations")){
                    var v=o.get("animations");if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("animations");
                    animations=v.getAsBoolean();
                }
                selected=new State(theme,accent,animations);
            }
        }catch(Exception invalid){selected=new State(0,0x2B4FFF,true);}
    }
    private static int number(JsonObject o,String name,int fallback){
        if(!o.has(name))return fallback;
        var v=o.get(name);if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException(name);
        return v.getAsBigDecimal().intValueExact();
    }
    public State snapshot(){return selected;}
    public String label(){return THEMES[selected.theme];}
    public static String[] themes(){return THEMES.clone();}
    public boolean light(){return (selected.theme&1)==1;}
    public boolean square(){return selected.theme>=2;}
    public int accent(){return 0xFF000000|selected.accent;}
    public boolean animations(){return selected.animations;}
    public synchronized void selectTheme(String name){
        for(int i=0;i<THEMES.length;i++)if(THEMES[i].equals(name)){selected=new State(i,selected.accent,selected.animations);dirty=true;flush();return;}
        throw new IllegalArgumentException("Settings theme");
    }
    public void nextTheme(){selectTheme(THEMES[(selected.theme+1)%THEMES.length]);}
    /** Picker drag changes memory only; mouse release/close flushes once. */
    public synchronized void setAccent(int rgb){selected=new State(selected.theme,rgb&0xFFFFFF,selected.animations);dirty=true;}
    public synchronized void setAnimations(boolean enabled){selected=new State(selected.theme,selected.accent,enabled);dirty=true;flush();}
    public synchronized void flush(){
        if(!dirty)return;Path temporary=null;
        try {
            SessionMedia.requirePlainRoot(path.getParent());Files.createDirectories(path.getParent());
            if(SessionMedia.isLink(path))throw new IOException("Appearance preference is a link");
            var o=new JsonObject();o.addProperty("theme",selected.theme);o.addProperty("accent",selected.accent);o.addProperty("animations",selected.animations);
            temporary=Files.createTempFile(path.getParent(),".settings-appearance-",".tmp");
            Files.writeString(temporary,new GsonBuilder().setPrettyPrinting().create().toJson(o)+"\n");
            Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);dirty=false;
        }catch(IOException failed){throw new IllegalStateException("Could not save settings appearance",failed);}
        finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}
    }
    public Palette palette(){return palette(selected.theme);}
    public static Palette palette(int theme){
        if(theme<0||theme>3)throw new IllegalArgumentException("Settings theme");
        boolean light=(theme&1)==1,block=theme>=2;
        return light?new Palette(block?0xFFF1F2FA:0xF2F1F3FB,0x22000000,0x0E000000,0x16000000,0x18000000,
                0x0C000000,0x15000000,0x10000000,0xFF171B2B,0xFF454D62,0xFF626D81,0x30000000,0x38000000,0x14000000,0x22000000)
            :new Palette(block?0xFF151B23:0xF21A2030,0x24FFFFFF,0x22000000,0x14FFFFFF,0x22FFFFFF,
                0x202E4160,0x0EFFFFFF,0x1AFFFFFF,0xFFF2F5FA,0xFF98A3B4,0xFF6E7A8C,0x36FFFFFF,0x33FFFFFF,0x1FFFFFFF,0x38FFFFFF);
    }
}
