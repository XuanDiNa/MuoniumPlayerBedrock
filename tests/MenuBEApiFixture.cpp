#include "ModMenuApi.h"
#include "core/ConfigRegistry.h"
#define WIN32_LEAN_AND_MEAN
#include <Windows.h>
#include <atomic>
#include <string>
namespace {
std::atomic<int> mode{},attempts{},closes{},rescans{};
ModMenuActionFn retired{};void* retiredUser{};
int Register(char const* name,ModMenuActionFn fn,void* user){
    ++attempts;if(mode==6)return 0;
    retired=fn;retiredUser=user;
    return modmenu::ConfigRegistry::instance().setExternal(name,fn,user);
}
int Unregister(char const* name){return modmenu::ConfigRegistry::instance().unregister(name);}
int Close(){++closes;return 1;}
int Metadata(char const*,char const*){++rescans;return 1;}
std::atomic<bool> menuOpen{};
int OpenMenu(char const*,int){menuOpen=true;return 1;}
int IsOpen(){return menuOpen?1:0;}
ModMenuApi table{};
}
extern "C" __declspec(dllexport) ModMenuApi const* ModMenu_GetApi(uint32_t minimum){
    if(mode==1||minimum>MODMENU_API_VERSION)return nullptr;
    table={};table.version=mode==2?0:mode==7?99:mode==8?1:2;
    table.structSize=mode==3?8:mode==8?uint32_t(offsetof(ModMenuApi,addCommand)):sizeof table;
    table.setExternalScreen=mode==4?nullptr:Register;table.unregister=mode==5?nullptr:Unregister;table.closeMenu=Close;table.setMetadata=Metadata;table.openMenu=OpenMenu;table.isMenuOpen=IsOpen;
    if(mode==9)table.setExternalScreen=reinterpret_cast<decltype(table.setExternalScreen)>(GetProcAddress(GetModuleHandleW(L"kernel32.dll"),"GetTickCount"));
    return &table;
}
extern "C" __declspec(dllexport) void FixtureMode(int value){mode=value;}
extern "C" __declspec(dllexport) unsigned FixtureAttempts(){return attempts;}
extern "C" __declspec(dllexport) unsigned FixtureCloses(){return closes;}
extern "C" __declspec(dllexport) unsigned FixtureRescans(){return rescans;}
extern "C" __declspec(dllexport) void FixtureMenuOpen(bool value){menuOpen=value;}
extern "C" __declspec(dllexport) bool FixtureRegistered(){return modmenu::ConfigRegistry::instance().has("MuoniumPlayerBedrock");}
extern "C" __declspec(dllexport) bool FixtureInvoke(){
    auto screen=modmenu::ConfigRegistry::instance().get("MuoniumPlayerBedrock");
    if(!screen||!screen->external)return false;
    screen->external(screen->externalUser);return true;
}
extern "C" __declspec(dllexport) void FixtureInvokeRetired(){if(retired)retired(retiredUser);}
