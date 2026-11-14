#include "EnvironmentRead.h"
#include <cstring>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <vector>

namespace {
void Need(bool ok,char const* message){if(!ok)throw std::runtime_error(message);}
struct Fixture {
    std::vector<unsigned char> bytes=std::vector<unsigned char>(0x100000);
    unsigned reads{},actors{};std::uint64_t ticks{};std::uintptr_t failAt{};
    template<class T>void put(std::uintptr_t at,T value){
        if(at<0x10000||at-0x10000+sizeof(T)>bytes.size())throw std::runtime_error("Fixture range");
        std::memcpy(bytes.data()+at-0x10000,&value,sizeof value);
    }
    static bool read(void* raw,std::uintptr_t at,void* out,std::size_t size){
        auto& f=*static_cast<Fixture*>(raw);++f.reads;
        if(at==f.failAt||at<0x10000||at-0x10000>f.bytes.size()||size>f.bytes.size()-(at-0x10000))return false;
        std::memcpy(out,f.bytes.data()+at-0x10000,size);return true;
    }
    static std::uint64_t clock(void* raw){return static_cast<Fixture*>(raw)->ticks++;}
    Fixture(){
        put<std::uintptr_t>(0x20048,0x20100);put<std::uintptr_t>(0x20050,0x20120);
        put<std::uintptr_t>(0x20068,0x20200);put<std::uintptr_t>(0x20070,0x20240);
        for(int i=0;i<4;i++)put<std::int64_t>(0x20100+i*8,-1);
        put<std::int64_t>(0x20108,0);put<std::int64_t>(0x20118,1);
        put<std::int64_t>(0x20200,-1);put<std::uint32_t>(0x20208,0x4f6ba419);put<std::uintptr_t>(0x20210,0x22000);
        put<std::int64_t>(0x20220,-1);put<std::uint32_t>(0x20228,0xfd3b0613);put<std::uintptr_t>(0x20230,0x24000);
        put<std::uint32_t>(0x21004,42);
        put<std::uintptr_t>(0x22008,0x22100);put<std::uintptr_t>(0x22010,0x22108);put<std::uintptr_t>(0x22100,0x23000);
        put<std::uintptr_t>(0x22050,0x22200);put<std::uintptr_t>(0x22058,0x22208);put<std::uintptr_t>(0x22200,0x30000);
        put<std::uintptr_t>(0x24008,0x24100);put<std::uintptr_t>(0x24010,0x24108);put<std::uintptr_t>(0x24100,0x25000);
        put<std::uintptr_t>(0x24050,0x24200);put<std::uintptr_t>(0x24058,0x24208);put<std::uintptr_t>(0x24200,0x40000);
    }
    std::uintptr_t add(std::uint32_t type,float health=20){
        Need(actors<128,"Fixture actors");auto i=actors++;auto actor=0x50000+i*0x400;
        put<std::uintptr_t>(actor+16,0x20000);put<std::uint32_t>(actor+24,0x40000+i);
        put<std::uint32_t>(0x23000+i*4,0x40000+i);put<std::uint32_t>(0x25000+i*4,0x40000+i);
        put<std::uint32_t>(0x30000+i*4,type);
        auto component=0x40000+i*80,keys=0x70000+i*4,instance=0x90000+i*136;
        put<std::uintptr_t>(component,keys);put<std::uintptr_t>(component+8,keys+4);
        put<std::uintptr_t>(component+0x18,instance);put<std::uintptr_t>(component+0x20,instance+136);
        put<std::uint32_t>(keys,42);put<float>(instance+0x78,20);put<float>(instance+0x7c,health);
        return actor;
    }
    mp::EnvironmentEntityStatus count(std::vector<std::uintptr_t> const& list,mp::EnvironmentEntityCounts& out,
        std::uintptr_t except=0,std::uint64_t budget=100000,std::size_t removedOffset=0x269){
        for(std::size_t i=0;i<list.size();i++)put<std::uintptr_t>(0x80000+i*8,list[i]);
        auto before=bytes;
        auto status=mp::ReadEnvironmentEntities(0x80000,list.size(),except,0x21000,read,this,clock,this,budget,out,removedOffset);
        Need(bytes==before,"Counting wrote game memory");return status;
    }
};
}
int main(){
    try {
        float body[6]{-1.8f,64,-.8f,-1.2f,65.8f,-.2f},box[6]{};
        Need(mp::EnvironmentEntityBox(body,box)&&box[0]==-18&&box[1]==48&&box[2]==-17&&box[3]==15&&box[4]==81&&box[5]==16,"Player-centered 33-block box incorrect");
        body[1]=std::numeric_limits<float>::quiet_NaN();Need(!mp::EnvironmentEntityBox(body,box),"Nonfinite body accepted");
        Fixture f;auto zombie=f.add(199456),cow=f.add(4875),villager=f.add(16778099);
        auto before=f.bytes;std::uint32_t type=0;
        Need(mp::ReadEnvironmentActorType(zombie,Fixture::read,&f,type)&&type==199456,"Valid actor type was not read");
        Need(f.bytes==before,"Type lookup wrote game memory");
        mp::EnvironmentEntityCounts counts;
        Need(f.count({zombie,cow,villager},counts)==mp::EnvironmentEntityStatus::Known,"Valid nearby list rejected");
        Need(counts.enemies==1&&counts.animals==1&&counts.villagers==1,"Native classes counted incorrectly");
        auto player=f.add(319),item=f.add(64),stand=f.add(317),dead=f.add(199456,0),trader=f.add(886);
        Need(f.count({zombie,zombie,cow,villager,player,item,stand,dead,trader},counts)==mp::EnvironmentEntityStatus::Known,"Exclusion list rejected");
        Need(counts.enemies==1&&counts.animals==1&&counts.villagers==2,"Duplicates, players, nonliving or dead actors counted");
        Need(f.count({zombie,cow},counts,zombie)==mp::EnvironmentEntityStatus::Known&&counts.enemies==0&&counts.animals==1,"Except actor counted");
        Need(f.count({},counts)==mp::EnvironmentEntityStatus::Known&&counts.enemies==0&&counts.animals==0&&counts.villagers==0,"Valid empty list is unknown or stale");
        auto removed=f.add(199456);f.put<unsigned char>(removed+0x269,1);
        Need(f.count({removed,cow},counts)==mp::EnvironmentEntityStatus::Known&&counts.enemies==0&&counts.animals==1,"Removed actor counted");
        Need(f.count({removed},counts,0,100000,0x289)==mp::EnvironmentEntityStatus::Known&&counts.enemies==1,"26.60 read the old removed flag");
        f.put<unsigned char>(removed+0x289,1);
        Need(f.count({removed},counts,0,100000,0x289)==mp::EnvironmentEntityStatus::Known&&counts.enemies==0,"26.60 removed actor counted");
        f.put<std::uint32_t>(0x23000,0);type=77;
        Need(!mp::ReadEnvironmentActorType(zombie,Fixture::read,&f,type),"Stale entity version accepted");
        Need(f.count({cow,zombie},counts)==mp::EnvironmentEntityStatus::Invalid&&counts.animals==0,"Partial stale list published");
        f=Fixture{};zombie=f.add(199456);f.put<std::int64_t>(0x20200,0);f.put<std::uint32_t>(0x20208,123);
        Need(!mp::ReadEnvironmentActorType(zombie,Fixture::read,&f,type)&&f.reads<100,"Hash-chain cycle was not bounded");
        f=Fixture{};zombie=f.add(199456);f.failAt=0x30000;
        Need(f.count({zombie},counts)==mp::EnvironmentEntityStatus::Invalid&&counts.enemies==0,"Unreadable component published");
        f=Fixture{};zombie=f.add(199456,std::numeric_limits<float>::quiet_NaN());
        Need(f.count({zombie},counts)==mp::EnvironmentEntityStatus::Invalid,"Nonfinite health accepted");
        f=Fixture{};zombie=f.add(199456);
        std::vector<std::uintptr_t> many(513,zombie);
        Need(f.count(many,counts)==mp::EnvironmentEntityStatus::Limit,"Oversized query list traversed");
        Need(f.count({zombie},counts,0,0)==mp::EnvironmentEntityStatus::Budget&&counts.enemies==0,"Expired budget published partial counts");
        auto outside=std::uintptr_t(-8);
        Need(mp::ReadEnvironmentEntities(outside,4,0,0x21000,Fixture::read,&f,Fixture::clock,&f,100,counts)==mp::EnvironmentEntityStatus::Invalid,"Overflowing list accepted");
        std::cout<<"Nearby entity type, health, exclusions, stale-token, cycle, unreadable, limit, budget and read-only checks passed\n";
    }catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}
}
