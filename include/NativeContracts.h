#pragma once
#include "NativeProfile.h"
#include <span>
namespace mp {
struct NativeContract {
    GameVersion version;
    std::uint32_t clientTable,levelTable,sourceTable,playerTable,biomeTable,sceneTable;
    std::uint32_t preRender,hudPredicate,getLevel,getRegion,getPlayer,sourceDimension;
    std::uint32_t readyChunk,getBlock,extraBlock,light,skyLight,biome,getTime,entitySpan,healthAttribute;
    std::uint32_t frame,render,body,hand,saveCamera,renderContext,sceneSlot,sceneRender;
    std::uint32_t networkUpdate,peerUpdate,rakUpdate;
    std::uint32_t rendererOwner,rendererPlayer,rendererClient,clientRenderer,clientGame,gameNetwork,gameNetworkTag;
    std::uint32_t camera,actorAabb,actorRotation,actorRemoved;
    bool inlineMaterial;
    std::uint32_t hudVirtual{0x978},screenStack{0x5D0},sceneValidity{424},sceneClient{440},healthStride{136};
};
inline constexpr NativeContract Contract2652{
    GameVersion::V2652,
    0xE9731B0,0xE7ADAB0,0xE8554E0,0xE8E1BC0,0xE9A5270,0xE8E9080,
    0x46AA4A0,0x5DC0C80,0x5DC2630,0x5DAEBB0,0x5DAED30,0x2D7DDF0,
    0x2D7F440,0x2D80120,0x2D80450,0x2D812B0,0x2D81410,0x2D8CD80,0x11788B0,0x2D84510,0x11DC54F0,
    0x4BA840,0x4BDFB0,0x5DC4D40,0x4C4400,0x9F4EB0,0x9ED0A0,0xE8E9118,0x48576B0,
    0x16A9DC0,0x46EB40,0x1735F80,
    0x9A0,0x468,0x408,0x1C0,0x1B0,0xF0,0xF8,
    0x740,0x220,0x228,0x269,false
};
// Derived from the canonical original 988F6A61... DB. The original code
// sections also match both independently hashed loader images byte-for-byte.
inline constexpr NativeContract Contract2660{
    GameVersion::V2660,
    0xF2B9330,0xF2CE790,0xF188A20,0xF2DEF10,0xF27D060,0xF20EC60,
    0x535E960,0x65AFEB0,0x65B02F0,0x659E2F0,0x659E470,0x32CC490,
    0x32CD550,0x32CE440,0x32CE700,0x32D1190,0x32D1310,0x32DC640,0x232BA70,0x32D3E30,0x129714C8,
    0x4E090C0,0x4E0B0B0,0x65B2820,0x4E0F610,0xAA6820,0xA9EE60,0xF20ECF8,0x4CCF520,
    0x2377640,0x4BF1B0,0x22D8490,
    0x998,0x480,0x418,0x1E0,0x1D0,0xF8,0x100,
    0x738,0x240,0x248,0x289,true
};
// Independently reviewed old-image layouts; see docs/native-older-versions.md.
inline constexpr NativeContract Contract2645{
    GameVersion::V2645,
    0xE774DB0,0xE79E800,0xE7EF3A0,0xE820EC0,0xE7F3E48,0xE8C5010,
    0x314DAE0,0xD5E780,0xD60120,0xD4BCD0,0xD4BE50,0x2484E60,
    0x24860E0,0x2486DC0,0x24870F0,0x2488010,0x2488170,0x24941A0,0x1CA0730,0x248B5C0,0x11ADC9C8,
    0x4F3A70,0x4F71E0,0xD62920,0x4FD590,0x302C4A0,0x3024CB0,0xE8C50A8,0x4B1CB90,
    0x1704EB0,0x4CB760,0x1742DB0,
    0x9A0,0x468,0x408,0x1B8,0x1A8,0xE8,0xF0,
    0x740,0x220,0x228,0x269,false,
    0x970,0x5C8,376,392,128
};
inline constexpr NativeContract Contract2651{
    GameVersion::V2651,
    0xE9731B0,0xE7ADAB0,0xE8554E0,0xE8E1BC0,0xE9A5270,0xE8E9080,
    0x46AA3D0,0x5DC0BB0,0x5DC2560,0x5DAEAE0,0x5DAEC60,0x2D7DE40,
    0x2D7F490,0x2D80170,0x2D804A0,0x2D81300,0x2D81460,0x2D8CDD0,0x11788F0,0x2D84560,0x11DC54F0,
    0x4BA840,0x4BDFB0,0x5DC4C70,0x4C4400,0x9F4EF0,0x9ED0E0,0xE8E9118,0x48575E0,
    0x16AA0B0,0x46EB40,0x1736270,
    0x9A0,0x468,0x408,0x1C0,0x1B0,0xF0,0xF8,
    0x740,0x220,0x228,0x269,false
};
inline NativeContract const* SelectNativeContract(HostIdentity const* identity) noexcept{
    for(auto const& known:HostIdentities)if(identity==&known){
        switch(known.version){
        case GameVersion::V2645:return &Contract2645;
        case GameVersion::V2651:return &Contract2651;
        case GameVersion::V2652:return &Contract2652;
        case GameVersion::V2660:return &Contract2660;
        }
    }
    return nullptr;
}
template<class T,std::size_t A,std::size_t B,std::size_t C,std::size_t D>
constexpr std::span<T const> VersionGuards(GameVersion v,T const (&a)[A],T const (&b)[B],T const (&c)[C],T const (&d)[D]) noexcept{
    switch(v){
    case GameVersion::V2645:return a;
    case GameVersion::V2651:return b;
    case GameVersion::V2652:return c;
    case GameVersion::V2660:return d;
    }
    return {};
}
NativeContract const* LoadedNativeContract(std::uintptr_t) noexcept;
}
