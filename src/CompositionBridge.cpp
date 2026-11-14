#include "CompositionBridge.h"
#include "D3D12Bridge.h"
#include "CompositionGpu.h"
#include "CompositionCamera.h"
#include "Runtime.h"
#include <MinHook.h>
#include <wrl/client.h>
#include <dxgi1_4.h>
#include <bcrypt.h>
#include <intrin.h>
#include <mutex>
#include <atomic>
#include <fstream>
#include <cstring>
#include <chrono>
namespace mp {namespace {
using Microsoft::WRL::ComPtr;
using Present=HRESULT(WINAPI*)(IDXGISwapChain*,UINT,UINT);
using Present1=HRESULT(WINAPI*)(IDXGISwapChain1*,UINT,UINT,DXGI_PRESENT_PARAMETERS const*);
using Resize=HRESULT(WINAPI*)(IDXGISwapChain*,UINT,UINT,UINT,DXGI_FORMAT,UINT);
using Resize1=HRESULT(WINAPI*)(IDXGISwapChain3*,UINT,UINT,UINT,DXGI_FORMAT,UINT,UINT const*,IUnknown* const*);
using Draw=void(WINAPI*)(ID3D11DeviceContext*,UINT,UINT,INT);
using Map=HRESULT(WINAPI*)(ID3D11DeviceContext*,ID3D11Resource*,UINT,D3D11_MAP,UINT,D3D11_MAPPED_SUBRESOURCE*);
using Unmap=void(WINAPI*)(ID3D11DeviceContext*,ID3D11Resource*,UINT);
using Clear=void(WINAPI*)(ID3D11DeviceContext*,ID3D11DepthStencilView*,UINT,FLOAT,UINT8);
Present originalPresent{};Present1 originalPresent1{};Resize originalResize{};Resize1 originalResize1{};Draw originalDraw{};Clear originalClear{};Map originalMap{};Unmap originalUnmap{};
std::mutex mutex;HANDLE mapping{},gate{};std::byte* shared{};HWND owner{};
std::filesystem::path logs;bool offline{},installed{},attempted{},releaseNotice{};std::atomic<bool> active{};
std::uintptr_t game{};thread_local bool inside{};DWORD graphicsThread{};
CompositionFrame frame;CompositionGpu gpu;
WorldPacket camera;float renderView[16]{};bool paired{};
ComPtr<ID3D11DepthStencilView> candidate;ComPtr<ID3D11Buffer> candidateCb;UINT candidateFirst{};WorldPacket candidateCamera;float candidateView[16]{};
std::uint64_t presents{},uploads{},captures{},busy{},failures{},sequence{},cameraWrites{},cameraMatches{};
std::uint64_t costMicros{},maxMicros{};
std::uint64_t presentOk{},presentRejected{},probes{},drawHits{},viewportMatches{},bufferMatches{},mapHits{};
UINT lastViewportWidth{},lastViewportHeight{},lastBufferBytes{},lastOffset{};unsigned lastTracked{};
struct Tracked {ComPtr<ID3D11Buffer> cb;UINT first{};CompositionCameraValues values;bool valid{};};
std::array<Tracked,4> tracked;
struct MappedValue{ID3D11Resource* resource{};void* pointer{};UINT first{};};
thread_local std::array<MappedValue,4> mappedValues;
CompositionCameraValues candidateValues;
std::array<CompositionCameraValues,32> cameraHistory;unsigned cameraNext{};
bool SameWorldCamera(WorldPacket const& a,WorldPacket const& b) noexcept {
    if(!a.valid||!b.valid||a.epoch!=b.epoch||a.dimension!=b.dimension)return false;
    for(unsigned i=0;i<3;++i)if(a.eye[i]!=b.eye[i])return false;
    for(unsigned i=0;i<16;++i)if(std::abs(a.view[i]-b.view[i])>.0001f)return false;
    if(std::abs(a.projection[0]-b.projection[0])>.0005f||std::abs(a.projection[5]-b.projection[5])>.0005f)return false;
    return true;
}
bool CopyMapped(void const* ptr,float* out) noexcept {__try{std::memcpy(out,ptr,480);return true;}__except(EXCEPTION_EXECUTE_HANDLER){return false;}}
HRESULT WINAPI Mapped(ID3D11DeviceContext* c,ID3D11Resource* resource,UINT sub,D3D11_MAP kind,UINT flags,D3D11_MAPPED_SUBRESOURCE* result){
    auto status=originalMap(c,resource,sub,kind,flags,result);auto error=GetLastError();
    if(SUCCEEDED(status)&&active.load(std::memory_order_relaxed)&&!inside&&sub==0&&(kind==D3D11_MAP_WRITE_DISCARD||kind==D3D11_MAP_WRITE_NO_OVERWRITE||kind==D3D11_MAP_WRITE)){
        std::unique_lock lock(mutex,std::try_to_lock);if(lock.owns_lock())for(auto& t:tracked)if(t.cb.Get()==resource){
            auto slot=std::find_if(mappedValues.begin(),mappedValues.end(),[&](auto const& v){return !v.resource||v.resource==resource;});
            if(slot!=mappedValues.end()){*slot={resource,result->pData,t.first};++mapHits;}t.valid=false;break;}}
    SetLastError(error);return status;
}
void WINAPI Unmapped(ID3D11DeviceContext* c,ID3D11Resource* resource,UINT sub){auto error=GetLastError();
    auto pending=std::find_if(mappedValues.begin(),mappedValues.end(),[&](auto const& v){return v.resource==resource&&sub==0;});
    MappedValue borrowed;if(pending!=mappedValues.end()){borrowed=*pending;*pending={};}
    if(active.load(std::memory_order_relaxed)&&!inside&&borrowed.pointer){std::unique_lock lock(mutex,std::try_to_lock);
        if(lock.owns_lock())for(auto& t:tracked)if(t.cb.Get()==resource&&t.first==borrowed.first){
            float values[120]{};if(CopyMapped(static_cast<std::byte*>(borrowed.pointer)+t.first*16,values)){++cameraWrites;auto now=GetTickCount64();
                for(unsigned i=0;i<cameraHistory.size();++i){auto const& sample=cameraHistory[(cameraNext+cameraHistory.size()-1-i)%cameraHistory.size()];
                    if(sample.world.at<=now&&now-sample.world.at<=100&&MatchCompositionCamera(values,sample.world,sample.renderView.data(),t.values)){t.valid=true;++cameraMatches;break;}}}
            break;}}
    SetLastError(error);originalUnmap(c,resource,sub);
}
// System API ownership and disk bytes: refuse foreign hooks rather than chain
// through unowned trampolines. This is independent of exact game admission.
bool Pristine(void* target){
    HMODULE module{};if(!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS|GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,reinterpret_cast<LPCWSTR>(target),&module))return false;
    wchar_t path[32768]{},sys[32768]{};if(!GetModuleFileNameW(module,path,32768)||!GetSystemDirectoryW(sys,32768))return false;
    if(_wcsicmp(path,(std::filesystem::path(sys)/L"dxgi.dll").c_str())&&_wcsicmp(path,(std::filesystem::path(sys)/L"d3d11.dll").c_str()))return false;
    std::ifstream f(path,std::ios::binary);if(!f)return false;f.seekg(0,std::ios::end);auto length=f.tellg();if(length<4096||length>128*1024*1024)return false;
    f.seekg(0);std::vector<char> bytes(static_cast<size_t>(length));if(!f.read(bytes.data(),length))return false;
    auto read=[&](size_t at,void* out,size_t n){if(at>bytes.size()||n>bytes.size()-at)return false;std::memcpy(out,bytes.data()+at,n);return true;};
    IMAGE_DOS_HEADER dos{};IMAGE_NT_HEADERS64 nt{};if(!read(0,&dos,sizeof dos)||dos.e_magic!=IMAGE_DOS_SIGNATURE||dos.e_lfanew<64
        ||!read(dos.e_lfanew,&nt,sizeof nt)||nt.Signature!=IMAGE_NT_SIGNATURE||nt.FileHeader.NumberOfSections>96)return false;
    auto rva=reinterpret_cast<std::uintptr_t>(target)-reinterpret_cast<std::uintptr_t>(module);
    for(unsigned i=0;i<nt.FileHeader.NumberOfSections;++i){IMAGE_SECTION_HEADER s{};if(!read(dos.e_lfanew+24+nt.FileHeader.SizeOfOptionalHeader+i*sizeof s,&s,sizeof s))return false;
        if(rva<s.VirtualAddress||rva-s.VirtualAddress>s.SizeOfRawData||32>s.SizeOfRawData-(rva-s.VirtualAddress))continue;
        auto at=s.PointerToRawData+rva-s.VirtualAddress;return (s.Characteristics&(IMAGE_SCN_MEM_WRITE|IMAGE_SCN_MEM_EXECUTE))==IMAGE_SCN_MEM_EXECUTE
            &&at<=bytes.size()&&32<=bytes.size()-at&&!std::memcmp(bytes.data()+at,target,32);}
    return false;
}
bool Backend(std::uintptr_t base){
    constexpr UINT begin=0xDF7E7C0,end=0xDF85F83;MEMORY_BASIC_INFORMATION page{};
    for(auto at=base+begin;at<base+end;){if(!VirtualQuery(reinterpret_cast<void*>(at),&page,sizeof page)||page.AllocationBase!=reinterpret_cast<void*>(base)
        ||page.State!=MEM_COMMIT||(page.Protect!=PAGE_EXECUTE_READ&&page.Protect!=PAGE_EXECUTE))return false;at=reinterpret_cast<std::uintptr_t>(page.BaseAddress)+page.RegionSize;}
    BCRYPT_ALG_HANDLE alg{};BCRYPT_HASH_HANDLE hash{};unsigned char digest[32]{};
    bool ok=BCryptOpenAlgorithmProvider(&alg,BCRYPT_SHA256_ALGORITHM,nullptr,0)>=0;
    if(ok)ok=BCryptCreateHash(alg,&hash,nullptr,0,nullptr,0,0)>=0;if(ok)ok=BCryptHashData(hash,reinterpret_cast<PUCHAR>(base+begin),end-begin,0)>=0;
    if(ok)ok=BCryptFinishHash(hash,digest,32,0)>=0;if(hash)BCryptDestroyHash(hash);if(alg)BCryptCloseAlgorithmProvider(alg,0);
    constexpr unsigned char expected[]{0x41,0x6D,0xF8,0x57,0x5D,0x2D,0x5D,0x6A,0xB7,0x05,0x8F,0x21,0xD1,0xF5,0xCE,0x2E,0xD9,0xE7,0x4E,0x3C,0x7F,0x77,0x25,0x57,0xD7,0x5A,0xEE,0x67,0x65,0x2A,0x5C,0x08};
    return ok&&!std::memcmp(digest,expected,32);
}
void Receive(ID3D11DeviceContext* c){
    auto now=GetTickCount64();auto wait=WaitForSingleObject(gate,0);if(wait!=WAIT_OBJECT_0&&wait!=WAIT_ABANDONED){++busy;return;}
    bool fresh{};
    {struct Release{HANDLE h;~Release(){ReleaseMutex(h);}} release{gate};
        CompositionHeader h;std::memcpy(&h,shared,sizeof h);fresh=h.sequence!=sequence&&ValidComposition(h,now);
        if(fresh){frame.header=h;frame.screen.resize(std::size_t(h.width)*h.height*4);frame.atlas.resize(h.planeCount?CompositionAtlas*std::size_t(h.atlasHeight)*4:0);
            if(!frame.screen.empty())std::memcpy(frame.screen.data(),shared+4096,frame.screen.size());
            if(!frame.atlas.empty())std::memcpy(frame.atlas.data(),shared+4096+CompositionScreenBytes,frame.atlas.size());sequence=h.sequence;}}
    if(fresh){if(gpu.upload(c,frame))++uploads;else ++failures;}
}
bool SwapOwner(IDXGISwapChain* swap){DXGI_SWAP_CHAIN_DESC desc{};return SUCCEEDED(swap->GetDesc(&desc))&&desc.OutputWindow==owner&&IsWindow(owner);}
bool Compose(IDXGISwapChain* swap,UINT flags,bool* ready=nullptr){
    if(ready)*ready=false;
    if(!active.load(std::memory_order_relaxed)||inside||(flags&DXGI_PRESENT_TEST)||!SwapOwner(swap)||IsIconic(owner))return false;
    std::unique_lock lock(mutex,std::try_to_lock);if(!lock.owns_lock()||!active||!shared||InterlockedCompareExchange(reinterpret_cast<LONG*>(shared+3076),0,0)!=1)return false;
    auto thread=GetCurrentThreadId();if(graphicsThread&&graphicsThread!=thread)return false;graphicsThread=thread;inside=true;
    bool painted=false;auto started=std::chrono::steady_clock::now();
    try{ComPtr<ID3D11Device> device;ComPtr<ID3D11DeviceContext> context;ComPtr<ID3D11Texture2D> buffer;ComPtr<ID3D11RenderTargetView> target;
        if(SUCCEEDED(swap->GetDevice(IID_PPV_ARGS(&device)))&&SUCCEEDED(swap->GetBuffer(0,IID_PPV_ARGS(&buffer)))){
            device->GetImmediateContext(&context);Receive(context.Get());
            if(candidate&&candidateCb&&gpu.captureVerified(context.Get(),candidate.Get(),candidateValues.world,candidateValues.renderView.data()))++captures;
            D3D11_TEXTURE2D_DESC desc{};buffer->GetDesc(&desc);
            if((desc.Format==DXGI_FORMAT_R8G8B8A8_UNORM||desc.Format==DXGI_FORMAT_B8G8R8A8_UNORM||desc.Format==DXGI_FORMAT_R8G8B8A8_UNORM_SRGB||desc.Format==DXGI_FORMAT_B8G8R8A8_UNORM_SRGB)
                &&SUCCEEDED(device->CreateRenderTargetView(buffer.Get(),nullptr,&target))){
                auto const& h=frame.header;auto now=GetTickCount64();
                if(h.reserved==CompositionProbeOnly){
                    if(ready)*ready=gpu.status().ready&&h.active&&ValidComposition(h,now)&&!h.planeCount&&h.viewportWidth==desc.Width&&h.viewportHeight==desc.Height;
                    ++probes;
                }else {painted=gpu.present(context.Get(),target.Get(),desc.Width,desc.Height,now);if(ready)*ready=painted;}
            }
            candidate.Reset();candidateCb.Reset();++presents;
        }
    }catch(...){++failures;}
    auto elapsed=std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-started).count();
    costMicros+=elapsed;maxMicros=(std::max)(maxMicros,static_cast<std::uint64_t>(elapsed));inside=false;return painted;
}
void Presented(HRESULT result,bool ready){
    if(!ready)return;auto error=GetLastError();std::unique_lock lock(mutex,std::try_to_lock);
    if(lock.owns_lock()&&active&&shared&&InterlockedCompareExchange(reinterpret_cast<LONG*>(shared+3076),0,0)==1){
        if(result==S_OK){++presentOk;InterlockedExchange64(reinterpret_cast<LONG64*>(shared+3080),GetTickCount64());}
        else ++presentRejected; // Includes OCCLUDED: no visible presentation was accepted.
    }SetLastError(error);
}
HRESULT WINAPI PresentHook(IDXGISwapChain* s,UINT interval,UINT flags){auto error=GetLastError();bool nested=inside,ready{};Compose(s,flags,&ready);inside=true;SetLastError(error);auto r=originalPresent(s,interval,flags);Presented(r,ready);inside=nested;return r;}
HRESULT WINAPI Present1Hook(IDXGISwapChain1* s,UINT interval,UINT flags,DXGI_PRESENT_PARAMETERS const* params){auto error=GetLastError();bool nested=inside,ready{};bool painted=Compose(s,flags,&ready);
    DXGI_PRESENT_PARAMETERS full{};inside=true;SetLastError(error);auto r=originalPresent1(s,interval,flags,painted?&full:params);Presented(r,ready);inside=nested;return r;}
