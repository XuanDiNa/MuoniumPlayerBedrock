#include "Runtime.h"
#include "WorldBridge.h"
#include "EnvironmentBridge.h"
#include <stdexcept>
#include <cmath>
#include <iostream>
#include <limits>
#include <array>
#include <cstring>
void Need(bool value){if(!value)throw std::runtime_error("World bridge assertion failed");}
int main(){try{
    // Reproduce the exact ScreenContext -> Camera -> MatrixStack owned copy.
    std::array<std::byte,64> context{},stack{};
    float raw[16]{};raw[0]=raw[5]=raw[10]=raw[15]=1;raw[12]=.25f;
    std::uintptr_t pages[]{reinterpret_cast<std::uintptr_t>(raw),0};
    auto stackPointer=stack.data();std::memcpy(context.data()+24,&stackPointer,8);
    auto pagePointer=pages;std::memcpy(stack.data()+8,&pagePointer,8);
    std::size_t two=2,one=1;std::memcpy(stack.data()+16,&two,8);std::memcpy(stack.data()+32,&one,8);
    float owned[16]{};Need(mp::CopyRenderViewForDepth(context.data(),owned));Need(owned[12]==.25f);
    raw[12]=72;Need(owned[12]==.25f); // Original may update its camera after the copy.
    Need(!mp::CopyRenderViewForDepth(nullptr,owned));
    std::size_t invalid=3;std::memcpy(stack.data()+16,&invalid,8);Need(!mp::CopyRenderViewForDepth(context.data(),owned));
    mp::WorldPacket p;p.view[0]=-1;p.view[5]=1;p.view[10]=-1;p.view[15]=1;
    p.projection[0]=1;p.projection[5]=1;p.projection[11]=-1;p.look[2]=1;p.fov=70;
    Need(mp::ValidWorldCamera(p));
    auto bad=p;bad.look[2]=0;Need(!mp::ValidWorldCamera(bad));
    bad=p;bad.eye[1]=std::numeric_limits<double>::quiet_NaN();Need(!mp::ValidWorldCamera(bad));
    bad=p;bad.view[0]=2;Need(!mp::ValidWorldCamera(bad));
    bad=p;bad.projection[15]=1;Need(!mp::ValidWorldCamera(bad));
    auto prefix=L"Local\\MuoniumBedrock-0123456789abcdef0123456789abcdef";
    Need(mp::OpenWorldBridge(prefix,L".",true));
    auto frames=OpenEventW(SYNCHRONIZE,FALSE,(std::wstring(prefix)+L"-world-frame").c_str());Need(frames);
    Need(WaitForSingleObject(frames,0)==WAIT_OBJECT_0);
    Need(WaitForSingleObject(frames,0)==WAIT_TIMEOUT); // Auto-reset, never spin on a consumed frame.
    auto map=OpenFileMappingW(FILE_MAP_READ,FALSE,(std::wstring(prefix)+L"-world").c_str());Need(map);
    auto view=static_cast<mp::WorldPacket const*>(MapViewOfFile(map,FILE_MAP_READ,0,0,256));Need(view);
    Need(view->magic==0x4357504d&&view->version==2&&view->valid==0&&view->sequence>0);
    Need(view->metricsVersion==1&&view->fps==-1&&view->ping==-1&&view->connectionKind==0&&view->metricsAt==0);
    auto envMap=OpenFileMappingW(FILE_MAP_READ,FALSE,(std::wstring(prefix)+L"-environment").c_str());
    Need(envMap!=nullptr); // Environment bridge must exist even before world capture.
    auto envView=static_cast<unsigned char const*>(MapViewOfFile(envMap,FILE_MAP_READ,0,0,sizeof(mp::EnvironmentPacket)));Need(envView);
    Need(*reinterpret_cast<std::uint32_t const*>(envView)==0x4556504d);
    Need(reinterpret_cast<mp::EnvironmentPacket const*>(envView)->version==2);
    auto request=OpenEventW(EVENT_MODIFY_STATE|SYNCHRONIZE,FALSE,(std::wstring(prefix)+L"-environment-active").c_str());Need(request);
    Need(WaitForSingleObject(request,0)==WAIT_TIMEOUT);
    SetEvent(request);Need(WaitForSingleObject(request,0)==WAIT_OBJECT_0);ResetEvent(request);
    UnmapViewOfFile(envView);CloseHandle(envMap);CloseHandle(request);
    auto voiceMap=OpenFileMappingW(FILE_MAP_READ,FALSE,(std::wstring(prefix)+L"-voice-world").c_str());Need(voiceMap);
    auto voiceView=static_cast<unsigned char const*>(MapViewOfFile(voiceMap,FILE_MAP_READ,0,0,96));Need(voiceView);
    Need(*reinterpret_cast<std::uint32_t const*>(voiceView)==0x5657504d);
    Need(*reinterpret_cast<std::uint32_t const*>(voiceView+32)==0);
    UnmapViewOfFile(voiceView);CloseHandle(voiceMap);
    mp::EnableWorldBridge(true); // Offline fixtures must never hook their host.
    Need(view->valid==0);
    Need(!mp::OpenWorldBridge(prefix,L".",true));
    mp::CloseWorldBridge();Need(view->valid==0);
    Need(WaitForSingleObject(frames,0)==WAIT_OBJECT_0);CloseHandle(frames);
    UnmapViewOfFile(view);CloseHandle(map);
    Need(mp::OpenWorldBridge(prefix,L".",true));mp::CloseWorldBridge();
    std::cout<<"World protocol, validation, mapping, dormant offline hook and lifecycle passed\n";
}catch(std::exception const& e){std::cerr<<e.what()<<"\n";return 1;}
}

