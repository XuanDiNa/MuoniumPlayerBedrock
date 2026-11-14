#pragma once
#include "WorldBridge.h"
#include <array>
#include <cstddef>
#include <vector>
namespace mp {
inline constexpr unsigned CompositionAtlas=2048,CompositionPlanes=32;
inline constexpr std::size_t CompositionScreenBytes=16777216ull*4;
inline constexpr std::size_t CompositionAtlasBytes=CompositionAtlas*CompositionAtlas*4ull;
// API-independent route: a probe may initialize resources but never draw over
// the external renderer. The pixel/plane mailbox also serves a future D3D12 backend.
inline constexpr std::uint32_t CompositionProbeOnly=1;
inline constexpr unsigned CompositionBands=4;
struct CompositionBand {std::uint32_t x{},y{},width{},height{},offsetY{};};
static_assert(sizeof(CompositionBand)==20);
struct CompositionPlane {
    double position[3]{};
    float right[3]{},down[3]{},bounds[4]{};
    std::uint32_t tile[4]{};
};
static_assert(sizeof(CompositionPlane)==80);
struct CompositionHeader {
    std::uint32_t magic{0x5043504d},version{1};
    std::uint64_t sequence{},at{},epoch{};
    std::int32_t dimension{};
    std::uint32_t active{},viewportWidth{},viewportHeight{},x{},y{},width{},height{},planeCount{},atlasHeight{},occlusion{};
    std::uint32_t reserved{};
    CompositionPlane planes[CompositionPlanes]{};
    std::uint32_t bandCount{};
    CompositionBand bands[CompositionBands]{};
};
static_assert(offsetof(CompositionHeader,planes)==80&&offsetof(CompositionHeader,bandCount)==2640&&sizeof(CompositionHeader)<=3072);
inline constexpr std::size_t CompositionBytes=4096+CompositionScreenBytes+CompositionAtlasBytes;
struct CompositionFrame {CompositionHeader header;std::vector<std::uint8_t> screen,atlas;};
bool ValidComposition(CompositionHeader const&,std::uint64_t now) noexcept;
}
