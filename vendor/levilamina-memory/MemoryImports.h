#pragma once
#include <new>
#include "IMemoryAllocator.h"
#define LL_SHARED_EXPORT __declspec(dllexport)
#define LL_ALLOCATOR __declspec(allocator)
#define LL_NOTHROW_ALLOCATOR __declspec(allocator) __declspec(nothrow)
namespace ll::memory {
#ifdef MPB_ALLOCATOR_STUB
__declspec(dllexport) Bedrock::Memory::IMemoryAllocator& getDefaultAllocator();
[[noreturn]] __declspec(dllexport) void throwMemoryException(std::size_t);
#else
__declspec(dllimport) Bedrock::Memory::IMemoryAllocator& getDefaultAllocator();
[[noreturn]] __declspec(dllimport) void throwMemoryException(std::size_t);
#endif
}
