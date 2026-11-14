#include "WorldBridge.h"
#include <cmath>
namespace mp {
bool ValidWorldCamera(WorldPacket const& p) noexcept {
    for(double n:p.eye)if(!std::isfinite(n)||std::abs(n)>32000000)return false;
    for(double n:p.look)if(!std::isfinite(n))return false;
    for(float n:p.view)if(!std::isfinite(n))return false;
    for(float n:p.projection)if(!std::isfinite(n))return false;
    if(!std::isfinite(p.fov)||p.fov<10||p.fov>170)return false;
    auto const* v=p.view;auto const* q=p.projection;
    if(std::abs(v[3])>.001||std::abs(v[7])>.001||std::abs(v[11])>.001||std::abs(v[15]-1)>.001)return false;
    if(std::abs(q[15])>.001||std::abs(std::abs(q[11])-1)>.001||std::abs(q[0])<.01||std::abs(q[5])<.01)return false;
    double len=0;for(double n:p.look)len+=n*n;if(std::abs(len-1)>.02)return false;
    for(int r=0;r<3;++r){double n=0;for(int c=0;c<3;++c)n+=v[c*4+r]*p.look[c];if(std::abs(n-(r==2?-1.:0.))>.05)return false;}
    // Require an orthonormal camera basis, not a scaled model transform.
    for(int a=0;a<3;++a)for(int b=0;b<3;++b){double dot=0;for(int r=0;r<3;++r)dot+=v[a*4+r]*v[b*4+r];if(std::abs(dot-(a==b?1.:0.))>.02)return false;}
    return true;
}
}
