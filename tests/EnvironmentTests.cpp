#include "EnvironmentBridge.h"
#include "EnvironmentRead.h"
#include <cstring>
#include <vector>
#include <limits>
#include <iostream>
#include <stdexcept>
void Need(bool x){if(!x)throw std::runtime_error("Environment assertion failed");}
struct Grid {unsigned reads{};std::uint64_t clock{1000};};
mp::EnvironmentCell Read(void* p,int,int y,int){auto& g=*static_cast<Grid*>(p);++g.reads;return {static_cast<std::uint8_t>(y<0?1:0)};}
std::uint64_t Clock(void* p){return static_cast<Grid*>(p)->clock++;}
struct HealthFixture {
    std::vector<unsigned char> bytes=std::vector<unsigned char>(65536);unsigned reads{};
    template<class T>void put(std::uintptr_t at,T value){std::memcpy(bytes.data()+at-0x10000,&value,sizeof value);}
    static bool read(void* raw,std::uintptr_t at,void* out,std::size_t size){auto& f=*static_cast<HealthFixture*>(raw);++f.reads;
        if(at<0x10000||at-0x10000>f.bytes.size()||size>f.bytes.size()-(at-0x10000))return false;
        std::memcpy(out,f.bytes.data()+at-0x10000,size);return true;}
    std::size_t stride;
    HealthFixture(std::size_t itemStride=136):stride(itemStride){
        put<std::uintptr_t>(0x10010,0x10100);put<std::uint32_t>(0x10018,0x40002);put<std::uint32_t>(0x10204,42);
        put<std::uintptr_t>(0x10148,0x10300);put<std::uintptr_t>(0x10150,0x10320);
        put<std::uintptr_t>(0x10168,0x10400);put<std::uintptr_t>(0x10170,0x10440);
        put<std::int64_t>(0x10318,0);put<std::int64_t>(0x10400,1);put<std::uint32_t>(0x10408,1);
        put<std::int64_t>(0x10420,-1);put<std::uint32_t>(0x10428,0xfd3b0613);put<std::uintptr_t>(0x10430,0x10500);
        put<std::uintptr_t>(0x10508,0x10600);put<std::uintptr_t>(0x10510,0x10608);put<std::uintptr_t>(0x10600,0x11000);
        put<std::uint32_t>(0x11008,0x40003);
        put<std::uintptr_t>(0x10550,0x10700);put<std::uintptr_t>(0x10558,0x10708);put<std::uintptr_t>(0x10700,0x14000);
        auto c=0x14000+3*80;put<std::uintptr_t>(c,0x15000);put<std::uintptr_t>(c+8,0x1500c);
        put<std::uintptr_t>(c+0x18,0x16000);put<std::uintptr_t>(c+0x20,0x16000+3*stride);
        put<std::uint32_t>(0x15000,2);put<std::uint32_t>(0x15004,42);put<std::uint32_t>(0x15008,99);
        put<float>(0x16000+stride+0x7c,5);put<float>(0x16000+stride+0x78,20);
    }
    bool health(float& value){return mp::ReadEnvironmentHealth(0x10000,0x10200,read,this,value,stride);}
};
int main(){
    HealthFixture oldHealth(128);float oldRatio=-1;Need(oldHealth.health(oldRatio)&&oldRatio==.25f);
    auto oldOriginal=oldHealth.bytes;oldHealth.stride=136;Need(!oldHealth.health(oldRatio));Need(oldHealth.bytes==oldOriginal);
    oldHealth.stride=129;auto beforeReads=oldHealth.reads;Need(!oldHealth.health(oldRatio)&&oldHealth.reads==beforeReads);
    HealthFixture health;auto original=health.bytes;float ratio=-1;Need(health.health(ratio)&&ratio==.25f);Need(health.bytes==original);
    health.put<std::uint32_t>(0x11008,3);Need(!health.health(ratio)); // Stale entity version.
    health=HealthFixture{};health.put<float>(0x16000+136+0x78,0);Need(!health.health(ratio));
    health=HealthFixture{};health.put<float>(0x16000+136+0x7c,std::numeric_limits<float>::quiet_NaN());Need(!health.health(ratio));
    health=HealthFixture{};health.put<std::uint32_t>(0x15004,41);Need(!health.health(ratio));
    health=HealthFixture{};health.put<std::int64_t>(0x10400,0);Need(!health.health(ratio));Need(health.reads<50); // Cycle is bounded.
    health=HealthFixture{};health.put<std::int64_t>(0x10318,-1);Need(!health.health(ratio));Need(health.reads<20);
    Need(mp::EnvironmentSurface("tile.glass.name",15)==4);Need(mp::EnvironmentSurface("tile.planks.name",16)==2);
    Need(mp::EnvironmentSurface("tile.wool.name",14)==3);Need(mp::EnvironmentSurface("tile.sand.name",14)==6);
    Need(mp::EnvironmentSurface("tile.sandstone.name",19)==1);Need(mp::EnvironmentSurface("tile.iron_block.name",20)==10);
    Need(mp::EnvironmentBlockClue("sculk_sensor",12)==1);Need(mp::EnvironmentBlockClue("sea_lantern",11)==2);
    Need(mp::EnvironmentBlockClue("stonebrick",10)==3);Need(mp::EnvironmentBlockClue("basalt",6)==4);
    Need(mp::EnvironmentBlockClue("blast_furnace",13)==5);Need(mp::EnvironmentBlockClue("not_a_barrel",12)==0);
    mp::EnvironmentScan scan;mp::WorldPacket world;world.eye[1]=2;world.look[2]=1;world.dimension=0;
    Grid g;scan.start(world,7,1000);
    Need(!scan.step(Read,&g,Clock,&g,100,100000));Need(g.reads==100);Need(!scan.complete());
    while(!scan.complete())scan.step(Read,&g,Clock,&g,1024,100000);
    Need(g.reads==33*33*33);Need(scan.packet().cells[0]==1);Need(scan.packet().epoch==7);
    Need(!scan.restart(world,7,1100));Need(scan.restart(world,8,1100));Need(scan.restart(world,7,2600));
    auto far=world;far.eye[0]=10;Need(scan.restart(far,7,1100));
    scan.clear();Need(!scan.complete());Need(scan.packet().valid==0);
    g={};scan.start(world,9,1000);Need(!scan.step(Read,&g,Clock,&g,1024,1));Need(g.reads<=32);
    struct Slow {unsigned reads{};std::uint64_t time{};} slow;
    auto slowCell=[](void* raw,int,int,int){auto& s=*static_cast<Slow*>(raw);++s.reads;s.time+=10;return mp::EnvironmentCell{1};};
    auto slowClock=[](void* raw)->std::uint64_t{return static_cast<Slow*>(raw)->time;};
    scan.start(world,9,1000);Need(!scan.step(slowCell,&slow,slowClock,&slow,1024,25));
    Need(slow.reads==3); // Stop after one over-budget native cell, not another 31.
    mp::EnvironmentCell slab;slab.material=2;slab.bounds={0,0,0,4096,2048,4096};
    auto shaped=[](void* p,int,int,int){return *static_cast<mp::EnvironmentCell*>(p);};
    scan.start(world,10,1000);
    while(!scan.complete())scan.step(shaped,&slab,Clock,&g,2048,100000);
    Need(scan.packet().version==2);Need(scan.packet().shapeCount==3);
    Need(scan.packet().shapes[2]==slab.bounds);Need(scan.packet().shapeIds[0]==2);
    Need(scan.packet().cells[0]==2);Need(scan.packet().known&128);
    Need(mp::EnvironmentTimeOfDay(-1)==23999);Need(mp::EnvironmentTimeOfDay(24001)==1);
    char key[64]{};Need(mp::CopyEnvironmentKey(key,"minecraft:cherry_grove",22));
    Need(!mp::CopyEnvironmentKey(key,"bad name",8));Need(key[0]==0);
    struct Shapes {unsigned n{};} shapes;
    auto varied=[](void* raw,int,int,int){auto& n=static_cast<Shapes*>(raw)->n;mp::EnvironmentCell c;c.material=1;c.bounds[4]=short(++n%4096+1);return c;};
    scan.start(world,10,1000);while(!scan.complete())scan.step(varied,&shapes,Clock,&g,2048,100000);
    Need(scan.packet().shapeCount==256);Need(scan.packet().shapeIds[300]==0); // Conservative overflow, no wrap.
    std::cout<<"Environment bounded scan, epoch/movement/timeout reset and complete-only publication passed\n";
}
