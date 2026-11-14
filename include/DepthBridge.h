#pragma once
#include "DepthGpu.h"
namespace mp {
struct DepthShared {
    DepthRequest request;
    std::uint64_t sequence{};
    WorldPacket camera;
    std::uint64_t at{};
    std::uint32_t viewportWidth{},viewportHeight{},x{},y{},width{},height{},valid{},sampleStep{};
    std::uint8_t rgba[DepthMaxPixels*4]{};
};
static_assert(offsetof(DepthShared,camera)==64&&offsetof(DepthShared,at)==320&&offsetof(DepthShared,rgba)==360);
void OpenDepthBridge(std::wstring const&,std::filesystem::path const&,bool offline);
void EnableDepthBridge(bool,std::uintptr_t gameBase);
void DepthCamera(WorldPacket const&,float const* pairedRenderView);
void RefreshDepthBridge();
void LogDepthBridge();
void CloseDepthBridge();
}
