#include "Jobs.h"
#include <memory>
namespace {std::unique_ptr<mp::Jobs> jobs;}
extern "C" __declspec(dllexport) void OwnerStart(HWND h,wchar_t const* folder){jobs=std::make_unique<mp::Jobs>(h,folder);}
extern "C" __declspec(dllexport) void OwnerStop(){jobs.reset();}
extern "C" __declspec(dllexport) mp::WorkspaceRequest OwnerState(){return jobs->Workspace().Read();}
extern "C" __declspec(dllexport) bool OwnerPhase(unsigned from,unsigned to){auto r=jobs->Workspace().Read();return jobs->Workspace().Transition(r,mp::WorkspacePhase(from),mp::WorkspacePhase(to));}
extern "C" __declspec(dllexport) void OwnerEnabled(bool value){jobs->Enabled(value);}
extern "C" __declspec(dllexport) void OwnerExpire(){auto r=jobs->Workspace().Read();r.deadline=GetTickCount64()-1;jobs->Workspace().Offer(r);}
