#include "D3D12Bridge.h"
#include "D3D12Composition.h"
#include "SystemGraphicsLibrary.h"
#include "ArkeInterop.h"
#include "Runtime.h"
#include <d3d11.h>
#include <MinHook.h>
#include <wrl/client.h>
#include <mutex>
#include <array>
#include <atomic>
#include <fstream>
#include <cstring>
#include <chrono>
#include <cstdio>
#include <appmodel.h>
namespace mp {namespace d12 {
using Microsoft::WRL::ComPtr;
using Present=HRESULT(WINAPI*)(IDXGISwapChain*,UINT,UINT);
using Present1=HRESULT(WINAPI*)(IDXGISwapChain1*,UINT,UINT,DXGI_PRESENT_PARAMETERS const*);
using Resize=HRESULT(WINAPI*)(IDXGISwapChain*,UINT,UINT,UINT,DXGI_FORMAT,UINT);
using Resize1=HRESULT(WINAPI*)(IDXGISwapChain3*,UINT,UINT,UINT,DXGI_FORMAT,UINT,UINT const*,IUnknown* const*);
using Execute=void(WINAPI*)(ID3D12CommandQueue*,UINT,ID3D12CommandList* const*);
using Barriers=void(WINAPI*)(ID3D12GraphicsCommandList*,UINT,D3D12_RESOURCE_BARRIER const*);
using Reset=HRESULT(WINAPI*)(ID3D12GraphicsCommandList*,ID3D12CommandAllocator*,ID3D12PipelineState*);
using Close=HRESULT(WINAPI*)(ID3D12GraphicsCommandList*);
using ColorSpace=HRESULT(WINAPI*)(IDXGISwapChain3*,DXGI_COLOR_SPACE_TYPE);
Present originalPresent{};Present1 originalPresent1{};Resize originalResize{};Resize1 originalResize1{};
Execute originalExecute{};Barriers originalBarriers{};Reset originalReset{};Close originalClose{};
ColorSpace originalColorSpace{};
std::mutex mutex;std::atomic<bool> enabled{};thread_local bool inside{};
std::byte* shared{};HANDLE gate{};std::atomic<HWND> owner{};std::filesystem::path logs;
bool attempted{},installed{},failed{},reportedD3D11{};ComPtr<IDXGISwapChain3> swap;ComPtr<ID3D12Device> device;ComPtr<ID3D12CommandQueue> queue;
std::array<ComPtr<ID3D12Resource>,8> buffers;UINT count{};UINT64 generation{},sequence{};
struct Trace {ComPtr<ID3D12GraphicsCommandList> list;UINT presentMask{};UINT64 generation{};bool closed{};};
std::array<Trace,128> traces;CompositionFrame frame;D3D12Composition renderer;
UINT64 presents{},draws{},probes{},received{},queueMatches{},busy{},errors{},resizes{},micros{},maximum{};
std::atomic<UINT64> lockSkips{};UINT64 emptyFrames{},textureAllocations{},uploadedPixelBytes{};
void Withdraw(){if(shared){InterlockedExchange(reinterpret_cast<LONG*>(shared+3072),0);InterlockedExchange64(reinterpret_cast<LONG64*>(shared+3080),0);}}
void Fail(char const* reason){if(!failed)Log(logs,std::string("D3D12 FALLBACK reason=")+reason+" sessionLatched=true");failed=true;Withdraw();if(shared)InterlockedExchange(reinterpret_cast<LONG*>(shared+3096),2);}
bool Owned(IDXGISwapChain* s){auto window=owner.load();DXGI_SWAP_CHAIN_DESC d{};return window&&SUCCEEDED(s->GetDesc(&d))&&d.OutputWindow==window&&IsWindow(window);}
bool Pristine(void* target){
    HMODULE module{};if(!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS|GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,reinterpret_cast<LPCWSTR>(target),&module))return false;
    wchar_t path[32768]{},sys[32768]{};if(!GetModuleFileNameW(module,path,32768)||!GetSystemDirectoryW(sys,32768))return false;
    auto full=std::filesystem::path(path).lexically_normal().wstring(),root=std::filesystem::path(sys).wstring()+L"\\";
    if(full.size()<=root.size()||_wcsnicmp(full.c_str(),root.c_str(),root.size()))return false;
    MEMORY_BASIC_INFORMATION memory{};if(!VirtualQuery(target,&memory,sizeof memory)||memory.State!=MEM_COMMIT||memory.AllocationBase!=module||
        (memory.Protect!=PAGE_EXECUTE_READ&&memory.Protect!=PAGE_EXECUTE))return false;
    std::ifstream f(path,std::ios::binary);if(!f)return false;f.seekg(0,std::ios::end);auto length=f.tellg();if(length<4096||length>512*1024*1024)return false;
    f.seekg(0);std::vector<char> bytes(static_cast<size_t>(length));if(!f.read(bytes.data(),length))return false;
    auto read=[&](size_t at,void* out,size_t n){if(at>bytes.size()||n>bytes.size()-at)return false;std::memcpy(out,bytes.data()+at,n);return true;};
    IMAGE_DOS_HEADER dos{};IMAGE_NT_HEADERS64 nt{};if(!read(0,&dos,sizeof dos)||dos.e_magic!=IMAGE_DOS_SIGNATURE||dos.e_lfanew<64||
        !read(dos.e_lfanew,&nt,sizeof nt)||nt.Signature!=IMAGE_NT_SIGNATURE||nt.FileHeader.NumberOfSections>96)return false;
    auto rva=reinterpret_cast<UINT_PTR>(target)-reinterpret_cast<UINT_PTR>(module);
    for(unsigned i=0;i<nt.FileHeader.NumberOfSections;i++){IMAGE_SECTION_HEADER s{};if(!read(dos.e_lfanew+24+nt.FileHeader.SizeOfOptionalHeader+i*sizeof s,&s,sizeof s))return false;
        if(rva<s.VirtualAddress||rva-s.VirtualAddress>s.SizeOfRawData||32>s.SizeOfRawData-(rva-s.VirtualAddress))continue;auto at=s.PointerToRawData+rva-s.VirtualAddress;
        return (s.Characteristics&(IMAGE_SCN_MEM_WRITE|IMAGE_SCN_MEM_EXECUTE))==IMAGE_SCN_MEM_EXECUTE&&at<=bytes.size()&&32<=bytes.size()-at&&!std::memcmp(bytes.data()+at,target,32);}
    return false;
}
void ClearTracking(){for(auto& b:buffers)b.Reset();count=0;for(auto& t:traces)t={};queue.Reset();device.Reset();swap.Reset();frame={};sequence=0;++generation;Withdraw();if(shared&&!failed)InterlockedExchange(reinterpret_cast<LONG*>(shared+3096),1);}
bool Select(IDXGISwapChain* s){
    ComPtr<IDXGISwapChain3> candidate;ComPtr<ID3D12Device> d;if(FAILED(s->QueryInterface(IID_PPV_ARGS(&candidate))))return false;
    if(FAILED(s->GetDevice(IID_PPV_ARGS(&d)))){
        ComPtr<ID3D11Device> d11;
        if(!reportedD3D11&&SUCCEEDED(s->GetDevice(IID_PPV_ARGS(&d11)))){
            reportedD3D11=true;Log(logs,"D3D12 WAIT owner=verified gameBackend=D3D11 native=false fallback=layered-window");
        }return false;
    }
    if(swap)return swap.Get()==candidate.Get();
    DXGI_SWAP_CHAIN_DESC1 desc{};if(FAILED(candidate->GetDesc1(&desc))||desc.BufferCount<2||desc.BufferCount>buffers.size())return false;
    if(desc.Format!=DXGI_FORMAT_R8G8B8A8_UNORM&&desc.Format!=DXGI_FORMAT_B8G8R8A8_UNORM){Fail("unsupported-format");return false;}
    for(UINT i=0;i<desc.BufferCount;i++)if(FAILED(s->GetBuffer(i,IID_PPV_ARGS(&buffers[i])))){ClearTracking();return false;}
    swap=candidate;device=d;count=desc.BufferCount;++generation;
    Log(logs,"D3D12 SWAPCHAIN selected owner=verified queue=pending backbuffers="+std::to_string(count));return true;
}
void WINAPI BarrierHook(ID3D12GraphicsCommandList* list,UINT n,D3D12_RESOURCE_BARRIER const* barriers){
    originalBarriers(list,n,barriers);auto error=GetLastError();
    if(enabled.load(std::memory_order_relaxed)&&!inside){std::unique_lock lock(mutex,std::try_to_lock);
        if(lock.owns_lock()&&shared&&!failed&&swap&&n<=4096){
            Trace* trace{};for(UINT k=0;k<n;k++){auto const& b=barriers[k];if(b.Type!=D3D12_RESOURCE_BARRIER_TYPE_TRANSITION||b.Flags==D3D12_RESOURCE_BARRIER_FLAG_BEGIN_ONLY)continue;
                for(UINT i=0;i<count;i++)if(buffers[i].Get()==b.Transition.pResource){
                    if(!trace){for(auto& t:traces)if(t.list.Get()==list){trace=&t;break;}
                        if(!trace)for(auto& t:traces)if(!t.list){trace=&t;t.list=list;t.generation=generation;break;}}
                    if(trace){trace->closed=false;if(b.Transition.StateAfter==D3D12_RESOURCE_STATE_PRESENT)trace->presentMask|=1u<<i;else trace->presentMask&=~(1u<<i);}break;
                }
            }
        }}SetLastError(error);
}
HRESULT WINAPI ResetHook(ID3D12GraphicsCommandList* list,ID3D12CommandAllocator* a,ID3D12PipelineState* p){
    auto hr=originalReset(list,a,p);auto error=GetLastError();if(enabled.load(std::memory_order_relaxed)&&!inside){std::lock_guard lock(mutex);for(auto& t:traces)if(t.list.Get()==list)t={};}SetLastError(error);return hr;
}
HRESULT WINAPI CloseHook(ID3D12GraphicsCommandList* list){auto hr=originalClose(list);auto error=GetLastError();if(enabled.load(std::memory_order_relaxed)&&!inside){
    std::lock_guard lock(mutex);for(auto& t:traces)if(t.list.Get()==list)t.closed=SUCCEEDED(hr);}SetLastError(error);return hr;
}
void WINAPI ExecuteHook(ID3D12CommandQueue* q,UINT n,ID3D12CommandList* const* lists){
    // Association becomes visible only after the real game submission was made.
    originalExecute(q,n,lists);auto error=GetLastError();if(enabled.load(std::memory_order_relaxed)&&!inside){std::unique_lock lock(mutex,std::try_to_lock);
        if(lock.owns_lock()&&shared&&!failed&&swap&&n<=4096&&q->GetDesc().Type==D3D12_COMMAND_LIST_TYPE_DIRECT){
            for(UINT i=0;i<n;i++)for(auto const& t:traces)if(t.list.Get()==lists[i]&&t.closed&&t.generation==generation&&t.presentMask){
                ComPtr<ID3D12Device> d;if(FAILED(q->GetDevice(IID_PPV_ARGS(&d)))||d.Get()!=device.Get())continue;
                if(queue&&queue.Get()!=q){Fail("multiple-backbuffer-queues");break;}
                if(!queue){queue=q;++queueMatches;InterlockedExchange(reinterpret_cast<LONG*>(shared+3100),2);InterlockedExchange(reinterpret_cast<LONG*>(shared+3096),0);InterlockedExchange(reinterpret_cast<LONG*>(shared+3072),1);
                    Log(logs,"D3D12 READY backend=native queue=verified-backbuffer-submission transport=BGRA-premultiplied entityDepth=false");}
            }
        }}SetLastError(error);
}
void Receive(){
    auto wait=WaitForSingleObject(gate,0);if(wait!=WAIT_OBJECT_0&&wait!=WAIT_ABANDONED){++busy;return;}
    struct Unlock{HANDLE h;~Unlock(){ReleaseMutex(h);}} unlock{gate};CompositionHeader h;std::memcpy(&h,shared,sizeof h);
    if(h.sequence==sequence||!ValidComposition(h,GetTickCount64()))return;
    frame.header=h;frame.screen.resize(SIZE_T(h.width)*h.height*4);frame.atlas.clear();
    if(!frame.screen.empty())std::memcpy(frame.screen.data(),shared+4096,frame.screen.size());sequence=h.sequence;++received;
}
D3D12Draw Compose(IDXGISwapChain* s,UINT flags){
    if(!enabled.load(std::memory_order_relaxed)||inside||(flags&DXGI_PRESENT_TEST)||!Owned(s)||IsIconic(owner.load()))return D3D12Draw::Empty;
    std::unique_lock lock(mutex,std::try_to_lock);if(!lock.owns_lock()){++lockSkips;return D3D12Draw::Empty;}
    if(!shared||failed||!Select(s)||!queue)return D3D12Draw::Empty;
    if(InterlockedCompareExchange(reinterpret_cast<LONG*>(shared+3076),0,0)!=1)return D3D12Draw::Empty;
    auto begin=std::chrono::steady_clock::now();inside=true;D3D12Draw result=D3D12Draw::Failed;
    try{Receive();auto before=renderer.transferStats();result=renderer.present(swap.Get(),queue.Get(),frame,GetTickCount64());auto after=renderer.transferStats();textureAllocations+=after.allocations-before.allocations;uploadedPixelBytes+=after.uploadBytes-before.uploadBytes;}catch(...){result=D3D12Draw::Failed;}
    inside=false;++presents;if(result==D3D12Draw::Drawn)++draws;if(result==D3D12Draw::Probe)++probes;
    if(result==D3D12Draw::Empty)++emptyFrames;
    if(result==D3D12Draw::Failed){++errors;Fail("gpu-resource-or-submit");}
    // A missed slot must not turn into a recurring visible hole.
    if(result==D3D12Draw::Busy){++busy;Fail("gpu-slot-still-in-flight");}
    auto elapsed=std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-begin).count();micros+=elapsed;maximum=(std::max)(maximum,UINT64(elapsed));return result;
}
void Acknowledge(HRESULT hr,D3D12Draw draw){std::unique_lock lock(mutex,std::try_to_lock);if(!lock.owns_lock()||!shared||!enabled||failed)return;
    if(hr==S_OK&&(draw==D3D12Draw::Drawn||draw==D3D12Draw::Probe))InterlockedExchange64(reinterpret_cast<LONG64*>(shared+3080),GetTickCount64());
    else if(FAILED(hr)){++errors;Fail("present-failed");}
}
HRESULT WINAPI PresentHook(IDXGISwapChain* s,UINT interval,UINT flags){
    auto error=GetLastError();bool nested=inside;if(nested||!enabled||!Owned(s))return originalPresent(s,interval,flags);auto draw=Compose(s,flags);inside=true;SetLastError(error);auto hr=originalPresent(s,interval,flags);auto after=GetLastError();inside=false;Acknowledge(hr,draw);SetLastError(after);return hr;
}
HRESULT WINAPI Present1Hook(IDXGISwapChain1* s,UINT interval,UINT flags,DXGI_PRESENT_PARAMETERS const* params){
    auto error=GetLastError();if(inside||!enabled||!Owned(s))return originalPresent1(s,interval,flags,params);auto draw=Compose(s,flags);inside=true;DXGI_PRESENT_PARAMETERS full{};
    SetLastError(error);auto hr=originalPresent1(s,interval,flags,draw==D3D12Draw::Drawn?&full:params);auto after=GetLastError();inside=false;Acknowledge(hr,draw);SetLastError(after);return hr;
}
bool BeforeResize(IDXGISwapChain* s){if(!enabled||inside||!Owned(s))return true;std::lock_guard lock(mutex);inside=true;auto ok=renderer.reset();inside=false;
    if(!ok){Fail("resize-fence-timeout");return false;}ClearTracking();++resizes;return true;
}
HRESULT WINAPI ResizeHook(IDXGISwapChain* s,UINT n,UINT w,UINT h,DXGI_FORMAT f,UINT flags){
    auto error=GetLastError();if(!BeforeResize(s))return DXGI_ERROR_WAS_STILL_DRAWING;SetLastError(error);return originalResize(s,n,w,h,f,flags);
}
HRESULT WINAPI Resize1Hook(IDXGISwapChain3* s,UINT n,UINT w,UINT h,DXGI_FORMAT f,UINT flags,UINT const* masks,IUnknown* const* queues){
    auto error=GetLastError();if(!BeforeResize(s))return DXGI_ERROR_WAS_STILL_DRAWING;SetLastError(error);return originalResize1(s,n,w,h,f,flags,masks,queues);
}
HRESULT WINAPI ColorSpaceHook(IDXGISwapChain3* s,DXGI_COLOR_SPACE_TYPE color){auto hr=originalColorSpace(s,color);auto error=GetLastError();
    if(SUCCEEDED(hr)&&enabled&&!inside&&Owned(s)&&color!=DXGI_COLOR_SPACE_RGB_FULL_G22_NONE_P709){std::lock_guard lock(mutex);Fail("unsupported-color-space");}SetLastError(error);return hr;
}
bool InitFailure(char const* stage,char const* api,HRESULT hr=S_OK,DWORD win32=0,MH_STATUS hook=MH_OK){
    char message[384]{};std::snprintf(message,sizeof message,"D3D12 INIT FAILED stage=%s api=%s hresult=0x%08lX win32=%lu hookStatus=%s",stage,api,static_cast<unsigned long>(hr),win32,MH_StatusToString(hook));
    Log(logs,message);return false;
}
bool InitResult(char const* stage,char const* api,HRESULT hr){
    if(FAILED(hr))return InitFailure(stage,api,hr);
    Log(logs,std::string("D3D12 INIT OK stage=")+stage+" api="+api);return true;
}
bool Install(){
    UINT32 packageLength{};auto packageResult=GetCurrentPackageFullName(&packageLength,nullptr);
    Log(logs,std::string("D3D12 INIT BEGIN probe=Win32-hwnd libraries=system32 packaged=")+(packageResult==ERROR_INSUFFICIENT_BUFFER?"true":"false"));
    auto dxgi=LoadSystemGraphicsLibrary(L"dxgi.dll");
    if(!dxgi){auto e=GetLastError();return InitFailure("load-library","dxgi.dll",HRESULT_FROM_WIN32(e),e);}
    auto d3d=LoadSystemGraphicsLibrary(L"d3d12.dll");
    if(!d3d){auto e=GetLastError();return InitFailure("load-library","d3d12.dll",HRESULT_FROM_WIN32(e),e);}
    Log(logs,"D3D12 RUNTIME resolved=system32 request=absolute-path verified=true appLocalModules=preserved");
    auto factoryFn=dxgi?reinterpret_cast<decltype(&CreateDXGIFactory1)>(GetProcAddress(dxgi,"CreateDXGIFactory1")):nullptr;
    if(!factoryFn){auto e=GetLastError();return InitFailure("resolve-export","CreateDXGIFactory1",HRESULT_FROM_WIN32(e),e);}
    auto deviceFn=reinterpret_cast<decltype(&D3D12CreateDevice)>(GetProcAddress(d3d,"D3D12CreateDevice"));
    if(!deviceFn){auto e=GetLastError();return InitFailure("resolve-export","D3D12CreateDevice",HRESULT_FROM_WIN32(e),e);}
    ComPtr<IDXGIFactory4> factory;if(!InitResult("factory","CreateDXGIFactory1",factoryFn(IID_PPV_ARGS(&factory))))return false;
    ComPtr<ID3D12Device> d;if(!InitResult("device","D3D12CreateDevice",deviceFn(nullptr,D3D_FEATURE_LEVEL_11_0,IID_PPV_ARGS(&d))))return false;
    D3D12_COMMAND_QUEUE_DESC qd{};qd.Type=D3D12_COMMAND_LIST_TYPE_DIRECT;ComPtr<ID3D12CommandQueue> q;
    ComPtr<ID3D12CommandAllocator> a;ComPtr<ID3D12GraphicsCommandList> l;
    if(!InitResult("command-queue","CreateCommandQueue",d->CreateCommandQueue(&qd,IID_PPV_ARGS(&q))))return false;
    if(!InitResult("command-allocator","CreateCommandAllocator",d->CreateCommandAllocator(qd.Type,IID_PPV_ARGS(&a))))return false;
    if(!InitResult("command-list","CreateCommandList",d->CreateCommandList(0,qd.Type,a.Get(),nullptr,IID_PPV_ARGS(&l))))return false;
    if(!InitResult("command-list-close","Close",l->Close()))return false;
    auto instance=GetModuleHandleW(nullptr);WNDCLASSW wc{};wc.lpfnWndProc=DefWindowProcW;wc.hInstance=instance;wc.lpszClassName=L"MuoniumD3D12Probe";
    if(!RegisterClassW(&wc)){auto e=GetLastError();if(e!=ERROR_CLASS_ALREADY_EXISTS)return InitFailure("window-class","RegisterClassW",HRESULT_FROM_WIN32(e),e);
        WNDCLASSW existing{};if(!GetClassInfoW(instance,wc.lpszClassName,&existing)||existing.lpfnWndProc!=DefWindowProcW||existing.hInstance!=instance)
            return InitFailure("window-class","GetClassInfoW",E_UNEXPECTED);}
    auto window=CreateWindowExW(0,wc.lpszClassName,L"",WS_POPUP,0,0,16,16,nullptr,nullptr,instance,nullptr);
    if(!window){auto e=GetLastError();return InitFailure("probe-window","CreateWindowExW",HRESULT_FROM_WIN32(e),e);}
    struct WindowScope{HWND value;~WindowScope(){DestroyWindow(value);}} windowScope{window};
    DXGI_SWAP_CHAIN_DESC1 desc{};desc.Width=desc.Height=16;desc.Format=DXGI_FORMAT_R8G8B8A8_UNORM;desc.SampleDesc.Count=1;desc.BufferCount=2;desc.BufferUsage=DXGI_USAGE_RENDER_TARGET_OUTPUT;desc.SwapEffect=DXGI_SWAP_EFFECT_FLIP_DISCARD;
    ComPtr<IDXGISwapChain1> first;
    if(!InitResult("probe-swapchain","CreateSwapChainForHwnd",factory->CreateSwapChainForHwnd(q.Get(),window,&desc,nullptr,nullptr,&first)))return false;
    ComPtr<IDXGISwapChain3> s;if(!InitResult("swapchain-interface","QueryInterface-IDXGISwapChain3",first.As(&s)))return false;
    auto st=*reinterpret_cast<void***>(s.Get()),qt=*reinterpret_cast<void***>(q.Get()),lt=*reinterpret_cast<void***>(l.Get());
    void* targets[]{st[8],st[22],st[13],st[39],qt[10],lt[26],lt[10],lt[9],st[38]};
    void* callbacks[]{reinterpret_cast<void*>(PresentHook),reinterpret_cast<void*>(Present1Hook),reinterpret_cast<void*>(ResizeHook),reinterpret_cast<void*>(Resize1Hook),reinterpret_cast<void*>(ExecuteHook),reinterpret_cast<void*>(BarrierHook),reinterpret_cast<void*>(ResetHook),reinterpret_cast<void*>(CloseHook),reinterpret_cast<void*>(ColorSpaceHook)};
    void** originals[]{reinterpret_cast<void**>(&originalPresent),reinterpret_cast<void**>(&originalPresent1),reinterpret_cast<void**>(&originalResize),reinterpret_cast<void**>(&originalResize1),reinterpret_cast<void**>(&originalExecute),reinterpret_cast<void**>(&originalBarriers),reinterpret_cast<void**>(&originalReset),reinterpret_cast<void**>(&originalClose),reinterpret_cast<void**>(&originalColorSpace)};
    char const* names[]{"Present","Present1","ResizeBuffers","ResizeBuffers1","ExecuteCommandLists","ResourceBarrier","Reset","Close","SetColorSpace1"};
    arke::Slots arkeSlots;bool arkeMode=false;
    std::array<unsigned,3> arkeIndices{0,1,4};
    if(!Pristine(targets[0])||!Pristine(targets[1])||!Pristine(targets[4])){
        std::string reason;
        if(!arke::Prepare({targets[0],targets[1],targets[4]},arkeSlots,reason)){
            Log(logs,"D3D12 INTEROP REFUSED reason="+reason+" unknown-hooks=preserved");
            return InitFailure("api-ownership","Present/Present1/ExecuteCommandLists",E_ACCESSDENIED);
        }
        arkeMode=true;
    }
    for(unsigned i=0;i<9;i++){
        if(arkeMode&&(i==0||i==1||i==4))continue;
        if(!Pristine(targets[i]))return InitFailure("api-ownership",names[i],E_ACCESSDENIED);
    }
    auto init=MH_Initialize();if(init!=MH_OK&&init!=MH_ERROR_ALREADY_INITIALIZED)return InitFailure("hook-init","MH_Initialize",S_OK,0,init);
    if(arkeMode)for(unsigned i=0;i<3;i++)*originals[arkeIndices[i]]=arkeSlots.originals[i];
    std::array<unsigned,9> owned{};unsigned made{},on{};bool complete=true;
    for(unsigned i=0;i<9;i++){
        if(arkeMode&&(i==0||i==1||i==4))continue;
        auto status=MH_CreateHook(targets[i],callbacks[i],originals[i]);
        if(status!=MH_OK){InitFailure("hook-create",names[i],S_OK,0,status);complete=false;break;}
        owned[made++]=i;
    }
    if(complete)for(;on<made;on++){
        auto i=owned[on];auto status=MH_EnableHook(targets[i]);
        if(status!=MH_OK){InitFailure("hook-enable",names[i],S_OK,0,status);complete=false;break;}
    }
    if(complete&&arkeMode){
        complete=arke::Commit(arkeSlots,{callbacks[0],callbacks[1],callbacks[4]});
        if(!complete)InitFailure("interop-slot-conflict","Arke-original-call-slots",E_ACCESSDENIED);
        else Log(logs,"D3D12 ARKE profile=98EFECBD6A932F61 phase=after-arke-overlay originalSlots=3 existingHooks=preserved");
    }
    if(complete)return true;
    while(on){auto i=owned[--on];auto status=MH_DisableHook(targets[i]);if(status!=MH_OK)InitFailure("rollback-disable",names[i],S_OK,0,status);}
    while(made){auto i=owned[--made];auto status=MH_RemoveHook(targets[i]);if(status!=MH_OK)InitFailure("rollback-remove",names[i],S_OK,0,status);}
    return false;
}
}
void EnableD3D12Bridge(bool value,std::byte* pixels,HANDLE lock,HWND window,std::filesystem::path const& folder){
    std::lock_guard guard(d12::mutex);d12::logs=folder;d12::owner=window;d12::shared=pixels;d12::gate=lock;
    if(value&&!d12::attempted){d12::attempted=true;d12::inside=true;
        try{d12::installed=d12::Install();}catch(...){d12::installed=false;d12::InitFailure("exception","native-initialization",E_FAIL);}
        d12::inside=false;
        Log(folder,d12::installed?"D3D12 ARMED native compositor awaiting owner swapchain/queue proof":"D3D12 REFUSED native hook initialization; layered output retained");}
    d12::enabled=value&&pixels&&lock&&window&&d12::installed&&!d12::failed;
    if(!d12::enabled)d12::Withdraw();else if(d12::queue)InterlockedExchange(reinterpret_cast<LONG*>(pixels+3072),1);
    else InterlockedExchange(reinterpret_cast<LONG*>(pixels+3096),1);
}
void CloseD3D12Bridge(){d12::enabled=false;std::lock_guard lock(d12::mutex);d12::inside=true;auto drained=d12::renderer.reset();d12::inside=false;
    if(!drained)d12::Fail("shutdown-fence-timeout");d12::ClearTracking();d12::shared=nullptr;d12::gate=nullptr;d12::owner=nullptr;
    // Pinned DLL: leave only dormant forwarders; never disable another mod's hooks.
}
void LogD3D12Bridge(){
    std::filesystem::path folder;bool native{};UINT64 presents{},draws{},probes{},received{},matches{},busy{},errors{},resizes{},micros{},maximum{},skips{},empty{},allocations{},uploadBytes{};
    {
        std::lock_guard lock(d12::mutex);if(!d12::installed)return;
        folder=d12::logs;native=bool(d12::queue&&!d12::failed);presents=d12::presents;draws=d12::draws;probes=d12::probes;received=d12::received;
        matches=d12::queueMatches;busy=d12::busy;errors=d12::errors;resizes=d12::resizes;micros=d12::micros;maximum=d12::maximum;
        skips=d12::lockSkips.load(std::memory_order_relaxed);empty=d12::emptyFrames;allocations=d12::textureAllocations;uploadBytes=d12::uploadedPixelBytes;
    }
    // Disk/antivirus delays must not hold the render lock: Present deliberately
    // avoids waiting on it, so logging under that lock makes visible frame holes.
    Log(folder,"D3D12 STATUS native="+std::to_string(native)+" presents="+std::to_string(presents)+" draws="+std::to_string(draws)+" probes="+std::to_string(probes)+" uploads="+std::to_string(received)
        +" queueMatches="+std::to_string(matches)+" busy="+std::to_string(busy)+" failures="+std::to_string(errors)+" resizes="+std::to_string(resizes)+" cpuAvgUs="+std::to_string(presents?micros/presents:0)+" cpuMaxUs="+std::to_string(maximum)
        +" lockSkips="+std::to_string(skips)+" emptyFrames="+std::to_string(empty)+" textureAllocations="+std::to_string(allocations)+" uploadedPixelBytes="+std::to_string(uploadBytes));
}
}
