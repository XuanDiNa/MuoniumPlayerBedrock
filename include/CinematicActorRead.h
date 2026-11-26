#pragma once
#include "Cinematic.h"
#include "NativeContracts.h"
#include <cmath>
namespace mp {
inline bool ReadCinematicActor(CinematicRead read,void* context,std::uintptr_t actor,NativeContract const& profile,CinematicState& state) noexcept {
    std::uintptr_t box{},rotation{};float bounds[6]{},angles[2]{};
    if(!read||actor<65536||actor>0x7fffffffffff-4096
        ||!read(context,actor+profile.actorAabb,&box,8)||!read(context,actor+profile.actorRotation,&rotation,8)
        ||!read(context,box,bounds,sizeof bounds)||!read(context,rotation,angles,sizeof angles))return false;
    for(unsigned i=0;i<3;++i){if(!std::isfinite(bounds[i])||!std::isfinite(bounds[i+3])||bounds[i]>bounds[i+3]
        ||std::abs(bounds[i])>32000000||std::abs(bounds[i+3])>32000000)return false;}
    if(!std::isfinite(angles[0])||std::abs(angles[0])>90||!std::isfinite(angles[1])||std::abs(angles[1])>36000)return false;
    state.foot[0]=(static_cast<double>(bounds[0])+bounds[3])*.5;state.foot[1]=bounds[1];state.foot[2]=(static_cast<double>(bounds[2])+bounds[5])*.5;
    state.pitch=angles[0];state.yaw=angles[1];return true;
}
}
