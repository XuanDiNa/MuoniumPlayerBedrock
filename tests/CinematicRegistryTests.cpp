#include "Cinematic.h"
#include <array>
#include <cstring>
#include <iostream>
#include <stdexcept>
namespace {
std::array<unsigned char,65536> arena{};constexpr std::uintptr_t start=0x100000;
bool read(void*,std::uintptr_t p,void* out,std::size_t n){if(p<start||n>arena.size()||p-start>arena.size()-n)return false;std::memcpy(out,arena.data()+p-start,n);return true;}
template<class T>void put(unsigned offset,T value){std::memcpy(arena.data()+offset,&value,sizeof value);}
void need(bool b){if(!b)throw std::runtime_error("Camera sparse registry ownership failed");}
}
int main(){
    constexpr std::uint32_t token=0x1c0001,key=0x4f6047c7;
    put(0x48,start+0x200);put(0x50,start+0x208);put(0x68,start+0x300);put(0x70,start+0x320);
    put(0x200,std::uint64_t(0));put(0x300,std::int64_t(-1));put(0x308,key);put(0x310,start+0x400);
    put(0x408,start+0x500);put(0x410,start+0x508);put(0x500,start+0x1000);put(0x1004,token&0xfffc0000u);
    put(0x420,start+0x700);put(0x428,start+0x704);put(0x700,token);
    put(0x450,start+0x600);put(0x458,start+0x608);put(0x600,start+0x4000);
    std::uintptr_t value{};
    need(mp::CinematicMember(read,nullptr,start,token,key,288,value)&&value==start+0x4000);
    need(mp::CinematicMember(read,nullptr,start,token,key,0,value)&&value==start+0x400);
    need(mp::CinematicMember(read,nullptr,start,token+0x40000,key,288,value)&&value==0); // recycled token
    put(0x1004,std::uint32_t(0xffffffff));need(mp::CinematicMember(read,nullptr,start,token,key,288,value)&&value==0);
    put(0x1004,token&0xfffc0000u);put(0x310,start+arena.size()-4);need(!mp::CinematicMember(read,nullptr,start,token,key,288,value));
    put(0x310,start+0x400);put(0x308,key+1);put(0x300,std::int64_t(0));need(!mp::CinematicMember(read,nullptr,start,token,key,288,value)); // cycle
    put(0x300,std::int64_t(-1));need(mp::CinematicMember(read,nullptr,start,token,key,288,value)&&value==0);
    put(0x308,key);put(0x50,start+0x218);need(!mp::CinematicMember(read,nullptr,start,token,key,288,value)); // non-power-of-two buckets
    std::cout<<"Camera sparse membership, recycled tokens, missing components, invalid pages and cyclic buckets passed\n";
}
