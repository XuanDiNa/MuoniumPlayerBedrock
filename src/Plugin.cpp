#include "Runtime.h"
#include "JvmHost.h"
#include "Version.h"
#include "LoaderPolicy.h"
#include "HostEvidence.h"
#include <atomic>
#include <mutex>
namespace {
std::atomic<int> status{};std::atomic<bool> stopRequested{};
mp::StartGate startGate;HMODULE plugin{};HANDLE worker{};
#ifdef MPB_LEVILAMINA
std::mutex lifecycle;
constexpr auto loader=mp::LoaderKind::LeviLamina;
#else
constexpr auto loader=mp::LoaderKind::Preload;
#endif
bool CheckHost(std::filesystem::path const& exe,std::filesystem::path const& folder){
    mp::HostVerification verification;
    bool verified=mp::VerifyHost(exe,&verification);auto identity=mp::VerifiedHostIdentity.load();
    mp::Log(folder,mp::FormatHostVerification(verification));
    if(!verified&&verification.peValid&&!verification.sha256.empty()){
        auto evidence=mp::SaveHostEvidence(exe,folder,verification.sha256);
        mp::Log(folder,"HOST_EVIDENCE revision=1 status="+evidence.reason+
            " file="+(evidence.saved?evidence.filename:"none")+
            " nativeEquivalent="+(evidence.nativeEquivalent?"yes":"no")+" nativeAuthorized=no");
    }
    bool leviLoaded=GetModuleHandleW(L"LeviLamina.dll")!=nullptr;
    mp::Log(folder,std::string("HOST CHECK identity=")+(verified?"matched":"unrecognized")+
        " profile="+(identity?std::string(identity->name):"none")+" LeviLamina="+(leviLoaded?"loaded":"absent"));
    return verified&&mp::AdmitLoader(identity,loader,leviLoaded);
}
DWORD WINAPI Start(void*){
    struct Finish{~Finish(){startGate.finish();}} finish;
    wchar_t dll[32768]{},exe[32768]{};
    if(!GetModuleFileNameW(plugin,dll,32768)||!GetModuleFileNameW(nullptr,exe,32768))return 0;auto folder=std::filesystem::path(dll).parent_path();
    try{
        mp::Log(folder,std::string("START version=")+mp::PluginVersion+" loader="+(loader==mp::LoaderKind::LeviLamina?"LeviLamina":"preload")+" keys-default=NONE");
        if(!CheckHost(exe,folder)){status=-1;mp::Log(folder,"REFUSED unsupported host image or wrong loader; no player/audio/network started");return 0;}
        mp::Log(folder,std::string("HOST VERIFIED profile=")+std::string(mp::VerifiedHostIdentity.load()->name));
        HWND owner{};for(int i=0;i<600&&!owner&&!stopRequested;++i){owner=mp::FindOwnerWindow(GetCurrentProcessId());if(!owner)Sleep(200);}
        if(stopRequested){status=2;return 0;}
        if(!owner){status=-2;mp::Log(folder,"REFUSED game window not available");return 0;}
        HMODULE pin;if(!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS|GET_MODULE_HANDLE_EX_FLAG_PIN,reinterpret_cast<LPCWSTR>(&Start),&pin))return 0;
        status=1;mp::RunJvmPlayer(plugin,owner,folder,false,&stopRequested);status=2;
    }catch(...){status=-3;mp::Log(folder,"STOP unexpected runtime exception");}return 0;
}
#ifdef MPB_LEVILAMINA
bool Enable(){
    std::lock_guard lock(lifecycle);
    if(!startGate.begin())return true;
    if(worker){CloseHandle(worker);worker=nullptr;}
    stopRequested=false;worker=CreateThread(nullptr,0,Start,nullptr,0,nullptr);
    if(!worker){startGate.finish();return false;}return true;
}
bool Disable(){
    std::lock_guard lock(lifecycle);stopRequested=true;
    return !worker||WaitForSingleObject(worker,6000)==WAIT_OBJECT_0;
}
#endif
}
extern "C" __declspec(dllexport) int MPB_GetStatus(){return status.load();}
extern "C" __declspec(dllexport) char const* MPB_GetVersion(){return mp::PluginVersion;}
#ifdef MPB_LEVILAMINA
namespace ll::mod {class Mod;}
extern "C" __declspec(dllexport) bool ll_mod_load(ll::mod::Mod&){
    wchar_t exe[32768]{},dll[32768]{};
    GetModuleFileNameW(plugin,dll,32768);auto folder=std::filesystem::path(dll).parent_path();
    mp::Log(folder,std::string("LOAD version=")+mp::PluginVersion+" loader=LeviLamina");
    bool admitted=GetModuleFileNameW(nullptr,exe,32768)&&CheckHost(exe,folder);
    mp::Log(folder,admitted?std::string("LOAD ADMITTED profile=")+std::string(mp::VerifiedHostIdentity.load()->name):
        "REFUSED native load: unsupported host image or wrong loader; no player/audio/network started");
    return admitted;
}
extern "C" __declspec(dllexport) bool ll_mod_enable(ll::mod::Mod&){return Enable();}
extern "C" __declspec(dllexport) bool ll_mod_disable(ll::mod::Mod&){return Disable();}
// No unload callback: dormant native forwarders remain pinned until process exit.
#endif
BOOL APIENTRY DllMain(HMODULE module,DWORD reason,LPVOID){
    if(reason==DLL_PROCESS_ATTACH){plugin=module;
#ifndef MPB_LEVILAMINA
        // DllMain only dispatches the preload worker; native work runs later.
        if(startGate.begin()){worker=CreateThread(nullptr,0,Start,nullptr,0,nullptr);if(worker)CloseHandle(worker);else startGate.finish();worker=nullptr;}
#endif
    }return TRUE;
}
