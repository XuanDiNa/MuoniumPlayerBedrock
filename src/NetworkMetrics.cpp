#include "Runtime.h"
#include "NativeContracts.h"
#include <span>
#include "GameMetrics.h"
#include <MinHook.h>
#include <array>
#include <atomic>
#include <cstring>
#include <mutex>
namespace mp {
namespace {
NativeContract const* native{};
struct Guard {std::uint32_t rva;std::array<unsigned char,32> bytes;};
#include "NetworkGuards.inl"
#include "NetworkGuards2660.inl"
#include "NetworkGuards2645.inl"
#include "NetworkGuards2651.inl"
std::uintptr_t base{},textLo{},textHi{},dataLo{},dataHi{};
std::atomic<std::uintptr_t> selected{};
std::atomic<std::uint64_t> selectionEpoch{1};
std::uint64_t stateSelectionEpoch{}; // protected by stateMutex
std::atomic<bool> ready{};
std::mutex stateMutex;
ConnectionMetrics state;
std::atomic<std::uint64_t> networkUpdates{},selectedUpdates{},peerUpdates{},snapshots{};
using NetworkUpdate=void(*)(void*,void*);
using PeerUpdate=void(*)(void*);
NetworkUpdate networkOriginal{};
PeerUpdate peerOriginal{},rakOriginal{};
struct Batch {std::uintptr_t peer{};unsigned depth{},count{};bool local{},sampled{};int ping{-1};};
thread_local Batch* current{};
struct BatchScope {Batch* previous;explicit BatchScope(Batch* b):previous(current){current=b;}~BatchScope(){current=previous;}};
struct DepthScope {unsigned& depth;explicit DepthScope(unsigned& d):depth(d){++depth;}~DepthScope(){--depth;}};
template<class T>T Read(void const* p,std::size_t offset=0){T out;std::memcpy(&out,static_cast<std::byte const*>(p)+offset,sizeof out);return out;}
void* Method(void* self,std::size_t slot){
    if(!self)return nullptr;auto table=Read<std::uintptr_t>(self);
    if(table<dataLo||table+slot+8>dataHi)return nullptr;
    auto fn=Read<std::uintptr_t>(reinterpret_cast<void*>(table),slot);
    return fn>=textLo&&fn<textHi?reinterpret_cast<void*>(fn):nullptr;
}
bool Sample(void* self,bool& local,int& ping) noexcept {
    // The object is owned by the original NetworkSystem update stack. Calls
    // are read-only virtual NetworkPeer methods with the exact-image ABI.
    __try {
        auto isLocal=Method(self,0x28);auto status=Method(self,0x10);
        if(!isLocal||!status)return false;
        local=reinterpret_cast<bool(*)(void*)>(isLocal)(self);
        if(local){ping=0;return true;}
        struct alignas(8) Status {std::uint32_t load,pad;std::int64_t current,average;std::byte tail[48];};
        static_assert(sizeof(Status)==72);
        Status out{};out.current=out.average=-1;
        auto returned=reinterpret_cast<Status*(*)(void*,Status*)>(status)(self,&out);
        if(returned!=&out)return false;
        ping=out.current>=0&&out.current<65535?static_cast<int>(out.current):-1;
        return true;
    }__except(EXCEPTION_EXECUTE_HANDLER){return false;}
}
void UpdatedPeer(void* self,PeerUpdate original){
    auto b=current;
    if(!b){original(self);return;}
    const bool outer=b->depth==0;
    {DepthScope depth(b->depth);original(self);}
    if(!outer)return;
    peerUpdates.fetch_add(1,std::memory_order_relaxed);
    ++b->count;
    bool local{};int ping=-1;
    if(Sample(self,local,ping)){
        b->sampled=true;b->peer=reinterpret_cast<std::uintptr_t>(self);b->local=local;b->ping=ping;
    }
}
void PeerDetour(void* self){UpdatedPeer(self,peerOriginal);}
void RakDetour(void* self){UpdatedPeer(self,rakOriginal);}
void NetworkDetour(void* self,void* users){
    networkUpdates.fetch_add(1,std::memory_order_relaxed);
    const auto owner=reinterpret_cast<std::uintptr_t>(self);
    const auto ticket=selectionEpoch.load(std::memory_order_acquire);
    Batch batch{};
    {BatchScope scope(ready.load(std::memory_order_acquire)&&selected.load(std::memory_order_acquire)==owner?&batch:nullptr);
     networkOriginal(self,users);}
    if(!ready.load(std::memory_order_acquire)||selected.load(std::memory_order_acquire)!=owner||selectionEpoch.load(std::memory_order_acquire)!=ticket)return;
    selectedUpdates.fetch_add(1,std::memory_order_relaxed);
    std::unique_lock lock(stateMutex,std::try_to_lock);if(!lock.owns_lock())return;
    if(selected.load(std::memory_order_acquire)!=owner||selectionEpoch.load(std::memory_order_acquire)!=ticket)return;
    if(stateSelectionEpoch!=ticket){state.reset();stateSelectionEpoch=ticket;}
    // Client-side game connection must resolve to one peer. Never choose a
    // random player connection in a host/server NetworkSystem.
    if(batch.count==1&&batch.sampled){state.observe(owner,batch.peer,batch.local,batch.ping,GetTickCount64());snapshots.fetch_add(1,std::memory_order_relaxed);}
    else state.reset();
}
bool CheckImage(std::uintptr_t image) noexcept {
    __try {
        base=image;native=LoadedNativeContract(base);if(!native)return false;auto dos=reinterpret_cast<IMAGE_DOS_HEADER*>(base);
        auto nt=reinterpret_cast<IMAGE_NT_HEADERS64*>(base+dos->e_lfanew);
        if(dos->e_magic!=IMAGE_DOS_SIGNATURE||nt->Signature!=IMAGE_NT_SIGNATURE)return false;
        auto sections=IMAGE_FIRST_SECTION(nt);
        for(unsigned i=0;i<nt->FileHeader.NumberOfSections;++i){
            if(!std::memcmp(sections[i].Name,".text",5)){textLo=base+sections[i].VirtualAddress;textHi=textLo+sections[i].Misc.VirtualSize;}
            if(!std::memcmp(sections[i].Name,".rdata",6)){dataLo=base+sections[i].VirtualAddress;dataHi=dataLo+sections[i].Misc.VirtualSize;}
        }
        if(!textLo||!dataLo)return false;
        for(auto const& g:VersionGuards(native->version,networkGuards2645,networkGuards2651,networkGuards,networkGuards2660))if(std::memcmp(reinterpret_cast<void*>(base+g.rva),g.bytes.data(),g.bytes.size()))return false;
        return true;
    }__except(EXCEPTION_EXECUTE_HANDLER){return false;}
}
}
bool InstallNetworkMetrics(std::uintptr_t image){
    if(!CheckImage(image))return false;
    struct Hook {std::uint32_t rva;void* detour;void** original;};
    Hook hooks[]={{native->networkUpdate,reinterpret_cast<void*>(&NetworkDetour),reinterpret_cast<void**>(&networkOriginal)},
                  {native->peerUpdate,reinterpret_cast<void*>(&PeerDetour),reinterpret_cast<void**>(&peerOriginal)},
                  {native->rakUpdate,reinterpret_cast<void*>(&RakDetour),reinterpret_cast<void**>(&rakOriginal)}};
    unsigned created=0;
    for(auto const& h:hooks){
        if(MH_CreateHook(reinterpret_cast<void*>(base+h.rva),h.detour,h.original)!=MH_OK){
            for(unsigned i=0;i<created;++i)MH_RemoveHook(reinterpret_cast<void*>(base+hooks[i].rva));
            return false;
        }
        ++created;
    }
    // Enable only our own targets. Do not apply a process-global hook queue.
    // On partial enable failure, ready stays false and installed detours only
    // forward. The pinned DLL keeps their trampolines alive.
    for(auto const& h:hooks)if(MH_EnableHook(reinterpret_cast<void*>(base+h.rva))!=MH_OK)return false;
    ready.store(true,std::memory_order_release);return true;
}
void SelectNetworkMetrics(std::uintptr_t owner){
    if(selected.exchange(owner,std::memory_order_acq_rel)==owner)return;
    auto ticket=selectionEpoch.fetch_add(1,std::memory_order_acq_rel)+1;
    std::unique_lock lock(stateMutex,std::try_to_lock);if(lock.owns_lock()){state.reset();stateSelectionEpoch=ticket;}
}
MetricSnapshot ReadNetworkMetrics(std::uint64_t now,std::uintptr_t owner){
    if(!ready.load(std::memory_order_acquire)||selected.load(std::memory_order_acquire)!=owner)return {};
    std::unique_lock lock(stateMutex,std::try_to_lock);
    if(!lock.owns_lock()||stateSelectionEpoch!=selectionEpoch.load(std::memory_order_acquire)||selected.load(std::memory_order_acquire)!=owner)return {};
    return state.snapshot(now,owner);
}
MetricDiagnostics ReadMetricDiagnostics() noexcept {
    return {networkUpdates.load(std::memory_order_relaxed),selectedUpdates.load(std::memory_order_relaxed),peerUpdates.load(std::memory_order_relaxed),snapshots.load(std::memory_order_relaxed)};
}
}
