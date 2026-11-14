// Real disabled-hook collision: initialization must identify the exact API and
// MinHook result, roll back its own entries, and preserve the existing hook.
#define main display_fixture_main
#include "D3D12DisplayFixture.cpp"
#undef main
#include "CompositionBridge.h"
#include <MinHook.h>
#include <vector>
static std::vector<std::string> messages;
namespace mp {void Log(std::filesystem::path const&,std::string const& s)noexcept{messages.push_back(s);}}
using Execute=void(WINAPI*)(ID3D12CommandQueue*,UINT,ID3D12CommandList* const*);
static Execute foreign;
static void WINAPI ForeignExecute(ID3D12CommandQueue* q,UINT n,ID3D12CommandList* const* lists){foreign(q,n,lists);}
static unsigned checks;
static void Require(bool ok,char const* text){++checks;if(!ok)throw std::runtime_error(text);}
int main(){try{
    Fixture f(true);auto target=(*reinterpret_cast<void***>(f.queue.Get()))[10];
    Require(MH_Initialize()==MH_OK,"fixture MinHook init");
    Require(MH_CreateHook(target,reinterpret_cast<void*>(ForeignExecute),reinterpret_cast<void**>(&foreign))==MH_OK,"create existing disabled hook");
    auto prefix=L"Local\\MuoniumBedrock-0123456789abcdef0123456789abcdea";
    Require(mp::OpenCompositionBridge(prefix,{},f.window,false),"open production bridge");
    mp::EnableCompositionBridge(true,0);
    bool diagnosed=false;
    for(auto const& s:messages){std::cout<<s<<"\n";diagnosed|=s.find("stage=hook-create api=ExecuteCommandLists")!=std::string::npos&&s.find("hookStatus=MH_ERROR_ALREADY_CREATED")!=std::string::npos;}
    Require(diagnosed,"initialization must diagnose the failed API and exact MinHook status");
    Require(MH_EnableHook(target)==MH_OK,"pre-existing hook preserved");
    Require(MH_DisableHook(target)==MH_OK,"restore fixture API");
    auto present=(*reinterpret_cast<void***>(f.swap.Get()))[8];
    Require(MH_RemoveHook(present)==MH_ERROR_NOT_CREATED,"partially created own hook rolled back");
    mp::CloseCompositionBridge();Require(MH_RemoveHook(target)==MH_OK,"only fixture removes its own hook");
    std::cout<<"D3D12_ADMISSION checks="<<checks<<" passed\n";return 0;
}catch(std::exception const& e){std::cerr<<"FAIL "<<e.what()<<"\n";return 1;}}
