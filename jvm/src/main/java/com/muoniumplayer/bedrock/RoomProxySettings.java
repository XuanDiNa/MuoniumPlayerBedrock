package com.muoniumplayer.bedrock;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Room-join policy only. Never sets JVM globals or modifies Windows proxy settings. */
public final class RoomProxySettings {
    public enum Mode {
        DIRECT("直连"),SYSTEM("系统代理"),HTTP("HTTP CONNECT"),SOCKS5("SOCKS5");
        public final String label;
        Mode(String label){this.label=label;}
    }
    public record Value(Mode mode,String host,int port) {
        public Value {
            Objects.requireNonNull(mode);
            host=host==null?"":host.strip();
            if(mode==Mode.DIRECT||mode==Mode.SYSTEM){host="";port=0;}
            else {
                if(port<1||port>65535)throw new IllegalArgumentException("代理端口须为 1～65535");
                if(host.startsWith("[")&&host.endsWith("]"))host=host.substring(1,host.length()-1);
                if(host.isEmpty()||host.length()>253||host.chars().anyMatch(c->c<=32||c==127)
                        ||host.contains("/")||host.contains("\\")||host.contains("@")||host.contains("#")||host.contains("?"))
                    throw new IllegalArgumentException("代理地址只填写主机名或 IP，不含网址、密码和路径");
                if(host.contains(":")) {
                    if(!host.matches("[0-9a-fA-F:.]+"))throw new IllegalArgumentException("IPv6 代理地址无效");
                }else {
                    try{host=IDN.toASCII(host,IDN.USE_STD3_ASCII_RULES);}
                    catch(IllegalArgumentException e){throw new IllegalArgumentException("代理主机名无效");}
                }
            }
        }
    }
    private static final class Holder {static final RoomProxySettings INSTANCE=new RoomProxySettings(Path.of("."));}
    public static RoomProxySettings global(){return Holder.INSTANCE;}
    private final Path file;
    private volatile Value value=new Value(Mode.DIRECT,"",0);
    private volatile boolean invalid;
    public RoomProxySettings(Path root) {
        file=root.toAbsolutePath().normalize().resolve("config/muonium/room-proxy.json");
        try {
            SessionMedia.requirePlainRoot(file.getParent());
            if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return;
            if(SessionMedia.isLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>4096)
                throw new IOException("Invalid proxy configuration file");
            try(var reader=new JsonReader(Files.newBufferedReader(file,StandardCharsets.UTF_8))) {
                reader.setStrictness(Strictness.STRICT);var keys=new HashSet<String>();var object=new JsonObject();
                reader.beginObject();
                while(reader.hasNext()){String key=reader.nextName();if(!keys.add(key))throw new IOException("Duplicate proxy key");object.add(key,JsonParser.parseReader(reader));}
                reader.endObject();if(reader.peek()!=JsonToken.END_DOCUMENT)throw new IOException("Trailing proxy data");
                Mode mode=Mode.valueOf(string(object,"mode"));
                value=new Value(mode,mode==Mode.HTTP||mode==Mode.SOCKS5?string(object,"host"):"",
                        mode==Mode.HTTP||mode==Mode.SOCKS5?object.get("port").getAsBigDecimal().intValueExact():0);
            }
        }catch(Exception e){invalid=true;}
    }
    private static String string(JsonObject object,String key) {
        var p=object.getAsJsonPrimitive(key);if(p==null||!p.isString())throw new IllegalArgumentException("Proxy setting type");return p.getAsString();
    }
    public Value get(){if(invalid)throw new IllegalStateException("代理设置损坏，请打开连接代理并重新保存");return value;}
    public String label(){return invalid?"设置损坏，需重新保存":value.mode().label;}
    public synchronized void save(Value next) throws IOException {
        Objects.requireNonNull(next);SessionMedia.requirePlainRoot(file.getParent());
        if(SessionMedia.isLink(file))throw new IOException("代理设置路径不可用");
        Files.createDirectories(file.getParent());Path temp=Files.createTempFile(file.getParent(),"room-proxy-",".tmp");
        try {
            var object=new JsonObject();object.addProperty("mode",next.mode().name());
            if(next.mode()==Mode.HTTP||next.mode()==Mode.SOCKS5){object.addProperty("host",next.host());object.addProperty("port",next.port());}
            Files.writeString(temp,new GsonBuilder().setPrettyPrinting().create().toJson(object)+"\n",StandardCharsets.UTF_8);
            try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
            value=next;invalid=false;
        }finally{Files.deleteIfExists(temp);}
    }
}