void Reset(IDXGISwapChain* s){if(inside||!SwapOwner(s))return;std::lock_guard lock(mutex);gpu.reset();sequence=0;candidate.Reset();candidateCb.Reset();graphicsThread=0;tracked={};frame={};}
HRESULT WINAPI ResizeHook(IDXGISwapChain* s,UINT n,UINT w,UINT h,DXGI_FORMAT f,UINT flags){auto error=GetLastError();Reset(s);SetLastError(error);return originalResize(s,n,w,h,f,flags);}
HRESULT WINAPI Resize1Hook(IDXGISwapChain3* s,UINT n,UINT w,UINT h,DXGI_FORMAT f,UINT flags,UINT const* masks,IUnknown* const* queues){auto error=GetLastError();Reset(s);SetLastError(error);return originalResize1(s,n,w,h,f,flags,masks,queues);}
void WINAPI Indexed(ID3D11DeviceContext* c,UINT n,UINT first,INT base){auto caller=reinterpret_cast<std::uintptr_t>(_ReturnAddress());originalDraw(c,n,first,base);auto error=GetLastError();
    if(active.load(std::memory_order_relaxed)&&!inside&&(caller==game+0xDF84D6C||caller==game+0xDF84D8F)){
        std::unique_lock lock(mutex,std::try_to_lock);if(lock.owns_lock())++drawHits;
        if(lock.owns_lock()&&paired&&camera.valid&&frame.header.planeCount&&c->GetType()==D3D11_DEVICE_CONTEXT_IMMEDIATE){
            auto thread=GetCurrentThreadId();if(!graphicsThread||graphicsThread==thread){ComPtr<ID3D11DepthStencilView> depth;c->OMGetRenderTargets(0,nullptr,&depth);
                UINT count=1;D3D11_VIEWPORT vp{};c->RSGetViewports(&count,&vp);
                lastViewportWidth=UINT(vp.Width);lastViewportHeight=UINT(vp.Height);
                if(depth&&count==1&&vp.TopLeftX==0&&vp.TopLeftY==0&&vp.MinDepth==0&&vp.MaxDepth==1&&vp.Width==frame.header.viewportWidth&&vp.Height==frame.header.viewportHeight){
                    ++viewportMatches;
                    ComPtr<ID3D11DeviceContext1> c1;c->QueryInterface(IID_PPV_ARGS(&c1));UINT offset{},length{4096};ComPtr<ID3D11Buffer> cb;
                    if(c1)c1->VSGetConstantBuffers1(0,1,&cb,&offset,&length);else c->VSGetConstantBuffers(0,1,&cb);
                    if(cb&&length>=30){
                        ++bufferMatches;D3D11_BUFFER_DESC observed{};cb->GetDesc(&observed);lastBufferBytes=observed.ByteWidth;lastOffset=offset;
                        auto t=std::find_if(tracked.begin(),tracked.end(),[&](auto const& v){return v.cb==cb&&v.first==offset;});
                        if(t==tracked.end()){t=std::find_if(tracked.begin(),tracked.end(),[](auto const& v){return !v.cb;});
                            D3D11_BUFFER_DESC desc{};cb->GetDesc(&desc);if(t!=tracked.end()&&desc.ByteWidth<=65536&&std::uint64_t(offset)*16+480<=desc.ByteWidth){t->cb=cb;t->first=offset;++lastTracked;}}
                        if(t!=tracked.end()&&t->valid&&SameWorldCamera(t->values.world,camera)&&GetTickCount64()-camera.at<=100){
                            candidate=depth;candidateCb=cb;candidateValues=t->values;
                            candidateValues.world.at=camera.at;candidateValues.world.sequence=camera.sequence;}
                    }
                }}
        }}SetLastError(error);
}
void WINAPI Clearing(ID3D11DeviceContext* c,ID3D11DepthStencilView* view,UINT flags,FLOAT depth,UINT8 stencil){auto error=GetLastError();
    if(active.load(std::memory_order_relaxed)&&!inside&&(flags&D3D11_CLEAR_DEPTH)&&depth==1.f){std::unique_lock lock(mutex,std::try_to_lock);
        if(lock.owns_lock()&&candidate.Get()==view){inside=true;try{if(gpu.captureVerified(c,view,candidateValues.world,candidateValues.renderView.data()))++captures;}catch(...){++failures;}
            candidate.Reset();candidateCb.Reset();inside=false;}}
    SetLastError(error);originalClear(c,view,flags,depth,stencil);
}
bool Install(){
    if(!Backend(game))return false;
    auto module=LoadLibraryExW(L"d3d11.dll",nullptr,LOAD_LIBRARY_SEARCH_SYSTEM32);auto create=module?reinterpret_cast<decltype(&D3D11CreateDeviceAndSwapChain)>(GetProcAddress(module,"D3D11CreateDeviceAndSwapChain")):nullptr;
    if(!create)return false;auto instance=GetModuleHandleW(nullptr);WNDCLASSW wc{};wc.lpfnWndProc=DefWindowProcW;wc.hInstance=instance;wc.lpszClassName=L"Muonium.CompositionProbe";
    if(!RegisterClassW(&wc)&&GetLastError()!=ERROR_CLASS_ALREADY_EXISTS)return false;
    auto window=CreateWindowExW(0,wc.lpszClassName,L"",WS_POPUP,0,0,16,16,nullptr,nullptr,instance,nullptr);if(!window)return false;
    DXGI_SWAP_CHAIN_DESC desc{};desc.BufferDesc.Width=desc.BufferDesc.Height=16;desc.BufferDesc.Format=DXGI_FORMAT_R8G8B8A8_UNORM;desc.SampleDesc.Count=1;
    desc.BufferUsage=DXGI_USAGE_RENDER_TARGET_OUTPUT;desc.BufferCount=2;desc.OutputWindow=window;desc.Windowed=TRUE;desc.SwapEffect=DXGI_SWAP_EFFECT_DISCARD;
    ComPtr<ID3D11Device> device;ComPtr<ID3D11DeviceContext> context;ComPtr<IDXGISwapChain> swap;
    auto result=create(nullptr,D3D_DRIVER_TYPE_HARDWARE,nullptr,0,nullptr,0,D3D11_SDK_VERSION,&desc,&swap,&device,nullptr,&context);DestroyWindow(window);
    ComPtr<IDXGISwapChain3> swap3;if(FAILED(result)||FAILED(swap.As(&swap3)))return false;
    auto table=*reinterpret_cast<void***>(swap3.Get());auto draw=*reinterpret_cast<void***>(context.Get());
    void* targets[]{table[8],table[22],table[13],table[39],draw[12],draw[53],draw[14],draw[15]};
    void* callbacks[]{reinterpret_cast<void*>(PresentHook),reinterpret_cast<void*>(Present1Hook),reinterpret_cast<void*>(ResizeHook),reinterpret_cast<void*>(Resize1Hook),reinterpret_cast<void*>(Indexed),reinterpret_cast<void*>(Clearing),reinterpret_cast<void*>(Mapped),reinterpret_cast<void*>(Unmapped)};
    void** originals[]{reinterpret_cast<void**>(&originalPresent),reinterpret_cast<void**>(&originalPresent1),reinterpret_cast<void**>(&originalResize),reinterpret_cast<void**>(&originalResize1),reinterpret_cast<void**>(&originalDraw),reinterpret_cast<void**>(&originalClear),reinterpret_cast<void**>(&originalMap),reinterpret_cast<void**>(&originalUnmap)};
    for(auto p:targets)if(!Pristine(p))return false;auto init=MH_Initialize();if(init!=MH_OK&&init!=MH_ERROR_ALREADY_INITIALIZED)return false;
    unsigned made{},on{};for(;made<8;++made)if(MH_CreateHook(targets[made],callbacks[made],originals[made])!=MH_OK)break;
    if(made==8)for(;on<8;++on)if(MH_EnableHook(targets[on])!=MH_OK)break;
    if(on==8)return true;while(on)MH_DisableHook(targets[--on]);while(made)MH_RemoveHook(targets[--made]);return false;
}
}
bool OpenCompositionBridge(std::wstring const& prefix,std::filesystem::path const& folder,HWND window,bool test){
    std::lock_guard lock(mutex);logs=folder;owner=window;offline=test;paired=false;graphicsThread=0;sequence=0;
    mapping=CreateFileMappingW(INVALID_HANDLE_VALUE,nullptr,PAGE_READWRITE,0,static_cast<DWORD>(CompositionBytes),(prefix+L"-composition").c_str());
    if(!mapping||GetLastError()==ERROR_ALREADY_EXISTS){if(mapping)CloseHandle(mapping);mapping=nullptr;return false;}
    gate=CreateMutexW(nullptr,FALSE,(prefix+L"-composition-lock").c_str());
    if(!gate||GetLastError()==ERROR_ALREADY_EXISTS){if(gate)CloseHandle(gate);CloseHandle(mapping);mapping=gate=nullptr;return false;}
    shared=static_cast<std::byte*>(MapViewOfFile(mapping,FILE_MAP_WRITE,0,0,CompositionBytes));if(!shared){CloseHandle(mapping);CloseHandle(gate);mapping=gate=nullptr;return false;}
    std::memset(shared,0,4096);return true;
}
bool CompositionEnabled(){return active.load();}
void EnableCompositionBridge(bool value,std::uintptr_t base){
    if(value&&active.load())return;std::lock_guard lock(mutex);
    // Keep the unaccepted legacy D3D11 path dormant. The independent D3D12
    // bridge proves its swapchain/queue and negotiates a real Present before
    // selecting native output. Unsupported paths retain the Windows presenter.
    if(!offline){
        active=false;
        EnableD3D12Bridge(value,shared,gate,owner,logs);
        if(value&&!releaseNotice){releaseNotice=true;Log(logs,"COMPOSITION RELEASE backend=auto-d3d12 legacyD3D11Hooks=disabled fallback=layered-window");}
        return;
    }
    if(value&&shared&&!offline&&!attempted){attempted=true;game=base;installed=Install();Log(logs,installed?"COMPOSITION READY native D3D11 Present alpha, GPU world planes; old depth writer hooks disabled":"COMPOSITION REFUSED backend or system API ownership; external renderer retained");}
    active=value&&installed&&shared;if(shared)InterlockedExchange(reinterpret_cast<LONG*>(shared+3072),active?1:0);
}
void CompositionCamera(WorldPacket const& value,float const* view){std::unique_lock lock(mutex,std::try_to_lock);if(!lock.owns_lock())return;camera=value;paired=value.valid&&view;
    if(paired){std::copy(view,view+16,renderView);auto& entry=cameraHistory[cameraNext++%cameraHistory.size()];entry.world=value;std::copy(view,view+16,entry.renderView.begin());}}
