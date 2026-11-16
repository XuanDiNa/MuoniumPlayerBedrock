#include "Jobs.h"
#include "Runtime.h"
#include <filesystem>
#include <iostream>
#include <stdexcept>
#include <thread>
#include <fstream>
#include <vector>
namespace {
unsigned opens{};
std::vector<unsigned> actions;
void Need(bool value,char const* why){if(!value)throw std::runtime_error(why);}
LRESULT CALLBACK Proc(HWND h,UINT m,WPARAM w,LPARAM l){if(m==mp::OpenPlayerMessage){++opens;actions.push_back(unsigned(w));return 0;}return DefWindowProcW(h,m,w,l);}
void Pump(unsigned ms){auto end=GetTickCount64()+ms;do{MSG m{};while(PeekMessageW(&m,nullptr,0,0,PM_REMOVE)){TranslateMessage(&m);DispatchMessageW(&m);}Sleep(5);}while(GetTickCount64()<end);}
template<class F>F Sym(HMODULE h,char const* name){auto p=GetProcAddress(h,name);Need(p!=nullptr,name);return reinterpret_cast<F>(p);}
}
int wmain(int argc,wchar_t**argv){try{
    Need(argc>=3&&argc<=5,"fixture DLL and rejection mode required");int mode=std::stoi(argv[2]);
    HMODULE loaded=argc==4?LoadLibraryW(argv[1]):nullptr;
    if(loaded)Sym<void(*)(int)>(loaded,"FixtureMode")(mode);
    WNDCLASSW wc{};wc.lpfnWndProc=Proc;wc.lpszClassName=L"Muonium.MenuBETest";wc.hInstance=GetModuleHandleW(nullptr);RegisterClassW(&wc);
    HWND window=CreateWindowW(wc.lpszClassName,L"",0,0,0,0,0,HWND_MESSAGE,nullptr,wc.hInstance,nullptr);Need(window!=nullptr,"message window");
    auto root=std::filesystem::temp_directory_path()/(L"MuoniumMenuBE-"+std::to_wstring(GetCurrentProcessId()));std::filesystem::create_directories(root);
    auto actionFolder=root/"MuoniumPlayerBedrock";
    if(argc==5){std::filesystem::create_directory(actionFolder);for(auto n:{"manifest.json","modmenu.settings.json"})std::filesystem::copy_file(std::filesystem::path(argv[4])/n,actionFolder/n);std::filesystem::create_directory(actionFolder/"interface");std::filesystem::copy_file(std::filesystem::path(argv[4])/"modmenu.settings.json",actionFolder/"interface/modmenu.settings.json");Need(mp::EnsurePlayerConfig(actionFolder),"fixture config");}
    {
      mp::Jobs jobs(window,root);Pump(250);Need(opens==0,"discovery must remain passive");
      HMODULE menu=loaded?loaded:LoadLibraryW(argv[1]);Need(menu!=nullptr,"load API fixture after provider");
      auto setMode=Sym<void(*)(int)>(menu,"FixtureMode");auto registered=Sym<bool(*)()>(menu,"FixtureRegistered");
      auto invoke=Sym<bool(*)()>(menu,"FixtureInvoke");setMode(mode);Pump(500);
      if(mode>0&&mode!=8){Need(!registered(),"invalid API or rejected registration must not bind");setMode(0);Pump(500);}
      Need(registered(),"valid versioned ModMenuBE API must register external screen");
      Need(opens==0,"registering or inspecting the entry must not open or start work");
      Need(invoke(),"explicit Configure");Need(opens==0,"render callback must only enqueue, never synchronously open");Pump(300);Need(opens==1&&actions.back()==12,"Configure must open the owned button page directly");
      Pump(300);Need(opens==1,"passive polling cannot replay Configure");
      jobs.Enabled(false);Pump(250);Need(invoke(),"disabled callback exists");Pump(250);Need(opens==1,"disabled plugin cannot open");
      jobs.Enabled(true);Pump(250);Need(invoke(),"reenabled callback");Pump(250);Need(opens==2,"reenabled explicit open");
    }
    unsigned expected=2;
    if(argc==5){
        auto original=[](auto const& path){std::ifstream f(path,std::ios::binary);return std::string(std::istreambuf_iterator<char>(f),{});};
        auto before=original(actionFolder/"MuoniumPlayerBedrock.json");
        auto rescans=Sym<unsigned(*)()>(GetModuleHandleW(L"ModMenuBE.dll"),"FixtureRescans")();
        mp::Jobs jobs(window,actionFolder);Pump(350);Need(opens==2,"local3 entry discovery passive");
        Need(!std::filesystem::exists(actionFolder/"modmenu.settings.json"),"Known legacy root sidecar must no longer intercept Configure");
        Need(original(actionFolder/"MuoniumPlayerBedrock.json")==before,"Entry migration must not change user settings");
        Need(Sym<unsigned(*)()>(GetModuleHandleW(L"ModMenuBE.dll"),"FixtureRescans")()==rescans+1,"Migration must request a public API rescan");
        Need(Sym<bool(*)()>(GetModuleHandleW(L"ModMenuBE.dll"),"FixtureInvoke")(),"Existing Configure opens owned buttons");
        Pump(350);Need(opens==3&&actions.back()==12,"Configure reaches direct button page without Apply");
        Pump(350);Need(opens==3,"Page cannot reopen on its own");
        jobs.OpenSettings();Pump(650);
        Need(std::filesystem::exists(actionFolder/"modmenu.settings.json"),"Ordinary settings restores the original local3 descriptor");
        Need(original(actionFolder/"MuoniumPlayerBedrock.json")==before,"Opening settings cannot change config");
        Sym<void(*)(bool)>(GetModuleHandleW(L"ModMenuBE.dll"),"FixtureMenuOpen")(false);Pump(350);
        Need(!std::filesystem::exists(actionFolder/"modmenu.settings.json"),"Closing settings returns Configure to direct buttons");
        Need(Sym<bool(*)()>(GetModuleHandleW(L"ModMenuBE.dll"),"FixtureInvoke")(),"Configure after ordinary settings");
        Pump(350);Need(opens==4&&actions.back()==12,"Direct buttons remain usable after settings round trip");expected=4;
    }
    auto menu=GetModuleHandleW(L"ModMenuBE.dll");Need(menu!=nullptr,"fixture remains loaded");
    Need(!Sym<bool(*)()>(menu,"FixtureRegistered")(),"shutdown must unregister");
    Sym<void(*)()>(menu,"FixtureInvokeRetired")();Pump(250);Need(opens==expected,"retained menu snapshot callback must be inert after provider stop");
    DestroyWindow(window);FreeLibrary(menu);
    std::cout<<"ModMenuBE mode="<<mode<<": delayed load, ABI rejection/retry, passive entry, async open, disable/recovery and retired callback passed\n";return 0;
}catch(std::exception const&e){std::cerr<<e.what()<<'\n';return 1;}}
