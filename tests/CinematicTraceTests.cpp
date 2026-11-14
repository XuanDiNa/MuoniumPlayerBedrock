#include "CinematicTrace.h"
#include "Cinematic.h"
#include <array>
#include <cstring>
#include <stdexcept>
#include <iostream>
#include <limits>

void need(bool value,char const* why){if(!value)throw std::runtime_error(why);}
mp::CinematicTraceSample sample(){
    mp::CinematicTraceSample s;s.at=1000;s.epoch=4;s.input=8;s.commandAt=990;s.command=1;
    s.eligible=s.valid=s.applied=true;s.baseFov=s.renderFov=70;s.aspect=16.f/9;
    return s;
}
int main(){try {
    using namespace mp;
    CinematicTrace trace;auto s=sample();trace.observe(s);
    // Observe real scoped patch output, not a diagnostic-only FOV calculation.
    std::array<std::byte,288> camera{};float radians=70*3.14159265358979323846f/180;
    std::memcpy(camera.data()+80,&radians,4);auto original=camera;
    CinematicCommand command;command.fovOffset=6;CinematicPatch patch;
    need(patch.apply(camera.data(),command),"fixture patch applies");
    std::memcpy(&radians,camera.data()+80,4);
    s.at++;s.command++;s.offset=6;s.renderFov=radians*180/3.14159265358979323846f;trace.observe(s);
    need(patch.restore(camera.data())&&camera==original,"diagnostic keeps scoped restoration");
    auto w=trace.take();
    need(w.samples==2&&w.applied==2&&w.events==1,"short pulse retained between five-second logs");
    need((w.event[0].reasons&CinematicTrace::OffsetStep)!=0,"commanded FOV pulse identified");
    need(w.maxBaseDelta==0&&w.maxOffsetDelta==6&&w.maxOutputDelta>5.99,"base and offset distinguished");
    need((w.event[0].reasons&CinematicTrace::OutputMismatch)==0,"actual patched FOV matches base plus offset");
    s.at++;s.baseFov=76;s.renderFov=82;trace.observe(s);w=trace.take();
    need(w.events==1&&(w.event[0].reasons&CinematicTrace::BaseStep),"same command native FOV change identified");
    need(w.maxOffsetDelta==0&&w.maxBaseDelta==6,"no false host pulse for native base change");
    s.at++;s.aspect=1.9f;trace.observe(s);w=trace.take();
    need(w.events==1&&(w.event[0].reasons&CinematicTrace::AspectStep),"aspect zoom distinguished from vertical FOV");
    // Different native phases are independent; alternating output is not a jump.
    auto saved=sample();saved.phase=CinematicTracePhase::Saved;trace.observe(saved);
    need(trace.take().events==0,"first saved projection has no prior phase comparison");
    saved.at++;saved.renderFov=73;trace.observe(saved);w=trace.take();
    need(w.events==1&&(w.event[0].reasons&CinematicTrace::OutputMismatch),"consumed scalar mismatch recorded");
    s.at++;s.command++;s.position[0]=1;s.yaw=30;trace.observe(s);w=trace.take();
    need(w.events==1&&(w.event[0].reasons&CinematicTrace::PositionStep)&&(w.event[0].reasons&CinematicTrace::RotationStep),"dolly or shot discontinuity distinguished");
    s.at++;s.applied=false;s.valid=false;trace.observe(s);w=trace.take();
    need(w.losses==1&&(w.event[0].reasons&CinematicTrace::ApplicationLost),"same-session camera gap retained");
    s.at++;s.applied=s.valid=true;trace.observe(s);w=trace.take();
    need(w.resumes==1,"same-session resumption counted separately");
    s.at++;s.input++;s.applied=false;trace.observe(s);w=trace.take();
    need(w.losses==0&&w.resets==1,"input revocation is not an unexplained gap");
    s.at++;s.epoch++;s.applied=true;s.baseFov=s.renderFov=95;s.offset=0;trace.observe(s);w=trace.take();
    need(w.events==0&&w.resets==1,"world replacement never compared across sessions");
    s.at+=251;s.baseFov=s.renderFov=60;trace.observe(s);w=trace.take();
    need(w.events==0&&w.resets==1,"long gap starts a new comparison");
    s.at++;s.renderFov=std::numeric_limits<float>::quiet_NaN();trace.observe(s);w=trace.take();
    need(w.invalid==1&&w.events==0,"invalid diagnostics never affect camera or maxima");
    CinematicTrace bounded;s=sample();bounded.observe(s);
    for(unsigned i=0;i<100;i++){s.at++;s.baseFov=s.renderFov=(i%2)?70.f:76.f;bounded.observe(s);}
    w=bounded.take();need(w.events==8&&w.droppedEvents==92,"event log has a hard per-window bound");
    for(unsigned i=1;i<CinematicTrace::MaxWindows;i++)bounded.take();
    bounded.observe(s);need(bounded.take().samples==0,"detailed capture stops after bounded windows");
    std::cout<<"CINEMATIC_TRACE real-patch pulse/base/aspect/pose/gap separation, phase/session isolation, restoration and bounded capture passed\n";
}catch(std::exception const& error){std::cerr<<error.what()<<'\n';return 1;}}
