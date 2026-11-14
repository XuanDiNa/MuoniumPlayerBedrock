#pragma once
#include <Windows.h>
#include <cstddef>
#include <filesystem>
namespace mp {
void EnableD3D12Bridge(bool,std::byte*,HANDLE,HWND,std::filesystem::path const&);
void CloseD3D12Bridge();
void LogD3D12Bridge();
}
