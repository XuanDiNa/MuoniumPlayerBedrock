#include "sdk/ModMenuWorkspace.h"
#include "workspace/Service.h"
#include <Windows.h>
#include <atomic>
#undef ModMenu_GetWorkspaceApi
extern "C" ModMenuWorkspaceApi const* Frozen_GetWorkspaceApi(uint32_t);
namespace {std::atomic<int> mode{};}
namespace modmenu::compat {bool nativeReady(){return mode!=1;}}
extern "C" __declspec(dllexport) ModMenuWorkspaceApi const* ModMenu_GetWorkspaceApi(uint32_t v){
    static ModMenuWorkspaceApi table;table=*Frozen_GetWorkspaceApi(v);
    if(mode==2)table.structSize=8;
    if(mode==3)table.version=99;
    if(mode==4)table.poll=reinterpret_cast<decltype(table.poll)>(GetProcAddress(GetModuleHandleW(L"kernel32.dll"),"GetTickCount"));
    return &table;
}
extern "C" __declspec(dllexport) void WorkspaceMode(int n){mode=n;}
extern "C" __declspec(dllexport) uint64_t WorkspaceSession(){auto s=modmenu::workspace::service().snapshot("MuoniumPlayerBedrock");return s?s->session:0;}
extern "C" __declspec(dllexport) uint64_t WorkspaceBegin(char const* target){
    using namespace modmenu::workspace;auto s=WorkspaceSession(),r=service().beginExternal(s,target,milliseconds());
    if(r)service().releaseExternal(s,r,milliseconds());return r;
}
extern "C" __declspec(dllexport) unsigned WorkspacePhase(){using namespace modmenu::workspace;return unsigned(service().handoff(milliseconds()).phase);}
extern "C" __declspec(dllexport) void WorkspaceFinish(){using namespace modmenu::workspace;auto h=service().handoff(milliseconds());service().finishExternal(h.request);}
extern "C" __declspec(dllexport) void WorkspaceCancel(){using namespace modmenu::workspace;auto h=service().handoff(milliseconds());service().cancelExternal(h.request);}
