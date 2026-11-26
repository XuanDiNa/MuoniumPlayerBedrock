#pragma once
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <Windows.h>
#include <cstdint>
#include <filesystem>
#include <string>
namespace mp {
bool EnsurePlayerConfig(std::filesystem::path const&) noexcept;
struct HostVerification {
    char const* reason{"not-run"};
    DWORD win32{};
    std::uint32_t cryptoStatus{};
    std::uint64_t bytes{};
    std::string sha256;
    bool peValid{};
    WORD machine{},subsystem{};
    DWORD timestamp{},imageSize{};
};
bool VerifyHost(std::filesystem::path const&,HostVerification* report=nullptr);
std::string FormatHostVerification(HostVerification const&);
HWND FindOwnerWindow(DWORD pid);
void Log(std::filesystem::path const&,std::string const&)noexcept;
int RunPlayer(HINSTANCE module,HWND owner,std::filesystem::path folder,bool preview=false,bool hidden=false);
}
