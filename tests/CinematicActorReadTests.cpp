#include "CinematicActorRead.h"
#include <array>
#include <cstring>
#include <iostream>
#include <stdexcept>
namespace {
void need(bool v){if(!v)throw std::runtime_error("Actor component layout regression");}
struct Memory {std::array<unsigned char,4096> bytes{};};constexpr std::uintptr_t base=0x100000;
bool Read(void* raw,std::uintptr_t at,void* out,std::size_t n){auto& m=*static_cast<Memory*>(raw);
    if(at<base||n>m.bytes.size()||at-base>m.bytes.size()-n)return false;std::memcpy(out,m.bytes.data()+at-base,n);return true;}
void pointer(Memory& m,unsigned slot,unsigned value){auto address=base+value;std::memcpy(m.bytes.data()+slot,&address,8);}
}
int main(){try{
    // Independent LL 26.60.7 SDK: BuiltInActorComponents starts at 0x240;
    // StateVector*, AABBShape*, ActorRotation* are its three consecutive slots.
    Memory memory;pointer(memory,0x240,0x500);pointer(memory,0x248,0x600);pointer(memory,0x250,0x700);
    float stateVector[]{5478.58f,70.f,3423.5f,5478.58f,70.f,3423.5f};std::memcpy(memory.bytes.data()+0x500,stateVector,sizeof stateVector);
    float box[]{5478.28f,68.38f,3423.2f,5478.88f,70.18f,3423.8f};std::memcpy(memory.bytes.data()+0x600,box,sizeof box);
    float rotation[]{-12.f,-200.5f};std::memcpy(memory.bytes.data()+0x700,rotation,sizeof rotation);
    mp::CinematicState result;
    need(mp::ReadCinematicActor(Read,&memory,base,mp::Contract2660,result));
    need(result.pitch==-12&&result.yaw==-200.5f&&std::abs(result.foot[1]-68.38)<.001);
    auto original=result;rotation[0]=5478;std::memcpy(memory.bytes.data()+0x700,rotation,sizeof rotation);
    need(!mp::ReadCinematicActor(Read,&memory,base,mp::Contract2660,result));need(std::memcmp(&result,&original,sizeof result)==0);
    pointer(memory,0x250,4092);need(!mp::ReadCinematicActor(Read,&memory,base,mp::Contract2660,result));
    need(mp::Contract2645.actorAabb==0x220&&mp::Contract2645.actorRotation==0x228);
    need(mp::Contract2651.actorAabb==0x220&&mp::Contract2651.actorRotation==0x228);
    need(mp::Contract2652.actorAabb==0x220&&mp::Contract2652.actorRotation==0x228);
    std::cout<<"Independent 26.60 actor layout, player foot/rotation, corrupt sample refusal and older contracts passed\n";return 0;
}catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}}
