#include "Cinematic.h"
#include <cmath>
#include <cstring>
namespace mp {
bool CinematicInputChanged(CinematicState const& a,CinematicState const& b) noexcept {
    double distance{};for(int i=0;i<3;i++){auto delta=a.foot[i]-b.foot[i];distance+=delta*delta;}
    auto yaw=std::remainder(a.yaw-b.yaw,360.f);
    return !std::isfinite(distance)||distance>1e-8||!std::isfinite(yaw)||std::abs(yaw)>.0001f
        ||!std::isfinite(b.pitch)||std::abs(a.pitch-b.pitch)>.0001f||b.health<a.health;
}
bool ValidCinematicCommand(CinematicCommand const& c,CinematicState const& s,std::uint64_t now) noexcept {
    if(c.magic!=0x4350434d||c.version!=1||s.magic!=0x5350434d||s.version!=1
        ||!c.sequence||!s.sequence||!s.epoch||c.epoch!=s.epoch||c.input!=s.input
        ||c.active!=1||(c.flags&~1u)||s.eligible!=1||!c.at||c.at>now||now-c.at>150
        ||!s.at||s.at>now||now-s.at>250)return false;
    double distance{};
    for(int i=0;i<3;i++){
        if(!std::isfinite(c.position[i])||!std::isfinite(s.foot[i])||std::abs(c.position[i])>32000000)return false;
        auto d=c.position[i]-s.foot[i];distance+=d*d;
    }
    return distance<=16*16&&std::isfinite(c.yaw)&&std::abs(c.yaw)<=36000&&std::isfinite(c.pitch)&&std::abs(c.pitch)<=90
        &&std::isfinite(c.roll)&&std::abs(c.roll)<=180&&std::isfinite(c.fovOffset)&&std::abs(c.fovOffset)<=20;
}
void CinematicForward(float const (&q)[4],float (&o)[3]) noexcept {
    o[0]=-2*(q[0]*q[2]+q[1]*q[3]);o[1]=-2*(q[1]*q[2]-q[0]*q[3]);o[2]=-(1-2*(q[0]*q[0]+q[1]*q[1]));
}
bool CinematicCommandCache::select(CinematicCommand const* receipt,CinematicState const& state,std::uint64_t now,CinematicCommand& out) noexcept {
    // Receipt includes explicit inactive/invalid commands: never resurrect an
    // earlier pose after revocation. A busy transport alone is not revocation.
    if(receipt)last_=*receipt;
    if(!ValidCinematicCommand(last_,state,now)){clear();return false;}
    out=last_;return true;
}
bool CinematicPatch::apply(void* component,CinematicCommand const& c) noexcept {
    if(applied_||!component)return false;
    constexpr double rad=3.14159265358979323846/180;
    auto yaw=(180-c.yaw)*rad*.5,pitch=-c.pitch*rad*.5,roll=-c.roll*rad*.5;
    double cy=std::cos(yaw),sy=std::sin(yaw),cx=std::cos(pitch),sx=std::sin(pitch),cz=std::cos(roll),sz=std::sin(roll);
    float q[4]{float(cy*sx*cz+sy*cx*sz),float(sy*cx*cz-cy*sx*sz),float(cy*cx*sz-sy*sx*cz),float(cy*cx*cz+sy*sx*sz)};
    float p[3]{float(c.position[0]),float(c.position[1]),float(c.position[2])},fov{};
    auto bytes=static_cast<std::byte*>(component);std::memcpy(&fov,bytes+80,4);
    if(!std::isfinite(fov)||fov<10*rad||fov>170*rad)return false;
    fov+=float(c.fovOffset*rad);if(fov<10*rad||fov>170*rad)return false;
    for(float f:q)if(!std::isfinite(f))return false;
    for(float f:p)if(!std::isfinite(f)||std::abs(f)>32000000)return false;
    std::memcpy(before_.data(),bytes+48,28);std::memcpy(before_.data()+28,bytes+80,4);std::memcpy(before_.data()+32,bytes+92,64);
    std::memcpy(written_.data(),q,16);std::memcpy(written_.data()+16,p,12);std::memcpy(written_.data()+28,&fov,4);
    float identity[16]{};identity[0]=identity[5]=identity[10]=identity[15]=1;std::memcpy(written_.data()+32,identity,64);
    // Mark ownership before the first write so an interrupted copy can still
    // restore fields that reached their complete owned value.
    applied_=true;
    for(unsigned i=0;i<96;i+=4){auto offset=i<28?48+i:i==28?80:92+i-32;std::memcpy(bytes+offset,written_.data()+i,4);}
    return true;
}
bool CinematicPatch::restore(void* component) noexcept {
    if(!applied_||!component)return false;applied_=false;bool unchanged=true;auto bytes=static_cast<std::byte*>(component);
    for(unsigned i=0;i<96;i+=4){auto offset=i<28?48+i:i==28?80:92+i-32;
        if(!std::memcmp(bytes+offset,written_.data()+i,4))std::memcpy(bytes+offset,before_.data()+i,4);else unchanged=false;
    }
    return unchanged;
}
}
