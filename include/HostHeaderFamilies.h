#pragma once
#include "NativeProfile.h"
namespace mp {
// Whole-file SHA-256 with exactly CheckSum (4 bytes) and Subsystem (2 bytes)
// zeroed in a private hashing buffer. No on-disk or loaded image is changed.
// Each fingerprint is generated only from an exact reviewed HostIdentity.
struct HostHeaderFamily {unsigned identityIndex;std::string_view sha256;};
inline constexpr HostHeaderFamily HostHeaderFamilies[]{
    {8,"D820230B9A31C199AA4E37CE90D102C7195E27D3A450401844EC0C6CE66F1D5F"},
    {7,"B8A036375B85003F12BCDFB2CA2C3B36D81851F99DE3EC30E259189AA25B6911"},
    {14,"EBDDDBEDDE5B1C43F86F3923A99C1227946F09DD81641EE0FEB263658013DB2A"},
    {12,"21934948D5DD699F51F75EE7E23CC91661A3500A0E6BB7CCCC010D9F07AFF9E7"},
    {1,"D4A8955AB2EDACF27D9630A3E07CF126A96D2EF03081FA0FC7D016CCC66B6F1C"},
    {0,"2B81CFFF1CBBABBEE75DC0CC315970B17470A62B0D5A70915069E159006F3ABC"},
    {18,"E2AC7687BF82AE741FABCF9D152C508BB261B6604BDC18AF5D61248D0412F247"},
    {2,"08A931A86949961525855B2D5D6CDEA3D76DE3186BF3DF540A87A6D878A673EB"},
    {6,"58779BCB1E89BB2EC591BB61BEF29015F42853E5BC42B9C16B538DB77CFE0328"}
};
constexpr bool CanHashHostHeaderVariant(std::uint64_t size,std::uint16_t machine,std::uint32_t timestamp,std::uint32_t imageSize,std::uint16_t subsystem) noexcept{
    if(subsystem!=2&&subsystem!=3)return false;
    for(auto const& family:HostHeaderFamilies){
        auto const& identity=HostIdentities[family.identityIndex];
        if(identity.fileSize==size&&MatchLoadedIdentity(&identity,machine,timestamp,imageSize))return true;
    }
    return false;
}
constexpr HostIdentity const* MatchHostHeaderVariant(std::string_view digest,std::uint64_t size,std::uint16_t machine,std::uint32_t timestamp,std::uint32_t imageSize,std::uint16_t subsystem) noexcept{
    if(subsystem!=2&&subsystem!=3)return nullptr;
    for(auto const& family:HostHeaderFamilies){
        auto const& identity=HostIdentities[family.identityIndex];
        if(family.sha256==digest&&identity.fileSize==size&&MatchLoadedIdentity(&identity,machine,timestamp,imageSize))return &identity;
    }
    return nullptr;
}
}
