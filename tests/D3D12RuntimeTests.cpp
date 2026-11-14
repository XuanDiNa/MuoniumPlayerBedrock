// Reproduce an already-loaded same-name failure DLL without loading game code.
#include <Windows.h>
#include <d3d12.h>
#include <d3d11.h>
#include <dxgi1_4.h>
#include <filesystem>
#include <vector>
#include <string>
#include <iostream>
#include <cstring>
#include "CompositionBridge.h"
#include "D3D12Composition.h"
static HMODULE RealSystem(wchar_t const* name){
    wchar_t system[32768]{};if(!GetSystemDirectoryW(system,32768))throw std::runtime_error("fixture system directory");
    auto path=std::filesystem::path(system)/name;
    auto result=LoadLibraryExW(path.c_str(),nullptr,LOAD_LIBRARY_SEARCH_SYSTEM32);
    if(!result)throw std::runtime_error("fixture genuine system module");return result;
}
static HRESULT WINAPI FixtureCreate(IUnknown* adapter,D3D_FEATURE_LEVEL level,REFIID iid,void** value){
    auto module=RealSystem(L"d3d12.dll");auto fn=reinterpret_cast<decltype(&D3D12CreateDevice)>(GetProcAddress(module,"D3D12CreateDevice"));return fn(adapter,level,iid,value);
}
static HRESULT WINAPI FixtureFactory(REFIID iid,void** value){
    auto module=RealSystem(L"dxgi.dll");auto fn=reinterpret_cast<decltype(&CreateDXGIFactory1)>(GetProcAddress(module,"CreateDXGIFactory1"));return fn(iid,value);
}
#define D3D12CreateDevice FixtureCreate
#define CreateDXGIFactory1 FixtureFactory
#define main display_fixture_main
#include "D3D12DisplayFixture.cpp"
#undef main
#undef CreateDXGIFactory1
#undef D3D12CreateDevice
static std::vector<std::string> messages;
namespace mp {void Log(std::filesystem::path const&,std::string const& s)noexcept{messages.push_back(s);std::cout<<s<<"\n";}}
static unsigned checks;
static void Require(bool condition,char const* message){++checks;if(!condition)throw std::runtime_error(message);}
static bool Logged(char const* text){for(auto const& s:messages)if(s.find(text)!=std::string::npos)return true;return false;}
int wmain(int argc,wchar_t** argv){try{
    bool d11=argc>1&&std::wstring(argv[1])==L"--d3d11";
    wchar_t own[32768]{},temp[32768]{};GetModuleFileNameW(nullptr,own,32768);GetTempPathW(32768,temp);
    auto folder=std::filesystem::path(temp)/(L"Muonium-owned-d3d12-runtime-"+std::to_wstring(GetCurrentProcessId()));
    Require(std::filesystem::create_directory(folder),"create unique owned fixture folder");
    auto stubPath=folder/L"d3d12.dll";
    std::filesystem::copy_file(std::filesystem::path(own).parent_path()/L"D3D12FailureStub.dll",stubPath);
    auto before=std::filesystem::file_size(stubPath);
    auto stub=LoadLibraryExW(stubPath.c_str(),nullptr,LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR|LOAD_LIBRARY_SEARCH_SYSTEM32);
    Require(stub!=nullptr,"preload owned same-name failure runtime");
    auto calls=reinterpret_cast<unsigned(WINAPI*)()>(GetProcAddress(stub,"FixtureCalls"));
    Require(calls&&calls()==0,"preloaded stub starts unused");
    // This demonstrates why the old short-name request is insufficient.
    auto ambiguous=LoadLibraryExW(L"d3d12.dll",nullptr,LOAD_LIBRARY_SEARCH_SYSTEM32);
    Require(ambiguous==stub,"short-name SYSTEM32 request reuses the preloaded failure DLL");FreeLibrary(ambiguous);
    std::wstring prefix=L"Local\\MuoniumBedrock-0123456789abcdef0123456789abcdbb";
    if(d11){
        auto instance=GetModuleHandleW(nullptr);WNDCLASSW wc{};wc.lpfnWndProc=DefWindowProcW;wc.hInstance=instance;wc.lpszClassName=L"MuoniumRuntimeD11";RegisterClassW(&wc);
        auto window=CreateWindowExW(0,wc.lpszClassName,L"",WS_POPUP,0,0,64,64,nullptr,nullptr,instance,nullptr);Require(window!=nullptr,"create owned D3D11 window");
        auto module=RealSystem(L"d3d11.dll");auto create=reinterpret_cast<decltype(&D3D11CreateDeviceAndSwapChain)>(GetProcAddress(module,"D3D11CreateDeviceAndSwapChain"));
        DXGI_SWAP_CHAIN_DESC desc{};desc.BufferDesc.Width=desc.BufferDesc.Height=64;desc.BufferDesc.Format=DXGI_FORMAT_R8G8B8A8_UNORM;desc.SampleDesc.Count=1;desc.BufferUsage=DXGI_USAGE_RENDER_TARGET_OUTPUT;desc.BufferCount=2;desc.OutputWindow=window;desc.Windowed=TRUE;desc.SwapEffect=DXGI_SWAP_EFFECT_FLIP_DISCARD;
        Microsoft::WRL::ComPtr<IDXGISwapChain> swap;Microsoft::WRL::ComPtr<ID3D11Device> device;Microsoft::WRL::ComPtr<ID3D11DeviceContext> context;
        Require(SUCCEEDED(create(nullptr,D3D_DRIVER_TYPE_WARP,nullptr,0,nullptr,0,D3D11_SDK_VERSION,&desc,&swap,&device,nullptr,&context)),"create genuine D3D11 swapchain");
        Require(mp::OpenCompositionBridge(prefix,{},window,false),"open bridge for D3D11 owner");mp::EnableCompositionBridge(true,0);
        Require(Logged("D3D12 ARMED"),"genuine probe can arm despite preloaded failure DLL");
        for(int i=0;i<3;i++)Require(SUCCEEDED(swap->Present(0,0)),"D3D11 presentation still forwards");
        Require(Logged("gameBackend=D3D11"),"actual owner swapchain reports D3D11, not native D3D12");
        unsigned reported{};for(auto const& s:messages)reported+=s.find("gameBackend=D3D11")!=std::string::npos;
        Require(reported==1,"backend attribution is logged once");Require(!Logged("D3D12 READY"),"D3D11 cannot claim a D3D12 queue");
        mp::CloseCompositionBridge();context.Reset();device.Reset();swap.Reset();DestroyWindow(window);
    }else{
        Fixture f(true);Require(mp::OpenCompositionBridge(prefix,{},f.window,false),"open bridge for genuine D3D12 owner");mp::EnableCompositionBridge(true,0);
        Require(Logged("D3D12 ARMED"),"production must initialize with the genuine system runtime despite preloaded stub");
        for(int i=0;i<3;i++)f.draw();Require(Logged("D3D12 READY"),"actual owner queue is proven after system runtime selection");
        mp::CompositionFrame frame;frame.header.sequence=1;frame.header.at=GetTickCount64();frame.header.active=1;frame.header.viewportWidth=f.width;frame.header.viewportHeight=f.height;
        frame.header.x=4;frame.header.y=8;frame.header.width=frame.header.height=16;frame.screen.resize(16*16*4);
        for(unsigned i=0;i<256;i++)reinterpret_cast<unsigned*>(frame.screen.data())[i]=0x80800000;
        mp::D3D12Composition renderer;
        Require(renderer.present(f.swap.Get(),f.queue.Get(),frame,GetTickCount64())==mp::D3D12Draw::Drawn,"root serializer and owned GPU pipeline must also use the genuine system runtime");
        f.wait();Require(renderer.reset(),"owned compositor drains");mp::CloseCompositionBridge();
    }
    Require(calls()==0,"production never calls the unrelated failure runtime");
    Require(std::filesystem::file_size(stubPath)==before,"preloaded runtime file unchanged");
    Require(FreeLibrary(stub)!=FALSE,"release only the fixture's own preload");
    Require(std::filesystem::remove(stubPath),"remove only the owned fixture DLL");Require(std::filesystem::remove(folder),"remove empty owned fixture folder");
    std::cout<<"D3D12_RUNTIME checks="<<checks<<" passed backend="<<(d11?"D3D11":"D3D12")<<" stubCalls=0\n";return 0;
}catch(std::exception const& e){std::cerr<<"FAIL "<<e.what()<<"\n";return 1;}}
