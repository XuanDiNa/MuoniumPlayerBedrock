#pragma once
#include <cstdint>
#include <array>
#include <cstring>
namespace mp {
// Official LeviLamina 26.60.7 ll::memory ABI. The original-pointer storage
// remains resident: the shared dispatcher updates it as other hooks change.
struct CinematicLaminaApi {
    int (*attach)(void*,void*,void**,int,bool){};
    bool (*detach)(void*,void*,bool){};
    std::uintptr_t reviewedRenderOwner{};
    std::array<std::uintptr_t,2> reviewedSetupOwners{};
};
bool ResolveCinematicLaminaApi(CinematicLaminaApi&) noexcept;
using CinematicCodeRead=bool(*)(void*,std::uintptr_t,void*,std::size_t);
// Read-only identity check, never a trampoline to invoke or rewrite. Only the
// reviewed owner's exact address can admit a previously occupied entry.
inline bool CinematicJumpTo(CinematicCodeRead read,void* context,std::uintptr_t start,std::uintptr_t owner) noexcept {
    if(!read||!owner||start==owner)return false;
    std::array<std::uintptr_t,5> visited{};
    for(unsigned hop=0;hop<visited.size();++hop){
        if(start==owner)return true;
        if(start<65536||start>0x7fffffffffff-16)return false;
        for(unsigned i=0;i<hop;++i)if(visited[i]==start)return false;
        visited[hop]=start;
        unsigned char code[6]{};if(!read(context,start,code,sizeof code))return false;
        std::int32_t delta{};
        if(code[0]==0xe9){std::memcpy(&delta,code+1,4);start=static_cast<std::uintptr_t>(static_cast<std::int64_t>(start+5)+delta);}
        else if(code[0]==0xff&&code[1]==0x25){std::memcpy(&delta,code+2,4);
            auto slot=static_cast<std::uintptr_t>(static_cast<std::int64_t>(start+6)+delta);
            if(slot<65536||slot>0x7fffffffffff-8||!read(context,slot,&start,8))return false;
        }else return false;
    }
    return false;
}
}
