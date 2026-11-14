#pragma once
#include "WorldBridge.h"
#include <array>
#include <cmath>
#include <algorithm>
namespace mp {
struct CompositionCameraValues {WorldPacket world;std::array<float,16> renderView{};};
// Match only CPU-visible constants owned by a live public D3D11 Map/Unmap.
// No GPU readback, frame prediction, widened comparison or game pointer.
inline bool MatchCompositionCamera(float const* constants,WorldPacket const& world,float const* paired,CompositionCameraValues& out) noexcept {
    if(!world.valid||!world.epoch||!paired||!constants)return false;
    for(unsigned i=0;i<32;++i)if(!std::isfinite(constants[i]))return false;
    if(!std::isfinite(constants[112])||!std::isfinite(constants[113]))return false;
    for(unsigned i=0;i<16;++i){
        if(!std::isfinite(paired[i])||!std::isfinite(world.projection[i]))return false;
        if(i!=10&&i!=14&&std::abs(constants[i]-world.projection[i])>.0005f)return false;
        if(std::abs(constants[16+i]-paired[i])>(i>=12&&i<15?.01f:.0001f))return false;
    }
    if(std::abs(constants[11]+1)>.001f||constants[0]<=.01f||constants[5]<=.01f||constants[14]>=0)return false;
    out.world=world;std::copy(constants,constants+16,out.world.projection);
    out.world.projection[8]+=constants[112];out.world.projection[9]-=constants[113];
    std::copy(paired,paired+16,out.renderView.begin());return true;
}
}
