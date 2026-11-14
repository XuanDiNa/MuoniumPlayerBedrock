#pragma once
#include <cstdint>
#include <filesystem>
#include <string>
namespace mp {
// Private owned transport. No game pointer is exported to the companion.
bool OpenWorldBridge(std::wstring const& prefix, std::filesystem::path const& folder, bool offline);
void EnableWorldBridge(bool enabled);
void PollWorldBridge();
void CloseWorldBridge();
// Synchronous owned copy from the exact pre-render argument; no pointer retained.
bool CopyRenderViewForDepth(void* screenContext,float* out) noexcept;
struct WorldPacket {
    std::uint32_t magic{0x4357504d}, version{2};
    std::uint64_t sequence{}, at{}, epoch{};
    std::uint32_t valid{};
    std::int32_t dimension{};
    double eye[3]{};
    float view[16]{}, projection[16]{};
    double look[3]{}, fov{};
    std::uint32_t metricsVersion{1};
    std::int32_t fps{-1}, ping{-1};
    std::uint32_t connectionKind{};
    std::uint64_t metricsAt{}, networkEpoch{};
};
static_assert(sizeof(WorldPacket)==256);
static_assert(offsetof(WorldPacket,view)==64 && offsetof(WorldPacket,look)==192);
static_assert(offsetof(WorldPacket,metricsVersion)==224 && offsetof(WorldPacket,metricsAt)==240);
bool ValidWorldCamera(WorldPacket const&) noexcept;
}