void LogCompositionBridge(){std::filesystem::path folder;std::string text;
    LogD3D12Bridge();
    {std::lock_guard lock(mutex);if(!installed)return;folder=logs;auto state=gpu.status();text="COMPOSITION presents="+std::to_string(presents)+" uploads="+std::to_string(uploads)+" gpuDepth="+std::to_string(captures)+" ipcBusy="+std::to_string(busy)+" failures="+std::to_string(failures)+" cameraWrites="+std::to_string(cameraWrites)+" cameraMatches="+std::to_string(cameraMatches)+" cpuAvgUs="+std::to_string(presents?costMicros/presents:0)+" cpuMaxUs="+std::to_string(maxMicros)
        +" gpuStage="+state.stage+" deviceFlags="+std::to_string(state.deviceFlags)+" featureLevel="+std::to_string(state.featureLevel)+" initResult="+std::to_string(static_cast<std::uint32_t>(state.result))+" planes="+std::to_string(frame.header.planeCount)+" occlusion="+std::to_string(frame.header.occlusion)
        +" accepted="+std::to_string(presentOk)+" rejected="+std::to_string(presentRejected)+" probes="+std::to_string(probes)+" drawHits="+std::to_string(drawHits)+" viewports="+std::to_string(viewportMatches)+" cbHits="+std::to_string(bufferMatches)+" tracked="+std::to_string(lastTracked)+" mapHits="+std::to_string(mapHits)
        +" viewport="+std::to_string(lastViewportWidth)+"x"+std::to_string(lastViewportHeight)+" cbBytes="+std::to_string(lastBufferBytes)+" cbFirst="+std::to_string(lastOffset);}
    Log(folder,text);
}
void CloseCompositionBridge(){active=false;std::lock_guard lock(mutex);CloseD3D12Bridge();gpu.reset();frame={};candidate.Reset();candidateCb.Reset();camera={};paired=false;graphicsThread=0;sequence=0;tracked={};cameraHistory={};cameraNext=0;
    if(shared)UnmapViewOfFile(shared);shared=nullptr;if(gate)CloseHandle(gate);if(mapping)CloseHandle(mapping);mapping=gate=nullptr;owner=nullptr;}
}
