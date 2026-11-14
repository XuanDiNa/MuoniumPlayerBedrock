#include "Composition.h"
#include "CompositionCamera.h"
#include <iostream>
#include <stdexcept>
#include <limits>
static unsigned checks;
static void Check(bool v,char const* message){++checks;if(!v)throw std::runtime_error(message);}
int main(){try{
    mp::CompositionHeader h;h.sequence=1;h.at=1000;h.active=1;h.viewportWidth=1920;h.viewportHeight=1080;
    h.x=10;h.y=20;h.width=100;h.height=40;
    Check(mp::ValidComposition(h,1001),"bounded flat crop accepted");
    h.reserved=mp::CompositionProbeOnly;Check(mp::ValidComposition(h,1001),"probe transport validated without drawing");
    h.reserved=2;Check(!mp::ValidComposition(h,1001),"unknown output ownership refused");h.reserved=0;
    Check(!mp::ValidComposition(h,1801),"dead producer expires");
    h.width=1920;Check(!mp::ValidComposition(h,1001),"crop cannot wrap");h.width=100;
    h.planeCount=1;h.epoch=7;h.atlasHeight=64;auto& p=h.planes[0];p.position[0]=10;p.right[0]=p.down[1]=.1f;
    p.bounds[2]=100;p.bounds[3]=40;p.tile[2]=100;p.tile[3]=40;
    Check(mp::ValidComposition(h,1001),"camera independent world plane accepted");
    p.position[0]=std::numeric_limits<double>::quiet_NaN();Check(!mp::ValidComposition(h,1001),"nonfinite world anchor refused");p.position[0]=10;
    p.tile[0]=2040;Check(!mp::ValidComposition(h,1001),"atlas overrun refused");p.tile[0]=0;
    h.planeCount=33;Check(!mp::ValidComposition(h,1001),"plane count bounded");h.planeCount=1;
    h.version=99;Check(!mp::ValidComposition(h,1001),"foreign transport version refused");
    mp::CompositionHeader sparse;sparse.version=2;sparse.sequence=1;sparse.at=1000;sparse.active=1;
    sparse.viewportWidth=400;sparse.viewportHeight=300;sparse.width=100;sparse.height=30;sparse.bandCount=2;
    sparse.bands[0]={3,270,60,10,0};sparse.bands[1]={7,5,100,20,10};
    Check(mp::ValidComposition(sparse,1001),"bounded disjoint v2 bands accepted in either vertical order");
    auto valid=sparse;
    sparse.bands[1].offsetY=9;Check(!mp::ValidComposition(sparse,1001),"packed row overlap refused");sparse=valid;
    sparse.bands[1].y=275;Check(!mp::ValidComposition(sparse,1001),"screen overlap refused");sparse=valid;
    sparse.bands[0].x=399;Check(!mp::ValidComposition(sparse,1001),"offscreen band refused");sparse=valid;
    sparse.height++;Check(!mp::ValidComposition(sparse,1001),"unaccounted packed rows refused");sparse=valid;
    sparse.width++;Check(!mp::ValidComposition(sparse,1001),"unaccounted packed columns refused");sparse=valid;
    sparse.bandCount=5;Check(!mp::ValidComposition(sparse,1001),"band array overflow refused");sparse=valid;
    sparse.bands[0].height=0;Check(!mp::ValidComposition(sparse,1001),"zero band refused");sparse=valid;
    sparse.bands[0].offsetY=0xffffffff;Check(!mp::ValidComposition(sparse,1001),"overflow offset refused");sparse=valid;
    sparse.bands[0].height=0xffffffff;Check(!mp::ValidComposition(sparse,1001),"overflow height refused");sparse=valid;
    sparse.planeCount=1;Check(!mp::ValidComposition(sparse,1001),"v2 world planes refused");sparse=valid;
    sparse.x=1;Check(!mp::ValidComposition(sparse,1001),"v2 union coordinates refused");
    sparse=valid;sparse.bandCount=sparse.width=sparse.height=0;Check(mp::ValidComposition(sparse,1001),"empty packed frame accepted");
    mp::WorldPacket camera;camera.valid=1;camera.epoch=7;camera.projection[0]=camera.projection[5]=1;camera.projection[10]=-1.01f;camera.projection[11]=-1;camera.projection[14]=-.2f;
    float values[120]{};float paired[16]{};paired[0]=paired[5]=paired[10]=paired[15]=1;
    std::copy(camera.projection,camera.projection+16,values);std::copy(paired,paired+16,values+16);values[112]=.001f;
    mp::CompositionCameraValues owned;
    Check(mp::MatchCompositionCamera(values,camera,paired,owned),"current CPU-visible camera admitted");
    Check(owned.world.projection[8]==.001f,"actual native projection jitter retained");
    values[16]=-1;Check(!mp::MatchCompositionCamera(values,camera,paired,owned),"foreign basis refused");
    std::cout<<"Composition protocol "<<checks<<" checks passed\n";return 0;
}catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}}
