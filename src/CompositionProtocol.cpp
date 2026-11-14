#include "Composition.h"
#include <cmath>
#include <algorithm>
namespace mp {
bool ValidComposition(CompositionHeader const& h,std::uint64_t now) noexcept {
    if(h.magic!=0x5043504d||(h.version!=1&&h.version!=2)||!h.sequence||!h.at||h.at>now||now-h.at>750
        ||h.active>1||h.occlusion>1||h.reserved>CompositionProbeOnly||h.planeCount>CompositionPlanes||h.atlasHeight>CompositionAtlas
        ||!h.viewportWidth||!h.viewportHeight||h.viewportWidth>8192||h.viewportHeight>8192
        ||std::uint64_t(h.viewportWidth)*h.viewportHeight>16777216
        ||h.width>h.viewportWidth||h.height>h.viewportHeight||h.x>h.viewportWidth-h.width||h.y>h.viewportHeight-h.height
        ||((h.width==0)!=(h.height==0))||h.planeCount&&(!h.epoch||!h.atlasHeight))return false;
    if(h.version==2){
        // V2 is a packed screen raster, not a world-plane/atlas protocol.
        if(h.x||h.y||h.planeCount||h.atlasHeight||h.bandCount>CompositionBands)return false;
        unsigned packedHeight=0,maxWidth=0;
        for(unsigned i=0;i<h.bandCount;i++){
            auto const& b=h.bands[i];
            if(!b.width||!b.height||b.width>h.viewportWidth||b.height>h.viewportHeight
                ||b.x>h.viewportWidth-b.width||b.y>h.viewportHeight-b.height
                ||b.offsetY!=packedHeight||b.height>h.height-packedHeight)return false;
            // Bands are screen-disjoint regardless of their wire order.
            for(unsigned j=0;j<i;j++){auto const& a=h.bands[j];if(b.y<a.y+a.height&&a.y<b.y+b.height)return false;}
            packedHeight+=b.height;maxWidth=(std::max)(maxWidth,b.width);
        }
        if(packedHeight!=h.height||maxWidth!=h.width)return false;
    }
    for(unsigned i=0;i<h.planeCount;++i){auto const& p=h.planes[i];
        for(auto x:p.position)if(!std::isfinite(x)||std::abs(x)>32000000)return false;
        float rr{},dd{};for(int k=0;k<3;++k){if(!std::isfinite(p.right[k])||!std::isfinite(p.down[k]))return false;rr+=p.right[k]*p.right[k];dd+=p.down[k]*p.down[k];}
        if(rr<1e-12||dd<1e-12||rr>1000000||dd>1000000)return false;
        for(auto x:p.bounds)if(!std::isfinite(x)||std::abs(x)>32768)return false;
        if(p.bounds[2]<=0||p.bounds[3]<=0||!p.tile[2]||!p.tile[3]||p.tile[2]>CompositionAtlas||p.tile[3]>h.atlasHeight
            ||p.tile[0]>CompositionAtlas-p.tile[2]||p.tile[1]>h.atlasHeight-p.tile[3])return false;
    }return true;
}
}
