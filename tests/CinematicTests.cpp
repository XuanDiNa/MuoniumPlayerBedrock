#include "Cinematic.h"
#include <array>
#include <cmath>
#include <cstring>
#include <iostream>
#include <limits>
#include <stdexcept>
void need(bool v,char const* why){if(!v){std::cerr<<why<<'\n';throw std::runtime_error(why);}}
int main(){
    mp::CinematicState s;s.sequence=1;s.at=1000;s.epoch=4;s.input=98;s.eligible=1;s.health=1;
    s.eye[0]=12;s.eye[1]=65;s.eye[2]=-4;s.foot[0]=12;s.foot[1]=63.4;s.foot[2]=-4;s.fov=70;
    mp::CinematicCommand c;c.sequence=1;c.at=1000;c.epoch=4;c.input=98;c.active=1;
    auto motion=s;motion.foot[0]+=.02;need(mp::CinematicInputChanged(s,motion),"controller or knockback cancels camera");
    motion=s;motion.yaw+=.1f;need(mp::CinematicInputChanged(s,motion),"controller look cancels camera");
    motion=s;motion.pitch+=.1f;need(mp::CinematicInputChanged(s,motion),"pitch input cancels camera");
    motion=s;motion.foot[0]+=.002;need(mp::CinematicInputChanged(s,motion),"small high-FPS controller motion cancels camera");
    motion=s;motion.yaw+=.001f;need(mp::CinematicInputChanged(s,motion),"small high-FPS controller look cancels camera");
    motion=s;motion.health=.9f;need(mp::CinematicInputChanged(s,motion),"damage cancels camera");
    motion=s;motion.eye[0]+=2;need(!mp::CinematicInputChanged(s,motion),"render camera is not actor input");
    c.position[0]=16;c.position[1]=67;c.position[2]=-6;c.yaw=90;c.pitch=30;c.fovOffset=8;
    need(mp::ValidCinematicCommand(c,s,1020),"fresh owned command");
    auto hide=c;hide.flags=1;need(mp::ValidCinematicCommand(hide,s,1020),"scoped HUD hide flag");
    hide.flags=2;need(!mp::ValidCinematicCommand(hide,s,1020),"unknown flags rejected");
    auto bad=c;bad.epoch++;need(!mp::ValidCinematicCommand(bad,s,1020),"world replacement");
    bad=c;bad.input++;need(!mp::ValidCinematicCommand(bad,s,1020),"input cancellation");
    need(!mp::ValidCinematicCommand(c,s,1201),"host heartbeat timeout");
    need(!mp::ValidCinematicCommand(c,s,999),"future command");
    bad=c;bad.position[0]=s.foot[0]+100;need(!mp::ValidCinematicCommand(bad,s,1020),"bounded camera displacement");
    bad=c;bad.position[1]=std::numeric_limits<double>::quiet_NaN();need(!mp::ValidCinematicCommand(bad,s,1020),"nonfinite pose");
    bad=c;bad.fovOffset=21;need(!mp::ValidCinematicCommand(bad,s,1020),"bounded FOV offset");
    bad=c;bad.active=0;need(!mp::ValidCinematicCommand(bad,s,1020),"inactive command");
    auto invalid=s;invalid.eligible=0;need(!mp::ValidCinematicCommand(c,invalid,1020),"UI focus death gating");
    invalid=s;invalid.at=600;need(!mp::ValidCinematicCommand(c,invalid,1020),"stale scene");
    std::array<std::byte,288> camera{};for(unsigned i=0;i<camera.size();i++)camera[i]=std::byte((i*17)%251);
    float fov=70*3.14159265358979323846f/180;std::memcpy(camera.data()+80,&fov,4);
    auto before=camera;mp::CinematicPatch patch;
    need(patch.apply(camera.data(),c),"apply render-only scalar pose");
    for(unsigned i=0;i<48;i++)need(camera[i]==before[i],"never copy or own engine strings");
    for(unsigned i=156;i<288;i++)need(camera[i]==before[i],"leave engine saved matrices alone");
    float q[4],p[3],look[3];std::memcpy(q,camera.data()+48,16);std::memcpy(p,camera.data()+64,12);
    mp::CinematicForward(q,look);need(std::abs(look[0]+std::sqrt(.75f))<.0001f&&std::abs(look[1]+.5f)<.0001f&&std::abs(look[2])<.0001f,"Bedrock quaternion yaw/pitch basis");
    need(p[0]==16&&p[1]==67&&p[2]==-6,"position is render eye");
    need(!patch.apply(camera.data(),c),"no nested patch");
    need(patch.restore(camera.data()),"restore synchronously");need(camera==before,"bit-exact scalar restoration");
    need(!patch.restore(camera.data()),"restore only once");
    // UI/re-focus may update vanilla FOV between frames. The scoped adapter
    // must use that fresh base and never accumulate a previous cinematic zoom.
    for(unsigned cycle=0;cycle<1000;++cycle){
        float vanilla=(cycle%2?70.f:82.f)*3.14159265358979323846f/180;std::memcpy(camera.data()+80,&vanilla,4);
        auto snapshot=camera;need(patch.apply(camera.data(),c),"re-entry after UI FOV change");float actual{};std::memcpy(&actual,camera.data()+80,4);
        need(std::abs(actual-(vanilla+c.fovOffset*3.14159265358979323846f/180))<1e-6f,"FOV offset applied exactly once");
        need(patch.restore(camera.data())&&camera==snapshot,"UI re-entry restores fresh vanilla FOV exactly");
    }
    c.yaw=0;c.pitch=0;c.roll=90;need(patch.apply(camera.data(),c),"roll support");
    std::memcpy(q,camera.data()+48,16);mp::CinematicForward(q,look);
    need(std::abs(look[0])<.0001&&std::abs(look[1])<.0001&&std::abs(look[2]-1)<.0001,"roll preserves forward");
    float other=99;std::memcpy(camera.data()+64,&other,4);
    need(!patch.restore(camera.data()),"detect concurrent owner replacement");
    float after{};std::memcpy(&after,camera.data()+64,4);need(after==99,"do not overwrite another writer");
    std::cout<<"Cinematic command ownership, freshness, bounds, basis and synchronous scalar restoration passed\n";
}
