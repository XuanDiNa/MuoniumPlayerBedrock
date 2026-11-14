#pragma once
#include "Runtime.h"
#include <string_view>
#include <atomic>
namespace mp {
std::wstring QuoteJavaArg(std::wstring_view value);
// previewRoot is supplied only by the independent developer smoke executable.
int RunJvmPlayer(HINSTANCE module, HWND owner, std::filesystem::path const& folder,
                 bool offlineSmoke = false,std::atomic<bool> const* stopRequested = nullptr);
}
