#include "Runtime.h"
#include "CinematicLaminaHooks.h"
#include <bcrypt.h>
#include <string_view>
namespace mp {
namespace {
bool ModuleFile(HMODULE module,std::uint64_t bytes,std::string_view expected) {
    wchar_t path[32768]{};auto count=GetModuleFileNameW(module,path,32768);
    if(!count||count>=32768)return false;
    struct File{HANDLE value{INVALID_HANDLE_VALUE};~File(){if(value!=INVALID_HANDLE_VALUE)CloseHandle(value);}} file;
    file.value=CreateFileW(path,GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr);
    LARGE_INTEGER size{};if(file.value==INVALID_HANDLE_VALUE||!GetFileSizeEx(file.value,&size)||size.QuadPart<0||static_cast<std::uint64_t>(size.QuadPart)!=bytes)return false;
    struct Hash{BCRYPT_ALG_HANDLE alg{};BCRYPT_HASH_HANDLE hash{};~Hash(){if(hash)BCryptDestroyHash(hash);if(alg)BCryptCloseAlgorithmProvider(alg,0);}} h;
    if(BCryptOpenAlgorithmProvider(&h.alg,BCRYPT_SHA256_ALGORITHM,nullptr,0)<0||BCryptCreateHash(h.alg,&h.hash,nullptr,0,nullptr,0,0)<0)return false;
    std::array<unsigned char,65536> buffer{};std::uint64_t total{};DWORD got{};
    for(;;){if(!ReadFile(file.value,buffer.data(),static_cast<DWORD>(buffer.size()),&got,nullptr))return false;if(!got)break;
        total+=got;if(total>bytes||BCryptHashData(h.hash,buffer.data(),got,0)<0)return false;}
    std::array<unsigned char,32> digest{};if(total!=bytes||BCryptFinishHash(h.hash,digest.data(),32,0)<0)return false;
    constexpr char hex[]="0123456789ABCDEF";char text[64]{};
    for(unsigned i=0;i<digest.size();++i){text[i*2]=hex[digest[i]>>4];text[i*2+1]=hex[digest[i]&15];}
    return std::string_view(text,sizeof text)==expected;
}
bool ExecutableOwner(void const* address,HMODULE owner) noexcept {
    MEMORY_BASIC_INFORMATION info{};return address&&VirtualQuery(address,&info,sizeof info)==sizeof info&&info.AllocationBase==owner
        &&info.State==MEM_COMMIT&&!(info.Protect&(PAGE_GUARD|PAGE_NOACCESS))&&(info.Protect&0xf0);
}
template<std::size_t N>bool OwnerPrefix(std::uintptr_t address,unsigned char const(&bytes)[N]) noexcept {
    __try{return !std::memcmp(reinterpret_cast<void*>(address),bytes,sizeof bytes);}
    __except(EXCEPTION_EXECUTE_HANDLER){return false;}
}
}
bool ResolveCinematicLaminaApi(CinematicLaminaApi& result) noexcept {
    result={};
    try{
        auto ll=GetModuleHandleW(L"LeviLamina.dll"),pl=GetModuleHandleW(L"PreLoader.dll");
        // The ABI, dispatcher and occupied Lamium target were reviewed as one
        // exact combination. Unknown libraries cannot waive a native guard.
        if(!ll||!pl||!ModuleFile(ll,4786688,"D2BB36559FEBF88D8A28CE65BF063DB0372C9D4FD6BAEFCCAC7CCF900812FF19")
            ||!ModuleFile(pl,10124800,"5CAB8D82F3C26012B4BAA5A70822035B7C4474F13AA3D42135AAAC609232874F"))return false;
        auto hook=GetProcAddress(ll,"?hook@memory@ll@@YAHPEAX0PEAPEAXW4HookPriority@12@_N@Z");
        auto unhook=GetProcAddress(ll,"?unhook@memory@ll@@YA_NPEAX0_N@Z");
        if(!ExecutableOwner(hook,ll)||!ExecutableOwner(unhook,ll))return false;
        result.attach=reinterpret_cast<decltype(result.attach)>(hook);result.detach=reinterpret_cast<decltype(result.detach)>(unhook);
        auto lamium=GetModuleHandleW(L"Lamium.dll");
        if(lamium&&ModuleFile(lamium,8140800,"7E9B6859B43A826021409F4EB87D34157D527D730E7AF44D26297665FE9B24C8")){
            constexpr unsigned char render[]{0x55,0x56,0x48,0x83,0xec,0x68,0x48,0x8d,0x6c,0x24,0x60,0x48,0xc7,0x45,0x00,0xfe,
                0xff,0xff,0xff,0x4c,0x89,0x45,0xd0,0x48,0x89,0x55,0xd8,0x48,0x89,0x4d,0xf0,0x48};
            auto address=reinterpret_cast<std::uintptr_t>(lamium)+0x34d5c0;
            if(ExecutableOwner(reinterpret_cast<void*>(address),lamium)&&OwnerPrefix(address,render))result.reviewedRenderOwner=address;
            // Exact delay-import registration: setupCamera IAT 0x780720.
            // FreeCameraSetupHook (Normal) and CameraCopyHook (Highest) both
            // call their resident original before post-processing/copying.
            constexpr unsigned char setup[]{0x55,0x56,0x57,0x48,0x83,0xec,0x30,0x48,0x8d,0x6c,0x24,0x30,0x48,0xc7,0x45,0xf8,
                0xfe,0xff,0xff,0xff,0x48,0x89,0xd6,0x48,0x89,0xcf,0x48,0x63,0x0d,0x1f,0x57,0x75};
            constexpr unsigned char copy[]{0x55,0x56,0x57,0x48,0x81,0xec,0x50,0x01,0x00,0x00,0x48,0x8d,0xac,0x24,0x80,0x00,
                0x00,0x00,0x44,0x0f,0x29,0x95,0xc0,0x00,0x00,0x00,0x44,0x0f,0x29,0x8d,0xb0,0x00};
            address=reinterpret_cast<std::uintptr_t>(lamium)+0x2d850;
            if(ExecutableOwner(reinterpret_cast<void*>(address),lamium)&&OwnerPrefix(address,setup))result.reviewedSetupOwners[0]=address;
            address=reinterpret_cast<std::uintptr_t>(lamium)+0x1ed710;
            if(ExecutableOwner(reinterpret_cast<void*>(address),lamium)&&OwnerPrefix(address,copy))result.reviewedSetupOwners[1]=address;
        }
        return true;
    }catch(...){result={};return false;}
}
}
