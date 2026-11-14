#pragma once
#include "WorldBridge.h"
#include <array>
#include <cstddef>
namespace mp {
using EnvironmentBounds=std::array<std::int16_t,6>;
struct EnvironmentCell {
    std::uint8_t material{255};
    EnvironmentBounds bounds{0,0,0,4096,4096,4096};
};
int EnvironmentTimeOfDay(int time) noexcept;
bool CopyEnvironmentKey(char (&out)[64],char const* text,std::size_t size) noexcept;
// Fixed owned values only. Kept separate from the accepted camera v2 ABI.
struct EnvironmentPacket {
    std::uint32_t magic{0x4556504d},version{2};
    std::uint64_t sequence{},at{},epoch{};
    std::uint32_t valid{};std::int32_t dimension{},bx{},by{},bz{},side{33};
    double eye[3]{},look[3]{};
    std::int32_t time{6000},shapeCount{2};
    float rain{},thunder{},light{15},sky{15},health{1};
    std::int32_t enemies{},animals{},villagers{};
    std::uint32_t known{32|128},water{};
    char biome[64]{};std::int32_t blockClue{};std::byte padding[36]{};
    std::array<std::uint8_t,33*33*33> cells{};
    std::array<std::uint8_t,33*33*33> shapeIds{};
    std::array<EnvironmentBounds,256> shapes{{{0,0,0,4096,4096,4096},{}}};
};
static_assert(sizeof(EnvironmentPacket)==75208 && offsetof(EnvironmentPacket,cells)==256);
static_assert(offsetof(EnvironmentPacket,shapeIds)==36193 && offsetof(EnvironmentPacket,shapes)==72130);
static_assert(offsetof(EnvironmentPacket,blockClue)==216);
static_assert(offsetof(EnvironmentPacket,eye)==56 && offsetof(EnvironmentPacket,known)==144);
class EnvironmentScan {
    EnvironmentPacket packet_{};std::size_t cursor_{};bool started_{};std::uint64_t startedAt_{};
public:
    using CellReader=EnvironmentCell(*)(void*,int,int,int);
    using Clock=std::uint64_t(*)(void*);
    void clear() noexcept;
    void start(WorldPacket const&,std::uint64_t epoch,std::uint64_t now) noexcept;
    bool restart(WorldPacket const&,std::uint64_t epoch,std::uint64_t now) const noexcept;
    bool step(CellReader,void*,Clock,void*,unsigned cellBudget,std::uint64_t clockBudget);
    bool complete() const noexcept{return started_&&cursor_==packet_.cells.size();}
    EnvironmentPacket& packet() noexcept{return packet_;}
};
bool OpenEnvironmentBridge(std::wstring const& prefix,std::filesystem::path const& folder);
bool VerifyEnvironmentNative(std::uintptr_t base) noexcept;
void CaptureEnvironment(void* source,void* client,WorldPacket const& world,std::uint64_t epoch,std::uint64_t now);
void ClearEnvironment();
void CloseEnvironmentBridge();
void LogEnvironment();
}
