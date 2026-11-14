#define WIN32_LEAN_AND_MEAN
#include <Windows.h>
#include <malloc.h>
#include <atomic>
#define MPB_ALLOCATOR_STUB
#include "MemoryImports.h"
namespace {
std::atomic<unsigned> allocated{},released{},alignedAllocated{},alignedReleased{};
std::atomic<bool> refuse{};
class Allocator final:public Bedrock::Memory::IMemoryAllocator {
    void* _allocate(uint64 n) override{++allocated;return refuse?nullptr:HeapAlloc(GetProcessHeap(),0,static_cast<SIZE_T>(n?n:1));}
    void _release(gsl::not_null<void*> p) override{++released;HeapFree(GetProcessHeap(),0,p.get());}
    void* _alignedAllocate(uint64 n,uint64 a) override{++alignedAllocated;return refuse?nullptr:_aligned_malloc(static_cast<size_t>(n?n:1),static_cast<size_t>(a));}
    void _alignedRelease(gsl::not_null<void*> p) override{++alignedReleased;_aligned_free(p.get());}
    uint64 _getUsableSize(gsl::not_null<void*> p,bool a) override{return a?0:HeapSize(GetProcessHeap(),0,p.get());}
    void* _realloc(gsl::not_null<void*> p,uint64 n) override{return HeapReAlloc(GetProcessHeap(),0,p.get(),static_cast<SIZE_T>(n));}
    void* _alignedRealloc(gsl::not_null<void*> p,uint64 n,uint64 a) override{return _aligned_realloc(p.get(),static_cast<size_t>(n),static_cast<size_t>(a));}
} allocator;
}
namespace ll::memory {
Bedrock::Memory::IMemoryAllocator& getDefaultAllocator(){return allocator;}
[[noreturn]] void throwMemoryException(std::size_t){throw std::bad_alloc();}
}
extern "C" __declspec(dllexport) unsigned MPB_AllocatorCount(unsigned kind){return kind==0?allocated.load():kind==1?released.load():kind==2?alignedAllocated.load():alignedReleased.load();}
extern "C" __declspec(dllexport) void MPB_AllocatorRefuse(bool value){refuse=value;}
