#define WIN32_LEAN_AND_MEAN
#include <Windows.h>
#include <new>
#include <cstdint>
#include <cstdio>
#include "MemoryImports.h"
int main(){
    (void)ll::memory::getDefaultAllocator();
    auto module=GetModuleHandleW(L"LeviLamina.dll");if(!module)return 1;
    auto count=reinterpret_cast<unsigned(*)(unsigned)>(GetProcAddress(module,"MPB_AllocatorCount"));
    auto refuse=reinterpret_cast<void(*)(bool)>(GetProcAddress(module,"MPB_AllocatorRefuse"));if(!count||!refuse)return 2;
    auto normal=count(0),release=count(1),aligned=count(2),alignedRelease=count(3);
    void* p=::operator new(127);if(!p||count(0)!=normal+1)return 3;::operator delete(p,std::size_t(127));if(count(1)!=release+1)return 4;
    p=::operator new[](128,std::align_val_t(64));if(!p||reinterpret_cast<std::uintptr_t>(p)%64||count(2)!=aligned+1)return 5;
    ::operator delete[](p,std::align_val_t(64));if(count(3)!=alignedRelease+1)return 6;
    refuse(true);if(::operator new(23,std::nothrow)!=nullptr)return 7;
    bool caught=false;try{p=::operator new(23);}catch(std::bad_alloc const&){caught=true;}refuse(false);
    if(!caught)return 8;
    std::puts("Official unified allocator imports, ordinary/sized/aligned/array and OOM paths passed");return 0;
}
