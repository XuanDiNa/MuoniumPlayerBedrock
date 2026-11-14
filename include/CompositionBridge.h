#pragma once
#include "Composition.h"
#include <Windows.h>
namespace mp {
bool OpenCompositionBridge(std::wstring const&,std::filesystem::path const&,HWND,bool offline);
void EnableCompositionBridge(bool,std::uintptr_t);
bool CompositionEnabled();
void CompositionCamera(WorldPacket const&,float const* paired);
void LogCompositionBridge();
void CloseCompositionBridge();
}
