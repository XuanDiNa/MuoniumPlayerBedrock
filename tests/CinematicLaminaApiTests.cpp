#include "Runtime.h"
#include "CinematicLaminaHooks.h"
#include <iostream>
#include <stdexcept>
namespace {
unsigned checks{};void need(bool value){++checks;if(!value)throw std::runtime_error("Shared camera ownership check failed");}
struct Memory{std::array<unsigned char,1024> bytes{};};
constexpr std::uintptr_t base=0x100000;
bool Read(void* raw,std::uintptr_t at,void* out,std::size_t count){auto& memory=*static_cast<Memory*>(raw);
    if(at<base||count>memory.bytes.size()||at-base>memory.bytes.size()-count)return false;
    std::memcpy(out,memory.bytes.data()+at-base,count);return true;}
void relative(Memory& m,unsigned from,unsigned to){m.bytes[from]=0xe9;std::int32_t delta=to-from-5;std::memcpy(m.bytes.data()+from+1,&delta,4);}
void indirect(Memory& m,unsigned from,unsigned slot,unsigned to){m.bytes[from]=0xff;m.bytes[from+1]=0x25;std::int32_t delta=slot-from-6;
    std::memcpy(m.bytes.data()+from+2,&delta,4);auto address=base+to;std::memcpy(m.bytes.data()+slot,&address,8);}
}
int main(){try{
    Memory m;relative(m,0,128);indirect(m,128,120,256);indirect(m,256,264,512);
    need(mp::CinematicJumpTo(Read,&m,base,base+512));
    need(!mp::CinematicJumpTo(Read,&m,base,0));need(!mp::CinematicJumpTo(Read,&m,base,base));
    need(!mp::CinematicJumpTo(Read,&m,base,base+513));
    need(!mp::CinematicJumpTo(nullptr,&m,base,base+512));
    auto bad=m;bad.bytes[128]=0xcc;need(!mp::CinematicJumpTo(Read,&bad,base,base+512));
    bad=m;relative(bad,256,0);need(!mp::CinematicJumpTo(Read,&bad,base,base+512));
    bad=m;std::uintptr_t invalid=0;std::memcpy(bad.bytes.data()+120,&invalid,8);need(!mp::CinematicJumpTo(Read,&bad,base,base+512));
    need(!mp::CinematicJumpTo(Read,&m,base+1020,base+512));
    bad=m;relative(bad,0,64);relative(bad,64,128);relative(bad,128,192);relative(bad,192,256);relative(bad,256,512);
    need(!mp::CinematicJumpTo(Read,&bad,base,base+512));
    mp::CinematicLaminaApi api;need(!mp::ResolveCinematicLaminaApi(api)&&!api.attach&&!api.detach&&!api.reviewedRenderOwner
        &&!api.reviewedSetupOwners[0]&&!api.reviewedSetupOwners[1]);
    std::cout<<"Shared camera bounded ownership, loop/truncation/unknown refusal and absent API: "<<checks<<" checks passed\n";
    return 0;
}catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}}
