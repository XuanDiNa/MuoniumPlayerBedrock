package com.muoniumplayer.bedrock;

import java.net.URI;

/** Temporary service invites contain transport details, not account credentials. */
record TunnelInvite(URI endpoint,String password,String roomCode) {
    static boolean isTunnel(String value){return value!=null&&value.trim().toLowerCase(java.util.Locale.ROOT).startsWith("wss:");}
    static TunnelInvite parse(String value) {
        if(value==null||value.length()>512)throw new IllegalArgumentException("Invalid internet room invite");
        String[] parts=value.trim().split("#",-1);
        if(parts.length<2||parts.length>3)throw new IllegalArgumentException("Invalid internet room invite");
        URI uri;
        try{uri=URI.create(parts[0]);}catch(RuntimeException e){throw new IllegalArgumentException("Invalid internet room address");}
        if(!"wss".equals(uri.getScheme())||!validHost(uri.getHost())||uri.getUserInfo()!=null
            ||uri.getPort()!=-1||uri.getRawQuery()!=null||uri.getRawFragment()!=null
            ||!uri.getRawPath().matches("/room/[a-f0-9]{32}"))
            throw new IllegalArgumentException("Invalid encrypted internet room address");
        String room=parts.length==3?parts[2]:"";
        if(!parts[1].matches("[a-zA-Z0-9]{8,64}")||!room.matches("[a-zA-Z0-9_-]{0,64}"))
            throw new IllegalArgumentException("Invalid internet room password/code");
        return new TunnelInvite(uri,parts[1],room);
    }
    static boolean validHost(String host){return host!=null&&host.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.trycloudflare\\.com");}
    static String create(String publicUrl,String token,String auth,String code) {
        URI base=URI.create(publicUrl);
        if(!"https".equals(base.getScheme())||!validHost(base.getHost())||base.getPort()!=-1
            ||base.getUserInfo()!=null||base.getRawQuery()!=null||base.getRawFragment()!=null
            ||!(base.getRawPath().isEmpty()||base.getRawPath().equals("/")))throw new IllegalArgumentException("Invalid tunnel URL");
        String value="wss://"+base.getHost()+"/room/"+token+"#"+auth+(code==null||code.isBlank()?"":"#"+code);
        parse(value);return value;
    }
    String localInvite(int port){return "127.0.0.1:"+port+"#"+password+(roomCode.isEmpty()?"":"#"+roomCode);}
}
