package com.muoniumplayer.bedrock;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.muoniumplayer.core.ncm.music.ambient.EnvSample;
import com.muoniumplayer.core.ncm.music.acoustics.*;

/** Immutable bounded client-world observation; native addresses never cross IPC. */
public final class EnvironmentFrame {
    public static final int SIDE=33,BYTES=75208;
    private static final int CELLS=SIDE*SIDE*SIDE,DICTIONARY=256+2*CELLS;
    final long at,epoch;final int dimension,bx,by,bz,known,water;
    final double x,y,z,lx,ly,lz;final int time,enemies,animals,villagers,blockClue;
    final float rain,thunder,light,sky,health;final String biome;private final byte[] cells,shapeIds;
    private final short[][] shapes;
    private EnvironmentFrame(byte[] raw) {
        var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        at=b.getLong(16);epoch=b.getLong(24);dimension=b.getInt(36);
        bx=b.getInt(40);by=b.getInt(44);bz=b.getInt(48);
        x=b.getDouble(56);y=b.getDouble(64);z=b.getDouble(72);lx=b.getDouble(80);ly=b.getDouble(88);lz=b.getDouble(96);
        time=b.getInt(104);rain=b.getFloat(112);thunder=b.getFloat(116);light=b.getFloat(120);sky=b.getFloat(124);health=b.getFloat(128);
        enemies=b.getInt(132);animals=b.getInt(136);villagers=b.getInt(140);known=b.getInt(144);water=b.getInt(148);blockClue=b.getInt(4)==2?b.getInt(216):0;
        int end=152;while(end<216&&raw[end]!=0)end++;biome=new String(raw,152,end-152,StandardCharsets.US_ASCII);
        cells=Arrays.copyOfRange(raw,256,256+SIDE*SIDE*SIDE);
        if(b.getInt(4)==2) {
            shapeIds=Arrays.copyOfRange(raw,256+CELLS,256+2*CELLS);
            shapes=new short[Math.max(0,Math.min(256,b.getInt(108)))][6];
            for(int i=0;i<shapes.length;i++)for(int a=0;a<6;a++)shapes[i][a]=b.getShort(DICTIONARY+12*i+2*a);
        } else {shapeIds=new byte[CELLS];shapes=new short[][]{{0,0,0,4096,4096,4096}};}
    }
    public static EnvironmentFrame decode(byte[] raw,long now,long expectedEpoch) {
        if(raw==null||raw.length!=BYTES&&raw.length!=36200)return null;
        var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int version=b.getInt(4);
        if(b.getInt(0)!=0x4556504d||(version!=1&&version!=2)||version==2&&raw.length!=BYTES||b.getLong(8)<=0||b.getInt(32)!=1||b.getInt(52)!=SIDE||b.getLong(24)!=expectedEpoch)return null;
        long at=b.getLong(16);if(at<=0||at>now||now-at>2000)return null;
        var f=new EnvironmentFrame(raw);
        if(f.dimension<0||f.dimension>2||(f.known&32)==0||(f.known&~511)!=0||f.water<0||f.water>3)return null;
        for(int count:new int[]{f.enemies,f.animals,f.villagers})if(count<0||count>4096)return null;
        for(double value:new double[]{f.x,f.y,f.z,f.lx,f.ly,f.lz,f.rain,f.thunder,f.light,f.sky,f.health})if(!Double.isFinite(value))return null;
        if(Math.abs(f.x)>32_000_000||Math.abs(f.y)>32_000_000||Math.abs(f.z)>32_000_000||Math.abs(f.lx*f.lx+f.ly*f.ly+f.lz*f.lz-1)>.02)return null;
        if(f.x-f.bx<0||f.x-f.bx>=SIDE||f.y-f.by<0||f.y-f.by>=SIDE||f.z-f.bz<0||f.z-f.bz>=SIDE)return null;
        if(f.time<0||f.time>=24000||f.rain<0||f.rain>1||f.thunder<0||f.thunder>1||f.light<0||f.light>15||f.sky<0||f.sky>15||f.health<0||f.health>1)return null;
        if(f.blockClue<0||f.blockClue>5)return null;
        if(!f.biome.matches("[a-z0-9_:.-]{0,63}"))return null;
        for(byte cell:f.cells)if((cell&255)>(version==2?10:9)&&(cell&255)!=255)return null;
        if(version==2) {
            if(b.getInt(108)<2||b.getInt(108)>256||(f.known&128)==0)return null;
            for(var shape:f.shapes)for(int a=0;a<3;a++)if(shape[a]<-4096||shape[a+3]>8192||shape[a]>shape[a+3])return null;
            if(!Arrays.equals(f.shapes[0],new short[]{0,0,0,4096,4096,4096})||!Arrays.equals(f.shapes[1],new short[6]))return null;
            for(byte id:f.shapeIds)if((id&255)>=f.shapes.length)return null;
        }
        return f;
    }
    int shapeCount(){return shapes.length;}
    short[] shape(int px,int py,int pz) {
        int ix=px-bx,iy=py-by,iz=pz-bz;
        if(ix<0||iy<0||iz<0||ix>=SIDE||iy>=SIDE||iz>=SIDE)return shapes[0];
        return shapes[shapeIds[(iy*SIDE+iz)*SIDE+ix]&255];
    }
    int cell(int px,int py,int pz) {
        int ix=px-bx,iy=py-by,iz=pz-bz;
        if(ix<0||iy<0||iz<0||ix>=SIDE||iy>=SIDE||iz>=SIDE)return 255;
        return cells[(iy*SIDE+iz)*SIDE+ix]&255;
    }
    boolean sameGeometry(EnvironmentFrame other) {
        return other!=null&&epoch==other.epoch&&dimension==other.dimension&&bx==other.bx&&by==other.by&&bz==other.bz
            &&Arrays.equals(cells,other.cells)&&Arrays.equals(shapeIds,other.shapeIds)&&Arrays.deepEquals(shapes,other.shapes);
    }
    private boolean blocked(int px,int py,int pz){int c=cell(px,py,pz);return (c>=1&&c<=7||c==10);}
    EnvSample ambient() {
        var f=new EnvSample();f.dimension=dimension;f.y=y;
        f.lightLevel=(int)light;f.skyLightLevel=(known&64)!=0?sky:15;
        f.timeOfDay=(known&1)!=0?time:6000;f.raining=(known&2)!=0&&rain>.2;f.thundering=(known&2)!=0&&thunder>.2;
        f.blockClue=(known&256)!=0?blockClue:0;
        f.healthPct=(known&8)!=0?health:1;f.enemyCount=(known&16)!=0?enemies:0;f.neutralCount=(known&16)!=0?animals:0;f.villagerCount=(known&16)!=0?villagers:0;
        f.inWater=(water&1)!=0;f.biomeKeyPath=(known&4)!=0?biome:"unknown";
        String key=f.biomeKeyPath;
        f.biomeForest=key.contains("forest")||key.contains("taiga")||key.contains("grove");
        f.biomePlains=key.contains("plains")||key.contains("meadow")||key.contains("savanna");
        f.biomeOcean=key.contains("ocean");f.biomeRiver=key.contains("river");f.biomeMountain=key.contains("mountain")||key.contains("peak")||key.contains("hills");
        f.biomeDesert=key.contains("desert")||key.contains("badlands")||key.contains("mesa");f.biomeJungle=key.contains("jungle");f.biomeBeach=key.contains("beach");
        int px=(int)Math.floor(x),py=(int)Math.floor(y),pz=(int)Math.floor(z),open=0;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(int dy=1;dy<=5;dy++)if(!blocked(px+dx,py+dy,pz+dz))open++;
        f.openness=open/45f;int walls=0;
        for(int[] dir:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})for(int step=1;step<=5;step++)if(blocked(px+dir[0]*step,py,pz+dir[1]*step)){walls++;break;}
        boolean roof=false;for(int step=1;step<=6;step++)roof|=blocked(px,py+step,pz);
        f.inBuilding=roof&&walls>=3;return f;
    }
    ListenerSample listener() {return EnvironmentGeometry.sample(this);}
}
