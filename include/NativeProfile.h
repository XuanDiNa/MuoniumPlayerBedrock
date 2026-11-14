#pragma once
#include <atomic>
#include <cstdint>
#include <string_view>
namespace mp {
enum class GameVersion { V2652, V2660, V2645, V2651 };
struct HostIdentity {
    GameVersion version;
    std::string_view name,sha256;
    std::uint64_t fileSize;
    std::uint32_t timestamp,imageSize;
};
inline constexpr HostIdentity HostIdentities[]{
    {GameVersion::V2652,"1.26.52.03","4A92BFA3CE2428B40EE517B7C1125D9F6F79274382DE03846351992663B2E2E4",311215984,0x6AB54E37,0x12C01000},
    {GameVersion::V2652,"1.26.52.03-launcher","0B66101DC873434E853A898329CB85CEE0DE52D0AE91AC796EFA0FE05B61522C",311218032,0x6AB54E37,0x12C02000},
    {GameVersion::V2660,"1.26.60.05","988F6A61403B4381AA41E6C66D64CCE441B14E561A9B10F647C2758EF3F2672A",323819336,0x6AD79EE4,0x1383B000},
    {GameVersion::V2660,"1.26.60.05-launcher","019F4EE7C82DB0E0C187653BC76C61CF75502FF6866A5CC6603FA0C909A386A2",323821384,0x6AD79EE4,0x1383C000},
    {GameVersion::V2660,"1.26.60.05-LeviLamina","807508E53ADE36497C30C95770016B9CABBC9DE9E7FA83130D28BA586B3C410F",323855872,0x6AD79EE4,0x13847000},
    {GameVersion::V2660,"1.26.60.05-LeviLamina-launcher","FAD0788DB090874B7BADC6353BBBCBD38FAC9172DA1F5D3F30771056E8A7A6A5",323855872,0x6AD79EE4,0x13847000},
    {GameVersion::V2660,"1.26.60.05-LeviLamina-player","B554A2DA8B2271A4A164AE544A4A447F3A630B5FC2261AFEFE47C7C206826A53",323855872,0x6AD79EE4,0x13847000},
    {GameVersion::V2645,"1.26.45.01","008408E6F544D7987F9CDE4E34C8E853690A65AFD97F8498DA2CD6ED24C4793B",307657080,0x6A8378BA,0x12888000},
    {GameVersion::V2645,"1.26.45.01-launcher","4143182F113D774062809039154248E7AF7578A81C92E54323F7D53361F8EFA1",307659128,0x6A8378BA,0x12889000},
    {GameVersion::V2651,"1.26.51.01","258EC563FD37B3EFCF67C2C5207AA0DF6AB8E20DC09ACF2394380D697CC24DAC",311249032,0x6AA482FD,0x12C0C000},
    {GameVersion::V2651,"1.26.51.01-launcher","E3BD6B85F5F37C7814B131BA4E0C412957397E2804B71871E6180C773F69605B",311251456,0x6AA482FD,0x12C0D000},
    // No-PreLoader forms: original eight section headers/bytes and all native
    // evidence match the reviewed 51 image. GUI/console hashes stay separate.
    {GameVersion::V2651,"1.26.51.01-vanilla-gui","537C0AEE2E79AFBDC94B44B28E00F466AE62BC50E2733D953B430DB9DBAA9EE7",311215952,0x6AA482FD,0x12C01000},
    {GameVersion::V2651,"1.26.51.01-vanilla-console","F3B58B0D0B21155382EB9B6F6DCE7228B8BCB5E23030CF22F05F81FBFC88786E",311215952,0x6AA482FD,0x12C01000},
    {GameVersion::V2651,"1.26.51.01-vanilla-launcher-gui","A249F54D7CF3B98580B2C1F52F97FA81578DDCA2EFFAE484CF977ED038814C21",311218000,0x6AA482FD,0x12C02000},
    {GameVersion::V2651,"1.26.51.01-vanilla-launcher-console","A7E1700A3A855D380368D8841460258EA3AF8B68417772CA11918057E222A891",311218000,0x6AA482FD,0x12C02000},
    // Console capture before/after launcher import rebuilding produces two
    // checksums. All eight original sections, entrypoint and native evidence
    // match; enumerate complete files without normalizing any runtime header.
    {GameVersion::V2660,"1.26.60.05-console","E34D9DBB574790CAB5293E8B2C9C31A696CD58287281C8424255C6CF5045C40B",323819336,0x6AD79EE4,0x1383B000},
    {GameVersion::V2660,"1.26.60.05-launcher-console","DA6C84C32681A7CD7F82CF3573B08E23D629BB2E6E6EC73AD44DE098248FCE63",323821384,0x6AD79EE4,0x1383C000},
    {GameVersion::V2660,"1.26.60.05-console-capture-launcher-gui","537A7BD5AB1BEB109BD9D2E5206B6DF35BF273D01717EF9C5B20EC4B68CC3017",323821384,0x6AD79EE4,0x1383C000},
    {GameVersion::V2660,"1.26.60.05-console-capture-launcher-console","68B38A0B3FD2A57C4538DBFAC5BEACE14AFD774513E2CBF1BE4443E9DE010768",323821384,0x6AD79EE4,0x1383C000}
};
constexpr HostIdentity const* MatchHostIdentity(std::string_view digest,std::uint64_t size) noexcept {
    for(auto const& p:HostIdentities)if(p.sha256==digest&&p.fileSize==size)return &p;
    return nullptr;
}
constexpr bool MatchLoadedIdentity(HostIdentity const* identity,std::uint16_t machine,std::uint32_t timestamp,std::uint32_t size) noexcept {
    return identity&&machine==0x8664&&identity->timestamp==timestamp&&identity->imageSize==size;
}
// Set only after full file hashing. A timestamp alone never selects a profile.
inline std::atomic<HostIdentity const*> VerifiedHostIdentity{};
}
