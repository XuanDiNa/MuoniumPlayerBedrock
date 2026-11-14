// Map the exact Arke image without running it. Reproduce its three detours and
// original-function slots; no Arke code, imports, TLS or game lifecycle runs.
#define main display_fixture_main
#include "D3D12DisplayFixture.cpp"
#undef main
#include "CompositionBridge.h"
#include <MinHook.h>
#include <array>
#include <vector>
static std::vector<std::string> messages;
namespace mp {void Log(std::filesystem::path const&,std::string const& s)noexcept{messages.push_back(s);}}
static void Require(bool ok,char const* text){if(!ok)throw std::runtime_error(text);}
static HRESULT WINAPI UnknownPresent(IDXGISwapChain*,UINT,UINT){return E_FAIL;}

// After exact-body admission, replace ONLY this no-init fixture mapping's
// callback bodies with typed stand-ins. This exercises the live call slots and
// pixel ordering without executing any Arke instructions/imports.
static Fixture* activeFixture{};
static std::array<void* volatile*,3> activeSlots{};
static unsigned foreignPresents{},foreignExecutes{};
static void PaintForeign(){
    auto& f=*activeFixture;f.wait();
    Check(f.allocator->Reset(),"foreign allocator");Check(f.list->Reset(f.allocator.Get(),nullptr),"foreign list");
    UINT index=f.swap->GetCurrentBackBufferIndex();
    D3D12_RESOURCE_BARRIER b{};b.Type=D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;b.Transition.pResource=f.buffers[index].Get();
    b.Transition.Subresource=D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;b.Transition.StateBefore=D3D12_RESOURCE_STATE_PRESENT;b.Transition.StateAfter=D3D12_RESOURCE_STATE_RENDER_TARGET;
    f.list->ResourceBarrier(1,&b);auto handle=f.heap->GetCPUDescriptorHandleForHeapStart();handle.ptr+=index*f.stride;
    float green[]{0,1,0,1};f.list->ClearRenderTargetView(handle,green,0,nullptr);
    std::swap(b.Transition.StateBefore,b.Transition.StateAfter);f.list->ResourceBarrier(1,&b);Check(f.list->Close(),"foreign close");
    ID3D12CommandList* lists[]{f.list.Get()};f.queue->ExecuteCommandLists(1,lists);
}
static HRESULT WINAPI ForeignPresent(IDXGISwapChain* s,UINT interval,UINT flags){
    ++foreignPresents;PaintForeign();
    return reinterpret_cast<HRESULT(WINAPI*)(IDXGISwapChain*,UINT,UINT)>(*activeSlots[0])(s,interval,flags);
}
static HRESULT WINAPI ForeignPresent1(IDXGISwapChain1* s,UINT interval,UINT flags,DXGI_PRESENT_PARAMETERS const* parameters){
    ++foreignPresents;PaintForeign();
    return reinterpret_cast<HRESULT(WINAPI*)(IDXGISwapChain1*,UINT,UINT,DXGI_PRESENT_PARAMETERS const*)>(*activeSlots[1])(s,interval,flags,parameters);
}
static void WINAPI ForeignExecute(ID3D12CommandQueue* q,UINT n,ID3D12CommandList* const* lists){
    ++foreignExecutes;reinterpret_cast<void(WINAPI*)(ID3D12CommandQueue*,UINT,ID3D12CommandList* const*)>(*activeSlots[2])(q,n,lists);
}
static void ReplaceFixtureBody(void* target,void* replacement){
    unsigned char jump[12]{0x48,0xB8};std::memcpy(jump+2,&replacement,sizeof replacement);jump[10]=0xFF;jump[11]=0xE0;
    DWORD protect{};Require(VirtualProtect(target,sizeof jump,PAGE_EXECUTE_READWRITE,&protect)!=FALSE,"fixture body write");
    std::memcpy(target,jump,sizeof jump);DWORD ignored{};VirtualProtect(target,sizeof jump,protect,&ignored);FlushInstructionCache(GetCurrentProcess(),target,sizeof jump);
}
static std::vector<unsigned char> ReadPixels(Fixture& f,UINT index){
    auto desc=f.buffers[index]->GetDesc();D3D12_PLACED_SUBRESOURCE_FOOTPRINT footprint{};UINT64 bytes{};
    f.device->GetCopyableFootprints(&desc,0,1,0,&footprint,nullptr,nullptr,&bytes);
    D3D12_HEAP_PROPERTIES heap{};heap.Type=D3D12_HEAP_TYPE_READBACK;
    D3D12_RESOURCE_DESC rd{};rd.Dimension=D3D12_RESOURCE_DIMENSION_BUFFER;rd.Width=bytes;rd.Height=1;rd.DepthOrArraySize=rd.MipLevels=1;rd.SampleDesc.Count=1;rd.Layout=D3D12_TEXTURE_LAYOUT_ROW_MAJOR;
    ComPtr<ID3D12Resource> read;Check(f.device->CreateCommittedResource(&heap,D3D12_HEAP_FLAG_NONE,&rd,D3D12_RESOURCE_STATE_COPY_DEST,nullptr,IID_PPV_ARGS(&read)),"readback resource");
    Check(f.allocator->Reset(),"read allocator");Check(f.list->Reset(f.allocator.Get(),nullptr),"read reset");
    D3D12_RESOURCE_BARRIER barrier{};barrier.Type=D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;barrier.Transition.pResource=f.buffers[index].Get();barrier.Transition.Subresource=D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;
    barrier.Transition.StateBefore=D3D12_RESOURCE_STATE_PRESENT;barrier.Transition.StateAfter=D3D12_RESOURCE_STATE_COPY_SOURCE;f.list->ResourceBarrier(1,&barrier);
    D3D12_TEXTURE_COPY_LOCATION src{};src.pResource=f.buffers[index].Get();src.Type=D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX;
    D3D12_TEXTURE_COPY_LOCATION dst{};dst.pResource=read.Get();dst.Type=D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT;dst.PlacedFootprint=footprint;f.list->CopyTextureRegion(&dst,0,0,0,&src,nullptr);
    std::swap(barrier.Transition.StateBefore,barrier.Transition.StateAfter);f.list->ResourceBarrier(1,&barrier);Check(f.list->Close(),"read close");
    ID3D12CommandList* lists[]{f.list.Get()};f.queue->ExecuteCommandLists(1,lists);f.wait();
    void* data{};D3D12_RANGE range{0,static_cast<SIZE_T>(bytes)};Check(read->Map(0,&range,&data),"read map");
    std::vector<unsigned char> result(f.width*f.height*4);for(UINT y=0;y<f.height;++y)std::memcpy(result.data()+y*f.width*4,static_cast<char*>(data)+footprint.Offset+y*footprint.Footprint.RowPitch,f.width*4);
    D3D12_RANGE noWrite{};read->Unmap(0,&noWrite);return result;
}
int main(int argc,char** argv){try{
    Require(argc==2||argc==3,"exact Arke image path required");
    std::string mode=argc==3?argv[2]:"";
    auto image=LoadLibraryExW(std::filesystem::path(argv[1]).c_str(),nullptr,DONT_RESOLVE_DLL_REFERENCES);
    Require(image!=nullptr,"map exact Arke without executing code");
    Fixture f(true);
    auto st=*reinterpret_cast<void***>(f.swap.Get()),qt=*reinterpret_cast<void***>(f.queue.Get());
    std::array<void*,3> targets{st[8],st[22],qt[10]};
    std::array<std::size_t,3> callbacks{0x934F0,0x935E0,0x936D0},slots{0x21ADB90,0x21ADB98,0x21ADBA0};
    std::array<void*,3> originals{};
    Require(MH_Initialize()==MH_OK,"initialize fixture hook owner");
    for(unsigned i=0;i<3;i++){
        auto callback=reinterpret_cast<std::byte*>(image)+callbacks[i];
        if(i==0&&mode=="--unknown-hook")callback=reinterpret_cast<std::byte*>(UnknownPresent);
        Require(MH_CreateHook(targets[i],callback,&originals[i])==MH_OK,"fixture create Arke-shaped detour");
        auto slot=reinterpret_cast<void* volatile*>(reinterpret_cast<std::byte*>(image)+slots[i]);
        Require(InterlockedCompareExchangePointer(slot,originals[i],nullptr)==nullptr,"initialize only empty fixture slot");
        Require(MH_EnableHook(targets[i])==MH_OK,"fixture enable detour");
    }
    if(mode=="--callback-mutated"){
        auto callback=reinterpret_cast<std::byte*>(image)+callbacks[0];DWORD protection{};
        Require(VirtualProtect(callback,1,PAGE_EXECUTE_READWRITE,&protection)!=FALSE,"fixture unprotect callback");
        *callback^=std::byte{1};DWORD ignored{};VirtualProtect(callback,1,protection,&ignored);
    }
    if(mode=="--slot-empty")InterlockedExchangePointer(reinterpret_cast<void* volatile*>(reinterpret_cast<std::byte*>(image)+slots[0]),nullptr);
    if(mode=="--slot-recursive")InterlockedExchangePointer(reinterpret_cast<void* volatile*>(reinterpret_cast<std::byte*>(image)+slots[0]),targets[0]);
    Require(mp::OpenCompositionBridge(L"Local\\MuoniumBedrock-0123456789abcdef0123456789abcded",{},f.window,false),"open private bridge");
    mp::EnableCompositionBridge(true,0);
    bool armed=false,ordered=false;
    for(auto const& s:messages){std::cout<<s<<"\n";armed|=s.find("D3D12 ARMED")!=std::string::npos;ordered|=s.find("phase=after-arke-overlay")!=std::string::npos;}
    if(mode.empty())Require(armed&&ordered,"verified Arke must attach after its overlay, preserving all pre-existing detours");
    else Require(!armed&&!ordered,"unverified Arke state must retain fallback");
    for(unsigned i=0;i<3;i++){
        auto slot=reinterpret_cast<void* volatile*>(reinterpret_cast<std::byte*>(image)+slots[i]);
        if(mode.empty())Require(InterlockedCompareExchangePointer(slot,nullptr,nullptr)!=originals[i],"production bridge replaced only the exact original slot");
        else Require(InterlockedCompareExchangePointer(slot,nullptr,nullptr)==(i==0&&mode=="--slot-empty"?nullptr:i==0&&mode=="--slot-recursive"?targets[0]:originals[i]),"refusal must preserve all foreign slots");
        Require(MH_EnableHook(targets[i])==MH_ERROR_ENABLED,"pre-existing Arke-shaped hook is still enabled");
    }
    if(mode.empty()){
        activeFixture=&f;
        std::array<void*,3> standins{reinterpret_cast<void*>(ForeignPresent),reinterpret_cast<void*>(ForeignPresent1),reinterpret_cast<void*>(ForeignExecute)};
        for(unsigned i=0;i<3;i++){
            activeSlots[i]=reinterpret_cast<void* volatile*>(reinterpret_cast<std::byte*>(image)+slots[i]);
            ReplaceFixtureBody(reinterpret_cast<std::byte*>(image)+callbacks[i],standins[i]);
        }
        std::wstring prefix=L"Local\\MuoniumBedrock-0123456789abcdef0123456789abcded";
        auto mapping=OpenFileMappingW(FILE_MAP_ALL_ACCESS,FALSE,(prefix+L"-composition").c_str());Require(mapping!=nullptr,"fixture mailbox");
        auto shared=static_cast<std::byte*>(MapViewOfFile(mapping,FILE_MAP_ALL_ACCESS,0,0,mp::CompositionBytes));Require(shared!=nullptr,"fixture pixels");
        auto publish=[&](bool probe,unsigned sequence){mp::CompositionHeader h;h.sequence=sequence;h.at=GetTickCount64();h.active=true;
            h.viewportWidth=f.width;h.viewportHeight=f.height;h.x=4;h.y=8;h.width=16;h.height=16;h.reserved=probe?mp::CompositionProbeOnly:0;
            std::memcpy(shared,&h,sizeof h);for(unsigned i=0;i<256;i++)reinterpret_cast<unsigned*>(shared+4096)[i]=0x80800000;
            InterlockedExchange(reinterpret_cast<LONG*>(shared+3076),1);};
        for(unsigned i=0;i<8;i++){publish(true,i+1);f.draw();}
        Require(*reinterpret_cast<LONG*>(shared+3072)==1,"slot forwarding proves real backbuffer queue");
        for(unsigned i=0;i<20;i++){
            f.present1=i%2;publish(false,9+i);auto index=f.swap->GetCurrentBackBufferIndex();auto before=foreignPresents;f.draw();
            auto pixels=ReadPixels(f,index);auto p=pixels.data()+(10*f.width+10)*4;
            Require(p[0]>=127&&p[0]<=129&&p[1]>=126&&p[1]<=128&&p[2]==0,"red player composed exactly once AFTER green foreign overlay");
            auto outside=pixels.data()+(2*f.width+2)*4;
            Require(outside[0]==0&&outside[1]==255&&outside[2]==0,"foreign overlay outside player crop preserved");
            Require(foreignPresents==before+1,"one foreign callback per Present/Present1");
        }
        Require(foreignExecutes>20,"foreign queue accounting remains active");
        mp::CloseCompositionBridge();UnmapViewOfFile(shared);CloseHandle(mapping);
        std::cout<<"D3D12_ARKE_ORDER typedStandins=true arkeCodeExecuted=false pixelChecks=40 present1=true passed\n";
    }
    mp::CloseCompositionBridge();
    // Only the fixture owns these three hooks; the production bridge must leave
    // them alone. Arke itself was never called.
    for(unsigned i=0;i<3;i++){
        auto slot=reinterpret_cast<void* volatile*>(reinterpret_cast<std::byte*>(image)+slots[i]);
        InterlockedExchangePointer(slot,originals[i]);
        Require(MH_DisableHook(targets[i])==MH_OK,"fixture retains detour ownership");
        Require(MH_RemoveHook(targets[i])==MH_OK,"fixture removes only its own detour");
    }
    std::cout<<"D3D12_ARKE_INTEROP structural=true arkeCodeExecuted=false mode="<<mode<<" passed\n";return 0;
}catch(std::exception const& e){std::cerr<<"FAIL "<<e.what()<<"\n";return 1;}}
