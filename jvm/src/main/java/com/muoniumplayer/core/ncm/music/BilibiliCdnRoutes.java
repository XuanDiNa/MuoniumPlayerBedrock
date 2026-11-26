package com.muoniumplayer.core.ncm.music;
import com.google.gson.JsonObject;
import java.util.*;
import java.net.URI;
/** Session-only addresses from one selected Bilibili audio representation. */
public final class BilibiliCdnRoutes {
    private BilibiliCdnRoutes(){}
    private record Entry(long at,List<String> urls){}
    private static final Map<String,Entry> routes=new LinkedHashMap<>();
    private static boolean http(String address){try{var u=URI.create(address);return Set.of("https","http").contains(u.getScheme())&&u.getHost()!=null&&u.getUserInfo()==null;}catch(RuntimeException e){return false;}}
    public static synchronized String remember(JsonObject stream,String primary){
        if(primary==null||!http(primary))return primary;
        var urls=new LinkedHashSet<String>();urls.add(primary);
        for(String name:new String[]{"backupUrl","backup_url"}){
            var values=stream==null?null:stream.get(name);
            if(values==null||!values.isJsonArray())continue;
            for(var item:values.getAsJsonArray())if(urls.size()<3&&item.isJsonPrimitive()&&item.getAsJsonPrimitive().isString()){
                String value=item.getAsString();if(http(value))urls.add(value);
            }
        }
        long now=System.nanoTime();routes.entrySet().removeIf(e->now-e.getValue().at>600_000_000_000L);
        routes.remove(primary);while(routes.size()>=64)routes.remove(routes.keySet().iterator().next());
        routes.put(primary,new Entry(now,List.copyOf(urls)));return primary;
    }
    public static synchronized List<String> candidates(String primary){
        var found=routes.get(primary);
        if(found!=null&&System.nanoTime()-found.at<=600_000_000_000L)return found.urls;
        routes.remove(primary);return List.of(primary);
    }
}
