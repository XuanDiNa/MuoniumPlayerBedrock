#include "DepthBridge.h"
#include "DepthWrites.h"
#include "Runtime.h"
#include <MinHook.h>
#include <bcrypt.h>
#include <wrl/client.h>
#include <intrin.h>
#include <atomic>
#include <mutex>
#include <fstream>
#include <vector>
#include <cstring>
namespace mp {namespace {
using Microsoft::WRL::ComPtr;
using Draw=void(WINAPI*)(ID3D11DeviceContext*,UINT,UINT,INT);
using Clear=void(WINAPI*)(ID3D11DeviceContext*,ID3D11DepthStencilView*,UINT,FLOAT,UINT8);
using Unmap=void(WINAPI*)(ID3D11DeviceContext*,ID3D11Resource*,UINT);
using Update=void(WINAPI*)(ID3D11DeviceContext*,ID3D11Resource*,UINT,D3D11_BOX const*,void const*,UINT,UINT);
using Copy=void(WINAPI*)(ID3D11DeviceContext*,ID3D11Resource*,ID3D11Resource*);
using CopyRegion=void(WINAPI*)(ID3D11DeviceContext*,ID3D11Resource*,UINT,UINT,UINT,UINT,ID3D11Resource*,UINT,D3D11_BOX const*);
Draw originalDraw{};Clear originalClear{};
Unmap originalUnmap{};Update originalUpdate{};Copy originalCopy{};CopyRegion originalCopyRegion{};
DepthWrites writes;
std::atomic<bool> active{};std::atomic<std::uint64_t> requestUntil{};std::mutex state;
HANDLE mapping{},gate{};DepthShared* shared{};std::filesystem::path logs;
bool offline{},attempted{},installed{};std::uintptr_t game{};
DepthGpu gpu;DepthResult output;std::uint64_t sequence{},draws{},clears{},submitted{},published{},busy{};
DepthRequest requested;
thread_local bool inside{};
bool Backend(std::uintptr_t base){
    constexpr std::uint32_t begin=0xDF7E7C0,end=0xDF85F83;
    MEMORY_BASIC_INFORMATION page{};
    for(auto at=base+begin;at<base+end;){
        if(!VirtualQuery(reinterpret_cast<void*>(at),&page,sizeof page)||page.AllocationBase!=reinterpret_cast<void*>(base)
            ||page.State!=MEM_COMMIT||(page.Protect!=PAGE_EXECUTE_READ&&page.Protect!=PAGE_EXECUTE))return false;
        at=reinterpret_cast<std::uintptr_t>(page.BaseAddress)+page.RegionSize;
    }
    BCRYPT_ALG_HANDLE alg{};BCRYPT_HASH_HANDLE hash{};unsigned char digest[32]{};
    bool ok=BCryptOpenAlgorithmProvider(&alg,BCRYPT_SHA256_ALGORITHM,nullptr,0)>=0;
    if(ok)ok=BCryptCreateHash(alg,&hash,nullptr,0,nullptr,0,0)>=0;
    if(ok)ok=BCryptHashData(hash,reinterpret_cast<PUCHAR>(base+begin),end-begin,0)>=0;
    if(ok)ok=BCryptFinishHash(hash,digest,32,0)>=0;
    if(hash)BCryptDestroyHash(hash);if(alg)BCryptCloseAlgorithmProvider(alg,0);
    constexpr unsigned char expected[]{0x41,0x6D,0xF8,0x57,0x5D,0x2D,0x5D,0x6A,0xB7,0x05,0x8F,0x21,0xD1,0xF5,0xCE,0x2E,0xD9,0xE7,0x4E,0x3C,0x7F,0x77,0x25,0x57,0xD7,0x5A,0xEE,0x67,0x65,0x2A,0x5C,0x08};
    return ok&&!std::memcmp(digest,expected,32);
}
// API hook ownership is independent of game admission. Reject existing detours
// rather than replacing another plugin's original/trampoline.
bool Pristine(void* target){
    HMODULE owner{};if(!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS|GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,reinterpret_cast<LPCWSTR>(target),&owner))return false;
    wchar_t path[32768]{},sys[32768]{};if(!GetModuleFileNameW(owner,path,32768)||!GetSystemDirectoryW(sys,32768))return false;
    if(_wcsicmp(path,(std::filesystem::path(sys)/L"d3d11.dll").c_str()))return false;
    std::ifstream f(path,std::ios::binary);if(!f)return false;f.seekg(0,std::ios::end);auto size=f.tellg();
    if(size<4096||size>128*1024*1024)return false;f.seekg(0);std::vector<char> bytes(static_cast<std::size_t>(size));if(!f.read(bytes.data(),size))return false;
    auto read=[&](std::size_t at,void* out,std::size_t n){if(at>bytes.size()||n>bytes.size()-at)return false;std::memcpy(out,bytes.data()+at,n);return true;};
    IMAGE_DOS_HEADER dos{};IMAGE_NT_HEADERS64 nt{};
    if(!read(0,&dos,sizeof dos)||dos.e_magic!=IMAGE_DOS_SIGNATURE||dos.e_lfanew<64||!read(dos.e_lfanew,&nt,sizeof nt)
        ||nt.Signature!=IMAGE_NT_SIGNATURE||nt.FileHeader.NumberOfSections>96)return false;
    auto rva=reinterpret_cast<std::uintptr_t>(target)-reinterpret_cast<std::uintptr_t>(owner);
    for(unsigned i=0;i<nt.FileHeader.NumberOfSections;++i){IMAGE_SECTION_HEADER section{};
        if(!read(dos.e_lfanew+24+nt.FileHeader.SizeOfOptionalHeader+i*sizeof section,&section,sizeof section))return false;
        if(rva<section.VirtualAddress||rva-section.VirtualAddress>section.SizeOfRawData||32>section.SizeOfRawData-(rva-section.VirtualAddress))continue;
        auto at=section.PointerToRawData+rva-section.VirtualAddress;
        return (section.Characteristics&(IMAGE_SCN_MEM_EXECUTE|IMAGE_SCN_MEM_WRITE))==IMAGE_SCN_MEM_EXECUTE
            &&at<=bytes.size()&&32<=bytes.size()-at&&!std::memcmp(bytes.data()+at,target,32);
    }return false;
}
DepthRequest Request(){
    DepthRequest r{};r.active=0;if(!shared||!gate)return r;
    auto wait=WaitForSingleObject(gate,0);if(wait==WAIT_OBJECT_0||wait==WAIT_ABANDONED){r=shared->request;ReleaseMutex(gate);}return r;
}
void Poll(ID3D11DeviceContext* c,std::uint64_t now){
    if(!gpu.poll(c,output,now)||!shared||!gate)return;
    auto wait=WaitForSingleObject(gate,0);if(wait!=WAIT_OBJECT_0&&wait!=WAIT_ABANDONED){++busy;return;}
    auto const& r=shared->request;
    if(ValidDepthRequest(r,now)&&output.camera.epoch==r.epoch&&output.camera.dimension==r.dimension
        &&r.viewportWidth==output.viewportWidth&&r.viewportHeight==output.viewportHeight){
        shared->valid=0;shared->camera=output.camera;shared->at=output.at;shared->viewportWidth=output.viewportWidth;shared->viewportHeight=output.viewportHeight;
        shared->x=output.x;shared->y=output.y;shared->width=output.width;shared->height=output.height;shared->sampleStep=output.sampleStep;
        std::memcpy(shared->rgba,output.rgba.data(),output.rgba.size());shared->sequence=++sequence;shared->valid=1;++published;
    }ReleaseMutex(gate);
}
void WINAPI Indexed(ID3D11DeviceContext* c,UINT count,UINT first,INT base){
    auto error=GetLastError();auto caller=reinterpret_cast<std::uintptr_t>(_ReturnAddress());
    if(active.load(std::memory_order_relaxed)&&!inside&&(caller==game+0xDF84D6C||caller==game+0xDF84D8F)
        &&requestUntil.load(std::memory_order_relaxed)>=GetTickCount64()){
        std::unique_lock lock(state,std::try_to_lock);
        if(lock.owns_lock())try{inside=true;auto now=GetTickCount64();auto r=requested;++draws;
            if(ValidDepthRequest(r,now)&&c->GetType()==D3D11_DEVICE_CONTEXT_IMMEDIATE){
                ComPtr<ID3D11DepthStencilView> dsv;c->OMGetRenderTargets(0,nullptr,&dsv);
                UINT n=1;D3D11_VIEWPORT viewport{};c->RSGetViewports(&n,&viewport);
                if(dsv&&n==1&&viewport.TopLeftX==0&&viewport.TopLeftY==0&&viewport.Width==r.viewportWidth&&viewport.Height==r.viewportHeight
                    &&viewport.MinDepth==0&&viewport.MaxDepth==1){
                    ComPtr<ID3D11Buffer> cb;ComPtr<ID3D11DeviceContext1> ranged;c->QueryInterface(IID_PPV_ARGS(&ranged));UINT offset=0,length=4096;
                    if(ranged)ranged->VSGetConstantBuffers1(0,1,&cb,&offset,&length);else c->VSGetConstantBuffers(0,1,&cb);
                    if(length>=30)gpu.observe(c,dsv.Get(),cb.Get(),offset,writes.observe(cb.Get()));
                }
            }inside=false;
        }catch(...){inside=false;}
    }
    SetLastError(error);originalDraw(c,count,first,base);
}
void WINAPI Clearing(ID3D11DeviceContext* c,ID3D11DepthStencilView* view,UINT flags,FLOAT depth,UINT8 stencil){
    auto error=GetLastError();if(active.load(std::memory_order_relaxed)&&!inside&&requestUntil.load(std::memory_order_relaxed)>=GetTickCount64()){
        std::unique_lock lock(state,std::try_to_lock);
        if(lock.owns_lock())try{inside=true;auto now=GetTickCount64();++clears;Poll(c,now);
            if((flags&D3D11_CLEAR_DEPTH)&&depth==1.f&&gpu.beforeClear(c,view,requested,now))++submitted;
            inside=false;
        }catch(...){inside=false;}
    }SetLastError(error);originalClear(c,view,flags,depth,stencil);
}
void Written(ID3D11Resource* resource) noexcept {
    if(!inside&&active.load(std::memory_order_relaxed)&&requestUntil.load(std::memory_order_relaxed)>=GetTickCount64())writes.written(resource);
}
void WINAPI Unmapped(ID3D11DeviceContext* c,ID3D11Resource* resource,UINT sub){
    originalUnmap(c,resource,sub);auto error=GetLastError();Written(resource);SetLastError(error);
}
void WINAPI Updated(ID3D11DeviceContext* c,ID3D11Resource* resource,UINT sub,D3D11_BOX const* box,void const* data,UINT row,UINT depth){
    originalUpdate(c,resource,sub,box,data,row,depth);auto error=GetLastError();Written(resource);SetLastError(error);
}
void WINAPI Copied(ID3D11DeviceContext* c,ID3D11Resource* dest,ID3D11Resource* source){
    originalCopy(c,dest,source);auto error=GetLastError();Written(dest);SetLastError(error);
}
void WINAPI RegionCopied(ID3D11DeviceContext* c,ID3D11Resource* dest,UINT sub,UINT x,UINT y,UINT z,ID3D11Resource* source,UINT srcSub,D3D11_BOX const* box){
    originalCopyRegion(c,dest,sub,x,y,z,source,srcSub,box);auto error=GetLastError();Written(dest);SetLastError(error);
}
bool Install(){
    if(!Backend(game))return false;
    auto module=LoadLibraryExW(L"d3d11.dll",nullptr,LOAD_LIBRARY_SEARCH_SYSTEM32);if(!module)return false;
    auto create=reinterpret_cast<decltype(&D3D11CreateDevice)>(GetProcAddress(module,"D3D11CreateDevice"));if(!create)return false;
    ComPtr<ID3D11Device> device;ComPtr<ID3D11DeviceContext> context;D3D_FEATURE_LEVEL level=D3D_FEATURE_LEVEL_11_0;
    if(FAILED(create(nullptr,D3D_DRIVER_TYPE_HARDWARE,nullptr,0,&level,1,D3D11_SDK_VERSION,&device,nullptr,&context)))return false;
    auto table=*reinterpret_cast<void***>(context.Get());void* targets[]{table[12],table[53],table[15],table[48],table[47],table[46]};
    for(auto target:targets)if(!Pristine(target))return false;
    auto init=MH_Initialize();if(init!=MH_OK&&init!=MH_ERROR_ALREADY_INITIALIZED)return false;
    void* callbacks[]{reinterpret_cast<void*>(&Indexed),reinterpret_cast<void*>(&Clearing),reinterpret_cast<void*>(&Unmapped),reinterpret_cast<void*>(&Updated),reinterpret_cast<void*>(&Copied),reinterpret_cast<void*>(&RegionCopied)};
    void** originals[]{reinterpret_cast<void**>(&originalDraw),reinterpret_cast<void**>(&originalClear),reinterpret_cast<void**>(&originalUnmap),reinterpret_cast<void**>(&originalUpdate),reinterpret_cast<void**>(&originalCopy),reinterpret_cast<void**>(&originalCopyRegion)};
    unsigned created{},enabled{};
    for(;created<std::size(targets);++created)if(MH_CreateHook(targets[created],callbacks[created],originals[created])!=MH_OK)break;
    if(created==std::size(targets))for(;enabled<created;++enabled)if(MH_EnableHook(targets[enabled])!=MH_OK)break;
    if(enabled!=std::size(targets)){
        for(unsigned i=0;i<enabled;++i)MH_DisableHook(targets[i]);
        for(unsigned i=0;i<created;++i)MH_RemoveHook(targets[i]);
        return false;
    }
    return true;
}
}
void OpenDepthBridge(std::wstring const& prefix,std::filesystem::path const& folder,bool test){
    std::lock_guard lock(state);logs=folder;offline=test;
    mapping=CreateFileMappingW(INVALID_HANDLE_VALUE,nullptr,PAGE_READWRITE,0,sizeof(DepthShared),(prefix+L"-depth").c_str());
    if(!mapping||GetLastError()==ERROR_ALREADY_EXISTS){if(mapping)CloseHandle(mapping);mapping=nullptr;return;}
    gate=CreateMutexW(nullptr,FALSE,(prefix+L"-depth-lock").c_str());
    if(!gate||GetLastError()==ERROR_ALREADY_EXISTS){if(gate)CloseHandle(gate);gate=nullptr;CloseHandle(mapping);mapping=nullptr;return;}
    shared=static_cast<DepthShared*>(MapViewOfFile(mapping,FILE_MAP_WRITE,0,0,sizeof(DepthShared)));
    if(!shared){CloseHandle(mapping);CloseHandle(gate);mapping=gate=nullptr;return;}
    shared->request=DepthRequest{};
}
void EnableDepthBridge(bool value,std::uintptr_t base){
    std::lock_guard lock(state);
    if(value&&shared&&!offline&&!attempted){attempted=true;game=base;installed=Install();
        Log(logs,installed?"DEPTH READY exact classic D3D11 read-only ROI capture; awaiting camera/target match":"DEPTH REFUSED backend/API hook ownership; voxel clipping retained");}
    active=value&&installed;
}
void DepthCamera(WorldPacket const& p,float const* renderView){
    std::unique_lock lock(state,std::try_to_lock);if(lock.owns_lock()&&active)gpu.camera(p,renderView);
}
void RefreshDepthBridge(){
    std::lock_guard lock(state);auto now=GetTickCount64();auto r=Request();requested=r;
    requestUntil.store(active&&ValidDepthRequest(r,now)?r.at+250:0,std::memory_order_relaxed);
}
void LogDepthBridge(){std::lock_guard lock(state);if(installed){auto d=gpu.diagnostics();
    Log(logs,"DEPTH draws="+std::to_string(draws)+" clears="+std::to_string(clears)+" submitted="+std::to_string(submitted)+" published="+std::to_string(published)+" transportBusy="+std::to_string(busy)
        +" completed="+std::to_string(d.completed)+" cameraMiss="+std::to_string(d.cameraMiss)+" stale="+std::to_string(d.stale)+" invalidJitter="+std::to_string(d.jitterInvalid)
        +" older="+std::to_string(d.older)+" queryPendingPolls="+std::to_string(d.queryPendingPolls)+" mapBusy="+std::to_string(d.mapBusy)
        +" pairedCameras="+std::to_string(d.pairedCameras)+" unpairedCameras="+std::to_string(d.unpairedCameras)+" alternativeBindingMatches="+std::to_string(d.alternativeBindingMatches)+" finalBindingMatches="+std::to_string(d.finalBindingMatches)
        +" snapshotCopies="+std::to_string(d.snapshotCopies)+" snapshotOverflow="+std::to_string(d.snapshotOverflow)+" intermediateMatches="+std::to_string(d.intermediateMatches)
        +" cameraSamples="+std::to_string(d.cameraSamples)+" projectionError="+std::to_string(d.projectionError)+" basisError="+std::to_string(d.basisError)
        +" translationError="+std::to_string(d.translationError)+" clipW="+std::to_string(d.clipW));}}
void CloseDepthBridge(){active=false;requestUntil=0;std::lock_guard lock(state);gpu.reset();writes.reset();output={};
    if(shared)UnmapViewOfFile(shared);shared=nullptr;if(gate)CloseHandle(gate);if(mapping)CloseHandle(mapping);gate=mapping=nullptr;
}
}
