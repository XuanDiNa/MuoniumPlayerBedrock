#include "EnvironmentBridge.h"
#include <algorithm>
#include <cmath>
#include <cstring>
namespace mp {
int EnvironmentTimeOfDay(int time) noexcept {int day=time%24000;return day<0?day+24000:day;}
bool CopyEnvironmentKey(char (&out)[64],char const* text,std::size_t size) noexcept {
    out[0]=0;if(!text||!size||size>=64)return false;
    for(std::size_t i=0;i<size;++i){char c=text[i];if(!((c>='a'&&c<='z')||(c>='0'&&c<='9')||c=='_'||c==':'||c=='.'||c=='-'))return false;}
    std::memcpy(out,text,size);out[size]=0;return true;
}
void EnvironmentScan::clear() noexcept{packet_=EnvironmentPacket{};cursor_=0;started_=false;startedAt_=0;}
void EnvironmentScan::start(WorldPacket const& p,std::uint64_t epoch,std::uint64_t now) noexcept {
    clear();packet_.epoch=epoch;packet_.dimension=p.dimension;
    packet_.bx=static_cast<int>(std::floor(p.eye[0]))-16;
    packet_.by=static_cast<int>(std::floor(p.eye[1]))-16;
    packet_.bz=static_cast<int>(std::floor(p.eye[2]))-16;
    std::copy_n(p.eye,3,packet_.eye);std::copy_n(p.look,3,packet_.look);
    packet_.cells.fill(255);started_=true;startedAt_=now;
}
bool EnvironmentScan::restart(WorldPacket const& p,std::uint64_t epoch,std::uint64_t now) const noexcept {
    if(!started_||packet_.epoch!=epoch||packet_.dimension!=p.dimension||now<startedAt_||now-startedAt_>1500)return true;
    for(int i=0;i<3;i++)if(std::abs(packet_.eye[i]-p.eye[i])>8)return true;
    return false;
}
bool EnvironmentScan::step(CellReader read,void* context,Clock clock,void* timeContext,unsigned limit,std::uint64_t budget) {
    if(!started_||!read||!clock||!limit||!budget)return false;
    auto begin=clock(timeContext);
    for(unsigned n=0;n<limit&&cursor_<packet_.cells.size();++n) {
        // A slow shape/material call must not force another 31 native reads
        // into this game frame before observing the budget again.
        if(clock(timeContext)-begin>=budget)break;
        auto i=cursor_++;
        auto cell=read(context,packet_.bx+int(i%33),packet_.by+int(i/(33*33)),packet_.bz+int(i/33%33));
        packet_.cells[i]=cell.material;
        if(cell.material==0||cell.material==8||cell.material==9||cell.material==255)packet_.shapeIds[i]=1;
        else {
            unsigned id=0;
            for(;id<unsigned(packet_.shapeCount);++id)if(packet_.shapes[id]==cell.bounds)break;
            // Bounded dictionary. A rare overflow keeps the conservative cube.
            if(id==unsigned(packet_.shapeCount)){if(id<256){packet_.shapes[id]=cell.bounds;++packet_.shapeCount;}else id=0;}
            packet_.shapeIds[i]=static_cast<std::uint8_t>(id);
        }
    }
    return complete();
}
}
