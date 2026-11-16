#include <Windows.h>
#include <filesystem>
#include <iostream>
#include <stdexcept>
#include "WorkspaceHandoff.h"
namespace {
void Need(bool v,char const* s){if(!v)throw std::runtime_error(s);}
template<class F>F Sym(HMODULE h,char const* n){auto p=GetProcAddress(h,n);Need(p!=nullptr,n);return reinterpret_cast<F>(p);}
void Pump(unsigned ms){auto end=GetTickCount64()+ms;do{MSG m{};while(PeekMessageW(&m,nullptr,0,0,PM_REMOVE)){TranslateMessage(&m);DispatchMessageW(&m);}Sleep(5);}while(GetTickCount64()<end);}
}
int wmain(int argc,wchar_t** argv){try{
    Need(argc>=3&&argc<=5,"mods folder and source required");auto mods=std::filesystem::path(argv[1]),root=std::filesystem::path(argv[2]);
    auto folder=mods/"MuoniumPlayerBedrock";for(auto n:{"manifest.json","modmenu.settings.json","modmenu.json"}){
        if(std::filesystem::exists(root/n))std::filesystem::copy_file(root/n,folder/n,std::filesystem::copy_options::overwrite_existing);
    }
    std::filesystem::create_directories(folder/"interface");std::filesystem::copy_file(root/"modmenu.settings.json",folder/"interface/modmenu.settings.json",std::filesystem::copy_options::overwrite_existing);
    auto owner=LoadLibraryW((folder/"MuoniumPlayerBedrock.dll").c_str());Need(owner!=nullptr,"provider DLL");
    auto start=Sym<void(*)(HWND,wchar_t const*)>(owner,"OwnerStart");auto stop=Sym<void(*)()>(owner,"OwnerStop");
    bool late=argc==5;
    if(late){start(nullptr,folder.c_str());Pump(150);}
    auto menu=LoadLibraryW((mods/"ModMenuBE/ModMenuBE.dll").c_str());Need(menu!=nullptr,"local4 fixture");
    int mode=argc>=4?std::stoi(argv[3]):0;auto setMode=Sym<void(*)(int)>(menu,"WorkspaceMode");setMode(mode);
    if(!late)start(nullptr,folder.c_str());Pump(250);
    if(mode){Need(!Sym<uint64_t(*)()>(menu,"WorkspaceSession")(),"unready/invalid workspace API must not register or fall back to local3");setMode(0);}
    Pump(350);
    Need(!Sym<bool(*)()>(menu,"FixtureRegistered")(),"local4 Configure must remain ordinary settings, not external tools");
    Need(std::filesystem::exists(folder/"modmenu.settings.json"),"local4 must keep the root ordinary settings descriptor");
    Need(Sym<uint64_t(*)()>(menu,"WorkspaceSession")()!=0,"local4 provider must register through the real identity-checked API");
    auto state=Sym<mp::WorkspaceRequest(*)()>(owner,"OwnerState");auto phase=Sym<bool(*)(unsigned,unsigned)>(owner,"OwnerPhase");
    Need(state().request==0,"discovery cannot request Java or network work");
    auto begin=Sym<uint64_t(*)(char const*)>(menu,"WorkspaceBegin");auto menuPhase=Sym<unsigned(*)()>(menu,"WorkspacePhase");
    auto finish=Sym<void(*)()>(menu,"WorkspaceFinish");
    Need(!begin("unknown"),"undeclared target refused");
    for(auto target:{"player","hud","presets","mini","island"}){
        auto request=begin(target);Need(request>(1ull<<63),"exact unsigned external request");Pump(150);
        auto r=state();Need(r.request==request&&r.phase==mp::WorkspacePhase::Requested,"explicit target delivery retains exact uint64 request");
        Need(menuPhase()==3,"delivery is not UI creation acknowledgement");Pump(100);Need(state().request==request,"polling cannot replay");
        Need(phase(1,2),"actual UI created");Pump(150);Need(state().phase==mp::WorkspacePhase::Accepted&&menuPhase()==4,"only actual UI creation activates handoff");
        Pump(150);Need(menuPhase()==4,"focus/page inactivity cannot implicitly return");
        Need(phase(4,5),"explicit Return button");Pump(150);Need(menuPhase()==5,"Return restores original workspace");finish();
    }
    auto failed=begin("player");Need(failed!=0,"failed target request");Pump(150);Need(phase(1,3),"target failed to create");Pump(150);Need(menuPhase()==6&&state().phase==mp::WorkspacePhase::Cancelled,"failed UI creation never acknowledges success");finish();
    auto cancelled=begin("player");Need(cancelled!=0,"cancelled scene request");Pump(150);Sym<void(*)()>(menu,"WorkspaceCancel")();
    Need(phase(1,2),"late created report");Pump(150);Need(state().phase==mp::WorkspacePhase::Cancelled,"cancelled menu rejects late creation");finish();
    Need(begin("player")!=0,"deadline request");Pump(150);Sym<void(*)()>(owner,"OwnerExpire")();Pump(150);
    Need(state().phase==mp::WorkspacePhase::Cancelled&&menuPhase()==6,"deadline cancels undelivered UI instead of replaying");finish();
    Sym<void(*)(bool)>(owner,"OwnerEnabled")(false);Pump(150);Need(begin("player")!=0,"disabled explicit menu request");Pump(150);Need(menuPhase()==6,"disabled provider refuses UI execution");finish();
    stop();Need(!Sym<uint64_t(*)()>(menu,"WorkspaceSession")(),"workspace shutdown unregisters");
    std::cout<<"local4 ordinary Configure and identity-checked workspace passed\n";return 0;
}catch(std::exception const&e){std::cerr<<e.what()<<'\n';return 1;}}
