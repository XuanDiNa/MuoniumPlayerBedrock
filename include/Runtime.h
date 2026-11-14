#pragma once
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <Windows.h>
#include <filesystem>
#include <string>
namespace mp {
bool EnsurePlayerConfig(std::filesystem::path const&) noexcept;
bool VerifyHost(std::filesystem::path const&);
HWND FindOwnerWindow(DWORD pid);
void Log(std::filesystem::path const&,std::string const&)noexcept;
int RunPlayer(HINSTANCE module,HWND owner,std::filesystem::path folder,bool preview=false,bool hidden=false);
}
