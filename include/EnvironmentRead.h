#pragma once
#include <cstddef>
#include <cstdint>
namespace mp {
using EnvironmentRead=bool(*)(void*,std::uintptr_t,void*,std::size_t);
bool ReadEnvironmentHealth(std::uintptr_t actor,std::uintptr_t attribute,EnvironmentRead,void*,float&,std::size_t attributeStride=136) noexcept;
bool ReadEnvironmentActorType(std::uintptr_t actor,EnvironmentRead,void*,std::uint32_t&) noexcept;
struct EnvironmentEntityCounts {int enemies{},animals{},villagers{};};
bool EnvironmentEntityBox(float const (&actorBox)[6],float (&queryBox)[6]) noexcept;
enum class EnvironmentEntityStatus {Known,Invalid,Limit,Budget};
using EnvironmentEntityClock=std::uint64_t(*)(void*);
EnvironmentEntityStatus ReadEnvironmentEntities(std::uintptr_t entries,std::size_t count,
    std::uintptr_t except,std::uintptr_t healthAttribute,EnvironmentRead,void*,
    EnvironmentEntityClock,void*,std::uint64_t budget,EnvironmentEntityCounts&,std::size_t removedOffset=0x269,std::size_t attributeStride=136) noexcept;
std::uint8_t EnvironmentSurface(char const*,std::size_t) noexcept;
int EnvironmentBlockClue(char const*,std::size_t) noexcept;
}
