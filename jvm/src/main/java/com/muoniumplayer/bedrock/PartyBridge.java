package com.muoniumplayer.bedrock;

import com.google.gson.*;
import com.muoniumplayer.party.*;
import com.muoniumplayer.party.direct.*;
import java.nio.file.*;
import java.util.UUID;

/** Companion-only identity for the original explicit TCP room UI.
 * It is not an Xbox identity and never sends custom payloads to Bedrock. */
public final class PartyBridge {
    record Identity(UUID id,String name){}
    static Identity identity(Path file) throws Exception {
        if(!Files.exists(file)) {
            Files.createDirectories(file.toAbsolutePath().getParent());
            UUID id=UUID.randomUUID();var json=new JsonObject();
            json.addProperty("id",id.toString());json.addProperty("name","Muonium-"+id.toString().substring(0,6));
            try{Files.writeString(file,new GsonBuilder().setPrettyPrinting().create().toJson(json),StandardOpenOption.CREATE_NEW);}
            catch(FileAlreadyExistsException raced) { /* Read the other complete owner below. */ }
        }
        if(Files.size(file)>4096)throw new IllegalArgumentException("Room identity size");
        var json=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        var id=UUID.fromString(json.get("id").getAsString());String name=json.get("name").getAsString().trim();
        if(!name.matches("[\\p{L}\\p{N}_ .-]{1,32}"))throw new IllegalArgumentException("Room display name");
        return new Identity(id,name);
    }
    static MpHost host(Identity identity) {
        return new MpHost(){
            public boolean canSend(){return false;}
            public void send(byte[] bytes){throw new IllegalStateException("Bedrock server party payload unavailable; use Direct rooms");}
            public UUID selfUuid(){return identity.id();}
            public String selfName(){return identity.name();}
            public void chat(String message){BedrockClient.getInstance().printMessage(message);}
        };
    }
    public static void start() {
        try {
            var id=identity(Path.of("config","muonium","bedrock-room-identity.json"));
            MpPartyClient.bind(MpHostRouter.wrap(host(id)));
            System.out.println("BEDROCK_PARTY ready transport=explicit-direct identity=companion-local");
        }catch(Exception e){System.err.println("BEDROCK_PARTY unavailable reason="+e.getClass().getSimpleName());}
    }
    public static void close(){MpDirect.close();MpPartyClient.onDisconnect();}
}
