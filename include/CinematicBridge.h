#pragma once
#include "Cinematic.h"
#include <windows.h>
#include <filesystem>
#include <string>
namespace mp {
bool OpenCinematicBridge(std::wstring const&,std::filesystem::path const&,bool offline);
void SetCinematicOwner(HWND);
bool EnableCinematicBridge(bool,std::uintptr_t image);
void CloseCinematicBridge();
void LogCinematicBridge();
// Existing exact WorldBridge HUD predicate; called synchronously on game owner.
bool CinematicHud(void* client) noexcept;
}
