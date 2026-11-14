#include "EnvironmentRead.h"
#include <array>
#include <cmath>
#include <string_view>
namespace mp {
namespace {
struct Memory {
    EnvironmentRead copy;void* context;
    template<class T>bool get(std::uintptr_t base,std::size_t offset,T& value) const {
        return base&&base<=UINTPTR_MAX-offset&&base+offset<=UINTPTR_MAX-sizeof(T)
            &&copy(context,base+offset,&value,sizeof value);
    }
    bool span(std::uintptr_t base,std::size_t offset,std::size_t stride,std::size_t max,std::uintptr_t& start,std::size_t& count) const {
        std::uintptr_t end{};if(!get(base,offset,start)||!get(base,offset+8,end)||end<start||(end-start)%stride)return false;
        count=(end-start)/stride;return start&&count&&count<=max;
    }
};
}
bool ReadEnvironmentActorType(std::uintptr_t actor,EnvironmentRead read,void* context,std::uint32_t& type) noexcept {
    type=0;if(!read||!actor||(actor&7))return false;Memory m{read,context};
    std::uintptr_t registry{},buckets{},dense{},storage{},pages{},page{},values{};
    std::uint32_t token{},again{};std::size_t bucketCount{},denseCount{},pageCount{},valueCount{};
    if(!m.get(actor,16,registry)||!m.get(actor,24,token)||(token&0x3ffffu)==0x3ffffu
        ||!m.span(registry,0x48,8,65536,buckets,bucketCount)||(bucketCount&(bucketCount-1))
        ||!m.span(registry,0x68,32,65536,dense,denseCount))return false;
    constexpr std::uint32_t key=0x4f6ba419;
    std::int64_t index{};if(!m.get(buckets,8*(key&(bucketCount-1)),index))return false;
    for(std::size_t hops=0;hops<denseCount&&hops<64&&index!=-1;++hops) {
        if(index<0||std::uint64_t(index)>=denseCount)return false;
        auto entry=dense+std::size_t(index)*32;std::uint32_t hash{};
        if(!m.get(entry,8,hash))return false;
        if(hash==key){if(!m.get(entry,16,storage))return false;break;}
        if(!m.get(entry,0,index))return false;
    }
    if(!storage||!m.span(storage,8,8,128,pages,pageCount))return false;
    auto sparsePage=(token&0x3ffffu)>>11;
    if(sparsePage>=pageCount||!m.get(pages,8*sparsePage,page))return false;
    std::uint32_t packed{};
    if(!m.get(page,4*(token&0x7ffu),packed)||(packed^(token&0xfffc0000u))>=0x3ffffu
        ||!m.span(storage,0x50,8,2048,values,valueCount))return false;
    auto packedIndex=packed&0x3ffffu;
    if((packedIndex>>7)>=valueCount||!m.get(values,8*(packedIndex>>7),page)
        ||!m.get(page,4*(packedIndex&127),type)||!m.get(actor,24,again)||again!=token){type=0;return false;}
    return true;
}
bool EnvironmentEntityBox(float const (&box)[6],float (&query)[6]) noexcept {
    for(int i=0;i<3;++i)if(!std::isfinite(box[i])||!std::isfinite(box[i+3])||box[i]>=box[i+3]
        ||std::abs(box[i])>32000000||std::abs(box[i+3])>32000000||box[i+3]-box[i]>32)return false;
    // Java's BlockPos AABB inflated by 16: [floor(position)-16, floor(position)+17].
    float center[3]{(box[0]+box[3])*.5f,box[1],(box[2]+box[5])*.5f};
    for(int i=0;i<3;++i){query[i]=std::floor(center[i])-16;query[i+3]=query[i]+33;}
    return true;
}
EnvironmentEntityStatus ReadEnvironmentEntities(std::uintptr_t entries,std::size_t count,
    std::uintptr_t except,std::uintptr_t healthAttribute,EnvironmentRead read,void* context,
    EnvironmentEntityClock clock,void* clockContext,std::uint64_t budget,EnvironmentEntityCounts& out,std::size_t removedOffset,std::size_t attributeStride) noexcept {
    out={};if(!read||!clock)return EnvironmentEntityStatus::Invalid;
    if(count>512)return EnvironmentEntityStatus::Limit;
    if(count&&(!entries||(entries&7)||entries>UINTPTR_MAX-count*sizeof(std::uintptr_t)))return EnvironmentEntityStatus::Invalid;
    auto start=clock(clockContext);
    auto expired=[&]{auto now=clock(clockContext);return now<start||now-start>=budget;};
    if(expired())return EnvironmentEntityStatus::Budget;
    std::array<std::uintptr_t,512> actors{};std::array<std::uintptr_t,1024> seen{};
    if(count&&!read(context,entries,actors.data(),count*sizeof(std::uintptr_t)))return EnvironmentEntityStatus::Invalid;
    Memory m{read,context};EnvironmentEntityCounts result;std::uintptr_t owner{};
    if(except&&!m.get(except,16,owner))return EnvironmentEntityStatus::Invalid;
    for(std::size_t i=0;i<count;++i){
        if(expired())return EnvironmentEntityStatus::Budget;
        auto actor=actors[i];if(!actor||(actor&7))return EnvironmentEntityStatus::Invalid;
        if(actor==except)continue;
        auto slot=(actor>>4)%seen.size();while(seen[slot]&&seen[slot]!=actor)slot=(slot+1)%seen.size();
        if(seen[slot])continue;seen[slot]=actor;
        std::uintptr_t registry{};unsigned char removed{};std::uint32_t type{},token{},again{};
        if(!m.get(actor,16,registry)||(owner&&registry!=owner)||!m.get(actor,24,token)
            ||!m.get(actor,removedOffset,removed)||removed>1)return EnvironmentEntityStatus::Invalid;
        if(removed)continue;
        if(!ReadEnvironmentActorType(actor,read,context,type))return EnvironmentEntityStatus::Invalid;
        // Native group masks, not Java inheritance. Explicitly registered
        // villager v1/v2 and wandering trader are the villager counterparts.
        bool enemy=(type&0xb00u)==0xb00u,animal=(type&0x1300u)==0x1300u;
        bool villager=type==0x100030fu||type==0x1000373u||type==886;
        if(enemy||animal||villager){
            float health{};if(!ReadEnvironmentHealth(actor,healthAttribute,read,context,health,attributeStride)
                ||!m.get(actor,24,again)||again!=token)return EnvironmentEntityStatus::Invalid;
            if(health>0){if(enemy)++result.enemies;if(animal)++result.animals;if(villager)++result.villagers;}
        }
    }
    if(expired())return EnvironmentEntityStatus::Budget;
    out=result;return EnvironmentEntityStatus::Known;
}
bool ReadEnvironmentHealth(std::uintptr_t actor,std::uintptr_t attribute,EnvironmentRead read,void* context,float& ratio,std::size_t attributeStride) noexcept {
    if(!read||(attributeStride!=128&&attributeStride!=136))return false;Memory m{read,context};
    std::uintptr_t registry{},buckets{},dense{},storage{},pages{},page{},values{},component{},keys{},instances{};
    std::uint32_t token{},key{};std::size_t bucketCount{},denseCount{},pageCount{},valueCount{},keyCount{},instanceCount{};
    if(!m.get(actor,16,registry)||!m.get(actor,24,token)||!m.get(attribute,4,key)
        ||!m.span(registry,0x48,8,65536,buckets,bucketCount)||(bucketCount&(bucketCount-1))
        ||!m.span(registry,0x68,32,65536,dense,denseCount))return false;
    constexpr std::uint32_t componentKey=0xFD3B0613;
    std::int64_t index{};if(!m.get(buckets,8*(componentKey&(bucketCount-1)),index))return false;
    // Native 8E1790 creates missing storage. This bounded equivalent never does.
    for(std::size_t hops=0;hops<denseCount&&hops<64&&index!=-1;++hops) {
        if(index<0||std::uint64_t(index)>=denseCount)return false;
        auto entry=dense+std::size_t(index)*32;std::uint32_t hash{};
        if(!m.get(entry,8,hash))return false;
        if(hash==componentKey){if(!m.get(entry,16,storage))return false;break;}
        if(!m.get(entry,0,index))return false;
    }
    if(!storage||!m.span(storage,8,8,128,pages,pageCount))return false;
    auto sparsePage=(token&0x3ffffu)>>11;
    if(sparsePage>=pageCount||!m.get(pages,8*sparsePage,page))return false;
    std::uint32_t packed{};
    if(!m.get(page,4*(token&0x7ffu),packed)||(packed^(token&0xfffc0000u))>=0x3ffffu
        ||!m.span(storage,0x50,8,2048,values,valueCount))return false;
    auto packedIndex=packed&0x3ffffu;
    if((packedIndex>>7)>=valueCount||!m.get(values,8*(packedIndex>>7),page)||!page||page>UINTPTR_MAX-80*(packedIndex&127))return false;
    component=page+80*(packedIndex&127);
    if(!m.span(component,0,4,1024,keys,keyCount)||!m.span(component,0x18,attributeStride,1024,instances,instanceCount)||keyCount!=instanceCount)return false;
    std::size_t lo=0,hi=keyCount;
    while(lo<hi){auto mid=lo+(hi-lo)/2;std::uint32_t found{};if(!m.get(keys,mid*4,found))return false;if(found<key)lo=mid+1;else hi=mid;}
    std::uint32_t found{};if(lo==keyCount||!m.get(keys,lo*4,found)||found!=key)return false;
    auto instance=instances+lo*attributeStride;float current{},maximum{};
    // Exact independent current/max health consumers 2539540 / 2539660.
    if(!m.get(instance,0x7c,current)||!m.get(instance,0x78,maximum)||!std::isfinite(current)||!std::isfinite(maximum)
        ||maximum<=0||maximum>1e9f||current<0||current>maximum)return false;
    ratio=current/maximum;return true;
}
std::uint8_t EnvironmentSurface(char const* chars,std::size_t length) noexcept {
    if(!chars||!length||length>191)return 1;
    std::string_view name(chars,length);
    // Exact BlockType name key; classification remains an approximation.
    auto contains=[&](std::string_view word){return name.find(word)!=name.npos;};
    if(contains("glass"))return 4;
    if(contains("wool")||contains("carpet")||contains("moss")||contains("honey"))return 3;
    if(contains("snow"))return 7;
    if(contains("sand")&&!contains("sandstone"))return 6;
    if(contains("dirt")||contains("grass")||contains("gravel")||contains("podzol")||contains("farmland")||contains("mud"))return 5;
    if(contains("planks")||contains("wood")||contains("log")||contains("bamboo")||contains("stem")||contains("hyphae")
        ||contains("chest")||contains("barrel")||contains("bookshelf"))return 2;
    if(contains("iron")||contains("copper")||contains("gold")||contains("anvil")||contains("chain"))return 10;
    return 1;
}
int EnvironmentBlockClue(char const* chars,std::size_t length) noexcept {
    if(!chars||length>63)return 0;char compact[64]{};unsigned n=0;
    for(std::size_t i=0;i<length;i++)if(chars[i]!='_')compact[n++]=chars[i];
    std::string_view name(compact,n);
    for(auto v:{"sculk","sculkcatalyst","sculksensor","sculkshrieker","sculkvein","reinforceddeepslate"})if(name==v)return 1;
    for(auto v:{"prismarine","prismarinebricks","darkprismarine","sealantern"})if(name==v)return 2;
    for(auto v:{"stonebrick","stonebricks","mossystonebricks","crackedstonebricks","chiseledstonebricks","infestedstonebricks"})if(name==v)return 3;
    for(auto v:{"blackstone","polishedblackstone","polishedblackstonebricks","gildedblackstone","chiseledpolishedblackstone","basalt","polishedbasalt"})if(name==v)return 4;
    for(auto v:{"craftingtable","cartographytable","smoker","fletchingtable","loom","blastfurnace","grindstone","stonecutter","smithingtable","bell","composter","barrel"})if(name==v)return 5;
    return 0;
}
}
