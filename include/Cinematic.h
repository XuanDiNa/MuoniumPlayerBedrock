#pragma once
#include <cstdint>
#include <cstddef>
#include <array>
namespace mp {
struct CinematicState {
    std::uint32_t magic{0x5350434d},version{1};
    std::uint64_t sequence{},at{},epoch{},input{};
    std::uint32_t eligible{},applied{};
    double foot[3]{},eye[3]{};
    float yaw{},pitch{},fov{},health{};
};
struct CinematicCommand {
    std::uint32_t magic{0x4350434d},version{1};
    std::uint64_t sequence{},at{},epoch{},input{};
    std::uint32_t active{},flags{};
    double position[3]{};
    float yaw{},pitch{},roll{},fovOffset{};
};
static_assert(sizeof(CinematicState)==112&&offsetof(CinematicState,foot)==48);
static_assert(sizeof(CinematicCommand)==88&&offsetof(CinematicCommand,position)==48);
bool ValidCinematicCommand(CinematicCommand const&,CinematicState const&,std::uint64_t now) noexcept;
// Native-owned values only. A missing receipt represents a nonblocking IPC miss.
class CinematicCommandCache {
    CinematicCommand last_{};
public:
    bool select(CinematicCommand const* receipt,CinematicState const&,std::uint64_t now,CinematicCommand& out) noexcept;
    void clear() noexcept {last_={};}
};
bool CinematicInputChanged(CinematicState const&,CinematicState const&) noexcept;
using CinematicRead=bool(*)(void*,std::uintptr_t,void*,std::size_t);
bool CinematicMember(CinematicRead,void*,std::uintptr_t registry,std::uint32_t token,std::uint32_t key,std::size_t stride,std::uintptr_t& value) noexcept;
void CinematicForward(float const (&q)[4],float (&out)[3]) noexcept;
// Own only the scalar fields written by the adapter, never an engine object.
class CinematicPatch {
    std::array<std::byte,96> before_{},written_{};bool applied_{};
public:
    bool apply(void* component,CinematicCommand const&) noexcept;
    bool restore(void* component) noexcept;
};
}
