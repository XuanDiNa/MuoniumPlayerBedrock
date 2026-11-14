#pragma once
#include <Windows.h>
#include <filesystem>
namespace mp {
// A short name can reuse an already-loaded app-local DLL even with SYSTEM32
// search flags. Use the absolute system path and verify the returned module.
inline HMODULE LoadSystemGraphicsLibrary(wchar_t const* name){
    if(!name||!*name||wcspbrk(name,L"\\/:")){SetLastError(ERROR_INVALID_NAME);return nullptr;}
    wchar_t directory[32768]{};auto length=GetSystemDirectoryW(directory,32768);
    if(!length)return nullptr;if(length>=32768){SetLastError(ERROR_INSUFFICIENT_BUFFER);return nullptr;}
    auto path=(std::filesystem::path(directory)/name).lexically_normal().wstring();
    auto module=LoadLibraryExW(path.c_str(),nullptr,LOAD_LIBRARY_SEARCH_SYSTEM32);if(!module)return nullptr;
    wchar_t resolved[32768]{};auto actual=GetModuleFileNameW(module,resolved,32768);
    if(!actual||actual>=32768||_wcsicmp(path.c_str(),resolved)){
        FreeLibrary(module);SetLastError(ERROR_INVALID_DLL);return nullptr;
    }
    return module;
}
}
