#include "Cinematic.h"
namespace mp {
namespace {
struct Reader {
    CinematicRead copy;void* owner;
    template<class T>bool get(std::uintptr_t p,T& out)const {
        return p>=65536&&p<=0x7fffffffffff-sizeof(T)&&copy(owner,p,&out,sizeof(T));
    }
};
bool range(std::uintptr_t first,std::uintptr_t last,std::size_t stride,std::size_t limit){
    return first>=65536&&last>=first&&last<=0x7fffffffffff&&(last-first)%stride==0&&(last-first)/stride<=limit;
}
}
bool CinematicMember(CinematicRead copy,void* owner,std::uintptr_t registry,std::uint32_t token,std::uint32_t key,std::size_t stride,std::uintptr_t& value) noexcept {
    value=0;if(!copy||registry<65536||registry>0x7ffffffff000)return false;Reader read{copy,owner};
    std::uintptr_t buckets{},end{},dense{},denseEnd{};
    if(!read.get(registry+0x48,buckets)||!read.get(registry+0x50,end)||!read.get(registry+0x68,dense)||!read.get(registry+0x70,denseEnd)
        ||!range(buckets,end,8,65536)||buckets==end||!range(dense,denseEnd,32,65536))return false;
    auto count=(end-buckets)/8,n=(denseEnd-dense)/32;if(count&(count-1))return false;
    std::int64_t index{};if(!read.get(buckets+8*(key&(count-1)),index))return false;std::uintptr_t storage{};
    for(unsigned hops=0;index!=-1;++hops){
        if(hops==64||index<0||std::uint64_t(index)>=n)return false;
        auto entry=dense+32*index;std::uint32_t found{};
        if(!read.get(entry+8,found))return false;
        if(found==key){if(!read.get(entry+16,storage))return false;break;}
        if(!read.get(entry,index))return false;
    }
    if(!storage)return true;
    if(storage<65536||storage>0x7ffffffff000)return false;
    std::uintptr_t pages{},pagesEnd{};if(!read.get(storage+8,pages)||!read.get(storage+16,pagesEnd)||!range(pages,pagesEnd,8,128))return false;
    auto pageIndex=(token&0x3ffffu)>>11;if(pageIndex>=(pagesEnd-pages)/8)return true;
    std::uintptr_t page{};if(!read.get(pages+8*pageIndex,page))return false;if(!page)return true;
    std::uint32_t packed{};if(!read.get(page+4*(token&0x7ffu),packed))return false;
    if((packed^(token&0xfffc0000u))>=0x3ffffu)return true;
    auto ix=packed&0x3ffffu;std::uintptr_t entities{},entitiesEnd{};std::uint32_t entity{};
    if(!read.get(storage+32,entities)||!read.get(storage+40,entitiesEnd)||!range(entities,entitiesEnd,4,0x40000)
        ||ix>=(entitiesEnd-entities)/4||!read.get(entities+4*ix,entity)||entity!=token)return false;
    if(!stride){value=storage;return true;}
    std::uintptr_t values{},valuesEnd{},valuesPage{};
    if(stride>4096||!read.get(storage+0x50,values)||!read.get(storage+0x58,valuesEnd)||!range(values,valuesEnd,8,2048)
        ||(ix>>7)>=(valuesEnd-values)/8||!read.get(values+8*(ix>>7),valuesPage)||valuesPage<65536
        ||valuesPage>0x7fffffffffff-stride*128)return false;
    value=valuesPage+stride*(ix&127);return true;
}
}
