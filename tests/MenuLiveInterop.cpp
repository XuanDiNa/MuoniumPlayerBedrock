#include "Jobs.h"
#include <iostream>
#include <stdexcept>
#include <fstream>
#include <functional>
void Need(bool v,char const* text){if(!v)throw std::runtime_error(text);}
template<class F>F Export(HMODULE dll,char const* name){auto fn=reinterpret_cast<F>(GetProcAddress(dll,name));Need(fn!=nullptr,name);return fn;}
bool Await(std::function<bool()> const& test){for(unsigned n=0;n<40;++n){if(test())return true;Sleep(50);}return test();}
unsigned messages{};WPARAM last{};
LRESULT CALLBACK Proc(HWND h,UINT msg,WPARAM w,LPARAM l){if(msg==mp::OpenPlayerMessage){++messages;last=w;return 0;}return DefWindowProcW(h,msg,w,l);}
void Pump(){MSG m;while(PeekMessageW(&m,nullptr,0,0,PM_REMOVE)){TranslateMessage(&m);DispatchMessageW(&m);}}
int wmain(int argc,wchar_t** argv){try{
    Need(argc==4,"provide provider fixture, menu fixture and duplicate menu");
    WNDCLASSW wc{};wc.hInstance=GetModuleHandleW(nullptr);wc.lpfnWndProc=Proc;wc.lpszClassName=L"Muonium.MenuAbi.Test";
    Need(RegisterClassW(&wc)!=0,"message class");auto window=CreateWindowExW(0,wc.lpszClassName,L"",0,0,0,0,0,HWND_MESSAGE,nullptr,wc.hInstance,nullptr);Need(window!=nullptr,"owned message window");
    auto provider=LoadLibraryW(argv[1]);Need(provider!=nullptr,"provider fixture load");
    auto start=Export<void(*)(HWND,wchar_t const*)>(provider,"FixtureStart");auto enabled=Export<void(*)(bool)>(provider,"FixtureEnabled");auto stop=Export<void(*)()>(provider,"FixtureStop");
    auto menu=LoadLibraryW(argv[2]);Need(menu!=nullptr,"menu ABI fixture load");
    auto ready=Export<void(*)(int)>(menu,"FixtureReady");auto count=Export<unsigned(*)()>(menu,"FixtureProviders");auto pending=Export<unsigned(*)()>(menu,"FixturePending");auto submit=Export<bool(*)(char const*)>(menu,"FixtureSubmit");
    auto folder=std::filesystem::path(argv[1]).parent_path();start(window,folder.c_str());
    Sleep(300);Need(count()==0,"not-ready menu must not register a provider");
    ready(3);Need(Await([&]{return count()==1&&submit("open");}),"ready LL menu must register the actual player provider");
    Need(Await([&]{Pump();return messages==1&&pending()==0;}),"open command must arrive and acknowledge");Need(last==0,"open dispatch value");
    for(auto [action,event]:{std::pair{"hudEditor",7},std::pair{"presets",8},std::pair{"miniPlayer",9},std::pair{"islandSearch",10}}){
        auto previous=messages;Need(submit(action),"explicit action accepted");Need(Await([&]{Pump();return messages==previous+1&&pending()==0;}),"command delivered once with acknowledgement");Need(last==WPARAM(event),"correct player action dispatch");
    }
    auto before=messages;Sleep(450);Pump();Need(messages==before&&pending()==0&&count()==1,"passive polls must not open the player or duplicate sessions");
    enabled(false);Sleep(450);Need(!submit("open"),"disabled player action must be unavailable");enabled(true);Sleep(450);
    ready(2);Need(Await([&]{return count()==0;}),"menu disable must unregister the player workspace");Pump();Need(messages==before,"disable must not dispatch any action");
    ready(3);Need(Await([&]{return count()==1;}),"readiness recovery must create one fresh session");
    auto other=LoadLibraryW(argv[3]);Need(other!=nullptr,"duplicate fixture loaded");
    Need(Await([&]{return count()==0;}),"two menu editions must not leave a registered provider");
    auto otherCount=Export<unsigned(*)()>(other,"FixtureProviders");Need(otherCount()==0,"wrong menu edition must not receive the provider");
    FreeLibrary(other);Need(Await([&]{return count()==1;}),"single-edition recovery");
    stop();Need(count()==0,"provider stop unregisters its session");
    // With only the opposite edition loaded, an LL provider must stay idle.
    FreeLibrary(menu);other=LoadLibraryW(argv[3]);Need(other!=nullptr,"wrong-edition-only fixture");
    auto otherReady=Export<void(*)(int)>(other,"FixtureReady");otherCount=Export<unsigned(*)()>(other,"FixtureProviders");otherReady(3);
    start(window,folder.c_str());Sleep(450);Need(otherCount()==0,"LL provider must not fall back to the vanilla DLL");stop();FreeLibrary(other);
    DestroyWindow(window);
    std::cout<<"Real frozen LL Workspace API: delayed readiness, five actions, passive polling, disable/recovery, duplicate refusal and cleanup passed\n";return 0;
}catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}}
