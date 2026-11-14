// Test-only controls around the frozen, unmodified real WorkspaceApi/broker.
#include "ModMenu/Workspace.h"
#include <atomic>
namespace {std::atomic<int> ready{};}
extern "C" __declspec(dllexport) int MMB_GetStatus(){return ready.load();}
extern "C" __declspec(dllexport) void FixtureReady(int value){ready=value;}
extern "C" __declspec(dllexport) unsigned FixtureProviders(){return unsigned(menu::Workspaces().Snapshots().size());}
extern "C" __declspec(dllexport) unsigned FixturePending(){auto all=menu::Workspaces().Snapshots();return all.empty()?0:unsigned(all.front().pending.size());}
extern "C" __declspec(dllexport) bool FixtureSubmit(char const* action){
    auto all=menu::Workspaces().Snapshots();if(all.size()!=1||!all.front().state)return false;
    auto const& s=all.front();std::string error;
    return menu::Workspaces().Submit(s.session,"player",action,(*s.state)["revision"].get<std::uint64_t>(),menu::WorkspaceJson::object(),{},error);
}
