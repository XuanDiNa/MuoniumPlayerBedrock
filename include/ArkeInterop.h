#pragma once
#include <Windows.h>
#include <bcrypt.h>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>

namespace mp::arke {
// Selected IDA evidence: artifacts/compat-26.60/arke-native-evidence.json.
// These are Arke's own original-call slots, reached AFTER its overlay work.
inline constexpr char ImageHash[]="98EFECBD6A932F613D3BC1FA8CBD35ABB9B6DB7B58B89994371B8020D3AD7515";
inline constexpr std::array<std::size_t,3> Callbacks{0x934F0,0x935E0,0x936D0};
inline constexpr std::array<std::size_t,3> CallbackSizes{0xB2,0xBC,0x3C};
inline constexpr std::array<std::size_t,3> OriginalSlots{0x21ADB90,0x21ADB98,0x21ADBA0};
struct Slots {
    std::array<void* volatile*,3> addresses{};
    std::array<void*,3> originals{};
    HMODULE module{};
};
inline bool Read(void const* source,void* result,std::size_t size,bool executable=false){
    auto at=reinterpret_cast<std::uintptr_t>(source);auto end=at+size;if(!at||end<at)return false;
    for(auto cursor=at;cursor<end;){MEMORY_BASIC_INFORMATION m{};
        if(!VirtualQuery(reinterpret_cast<void*>(cursor),&m,sizeof m)||m.State!=MEM_COMMIT||(m.Protect&(PAGE_GUARD|PAGE_NOACCESS)))return false;
        auto p=m.Protect&0xff;
        if(executable&&p!=PAGE_EXECUTE&&p!=PAGE_EXECUTE_READ&&p!=PAGE_EXECUTE_READWRITE&&p!=PAGE_EXECUTE_WRITECOPY)return false;
        auto next=reinterpret_cast<std::uintptr_t>(m.BaseAddress)+m.RegionSize;if(next<=cursor)return false;cursor=next;
    }
    SIZE_T copied{};
    return ReadProcessMemory(GetCurrentProcess(),source,result,size,&copied)&&copied==size;
}
inline bool LoadImage(std::filesystem::path const& path,std::vector<unsigned char>& bytes){
    std::ifstream file(path,std::ios::binary|std::ios::ate);if(!file)return false;auto n=file.tellg();
    if(n<4096||n>512*1024*1024)return false;bytes.resize(static_cast<std::size_t>(n));file.seekg(0);
    return bool(file.read(reinterpret_cast<char*>(bytes.data()),n));
}
inline bool HashMatches(std::vector<unsigned char> const& bytes){
    BCRYPT_ALG_HANDLE algorithm{};BCRYPT_HASH_HANDLE hash{};unsigned char result[32]{};
    bool ok=BCryptOpenAlgorithmProvider(&algorithm,BCRYPT_SHA256_ALGORITHM,nullptr,0)>=0;
    if(ok)ok=BCryptCreateHash(algorithm,&hash,nullptr,0,nullptr,0,0)>=0;
    if(ok)ok=BCryptHashData(hash,const_cast<PUCHAR>(bytes.data()),static_cast<ULONG>(bytes.size()),0)>=0;
    if(ok)ok=BCryptFinishHash(hash,result,32,0)>=0;
    if(hash)BCryptDestroyHash(hash);if(algorithm)BCryptCloseAlgorithmProvider(algorithm,0);
    constexpr char hex[]="0123456789ABCDEF";char text[65]{};
    for(unsigned i=0;i<32;i++){text[2*i]=hex[result[i]>>4];text[2*i+1]=hex[result[i]&15];}
    return ok&&!std::strcmp(text,ImageHash);
}
inline unsigned char const* ImageBytes(std::vector<unsigned char> const& image,std::size_t rva,std::size_t size){
    if(image.size()<sizeof(IMAGE_DOS_HEADER))return nullptr;IMAGE_DOS_HEADER dos{};std::memcpy(&dos,image.data(),sizeof dos);
    if(dos.e_magic!=IMAGE_DOS_SIGNATURE||dos.e_lfanew<64||std::size_t(dos.e_lfanew)>image.size()-sizeof(IMAGE_NT_HEADERS64))return nullptr;
    IMAGE_NT_HEADERS64 nt{};std::memcpy(&nt,image.data()+dos.e_lfanew,sizeof nt);
    if(nt.Signature!=IMAGE_NT_SIGNATURE||nt.OptionalHeader.Magic!=IMAGE_NT_OPTIONAL_HDR64_MAGIC||nt.FileHeader.NumberOfSections>96)return nullptr;
    auto table=std::size_t(dos.e_lfanew)+24+nt.FileHeader.SizeOfOptionalHeader;
    for(unsigned i=0;i<nt.FileHeader.NumberOfSections;i++){
        auto at=table+i*sizeof(IMAGE_SECTION_HEADER);if(at>image.size()||sizeof(IMAGE_SECTION_HEADER)>image.size()-at)return nullptr;
        IMAGE_SECTION_HEADER section{};std::memcpy(&section,image.data()+at,sizeof section);
        if(rva<section.VirtualAddress||rva-section.VirtualAddress>section.SizeOfRawData||size>section.SizeOfRawData-(rva-section.VirtualAddress))continue;
        auto raw=section.PointerToRawData+rva-section.VirtualAddress;
        if(raw>image.size()||size>image.size()-raw)return nullptr;
        return image.data()+raw;
    }return nullptr;
}
inline bool OriginalSystemBytes(void* target,std::array<unsigned char,32>& bytes){
    HMODULE module{};if(!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS|GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,reinterpret_cast<LPCWSTR>(target),&module))return false;
    wchar_t path[32768]{},sys[32768]{};if(!GetModuleFileNameW(module,path,32768)||!GetSystemDirectoryW(sys,32768))return false;
    auto full=std::filesystem::path(path).lexically_normal().wstring(),root=std::filesystem::path(sys).wstring()+L"\\";
    if(full.size()<=root.size()||_wcsnicmp(full.c_str(),root.c_str(),root.size()))return false;
    MEMORY_BASIC_INFORMATION memory{};if(!VirtualQuery(target,&memory,sizeof memory)||memory.AllocationBase!=module)return false;
    std::vector<unsigned char> image;if(!LoadImage(path,image))return false;
    auto original=ImageBytes(image,reinterpret_cast<std::uintptr_t>(target)-reinterpret_cast<std::uintptr_t>(module),bytes.size());
    if(!original)return false;std::memcpy(bytes.data(),original,bytes.size());return true;
}
inline bool DetourMatches(void* target,void* callback,std::array<unsigned char,32> const& original){
    std::array<unsigned char,32> first{};if(!Read(target,first.data(),first.size(),true))return false;
    std::size_t patch=first[0]==0xE9?5:(first[0]==0xFF&&first[1]==0x25?6:0);
    if(patch==6){std::int32_t displacement{};std::memcpy(&displacement,first.data()+2,4);if(displacement==0)patch=14;}
    if(!patch||std::memcmp(first.data()+patch,original.data()+patch,first.size()-patch))return false;
    auto at=reinterpret_cast<std::uintptr_t>(target);
    for(unsigned hop=0;hop<4;hop++){
        if(at==reinterpret_cast<std::uintptr_t>(callback))return true;
        unsigned char instruction[6]{};if(!Read(reinterpret_cast<void*>(at),instruction,sizeof instruction,true))return false;
        std::int32_t offset{};
        if(instruction[0]==0xE9){std::memcpy(&offset,instruction+1,4);at=at+5+offset;}
        else if(instruction[0]==0xFF&&instruction[1]==0x25){std::memcpy(&offset,instruction+2,4);void* next{};
            if(!Read(reinterpret_cast<void*>(at+6+offset),&next,sizeof next))return false;at=reinterpret_cast<std::uintptr_t>(next);}
        else return false;
    }return at==reinterpret_cast<std::uintptr_t>(callback);
}
inline bool Prepare(std::array<void*,3> const& targets,Slots& result,std::string& reason){
    HMODULE module{};if(!GetModuleHandleExW(0,L"arke.dll",&module)){reason="arke-not-loaded";return false;}
    struct ReleaseModule {HMODULE module;~ReleaseModule(){FreeLibrary(module);}} reference{module};
    wchar_t path[32768]{};if(!GetModuleFileNameW(module,path,32768)){reason="arke-path";return false;}
    std::vector<unsigned char> image;if(!LoadImage(path,image)||!HashMatches(image)){reason="arke-image-hash";return false;}
    Slots candidate;candidate.module=module;
    for(unsigned i=0;i<3;i++){
        auto callback=reinterpret_cast<std::byte*>(module)+Callbacks[i];std::array<unsigned char,192> live{};
        auto expected=ImageBytes(image,Callbacks[i],CallbackSizes[i]);
        if(!expected||!Read(callback,live.data(),CallbackSizes[i],true)||std::memcmp(live.data(),expected,CallbackSizes[i])){reason="arke-callback-body";return false;}
        std::array<unsigned char,32> stock{};
        if(!OriginalSystemBytes(targets[i],stock)||!DetourMatches(targets[i],callback,stock)){reason="arke-detour-owner";return false;}
        auto slot=reinterpret_cast<void* volatile*>(reinterpret_cast<std::byte*>(module)+OriginalSlots[i]);MEMORY_BASIC_INFORMATION memory{};
        if(!VirtualQuery(const_cast<void**>(slot),&memory,sizeof memory)||memory.State!=MEM_COMMIT||memory.AllocationBase!=module||
           (memory.Protect!=PAGE_READWRITE&&memory.Protect!=PAGE_WRITECOPY)){reason="arke-slot-page";return false;}
        void* original{};unsigned char byte{};
        if(!Read(const_cast<void**>(slot),&original,sizeof original)||!Read(original,&byte,1,true)){reason="arke-original-unavailable";return false;}
        auto address=reinterpret_cast<std::uintptr_t>(original);
        for(unsigned j=0;j<3;j++){
            auto begin=reinterpret_cast<std::uintptr_t>(module)+Callbacks[j];
            if(original==targets[j]||(address>=begin&&address<begin+CallbackSizes[j])){reason="arke-original-recursive";return false;}
        }
        candidate.addresses[i]=slot;candidate.originals[i]=original;
    }
    HMODULE pinned{};
    if(!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS|GET_MODULE_HANDLE_EX_FLAG_PIN,reinterpret_cast<LPCWSTR>(module),&pinned)||pinned!=module){reason="arke-lifetime";return false;}
    result=candidate;return true;
}
inline void Rollback(Slots const& slots,std::array<void*,3> const& callbacks,unsigned count){
    while(count){--count;InterlockedCompareExchangePointer(slots.addresses[count],slots.originals[count],callbacks[count]);}
}
inline bool Commit(Slots const& slots,std::array<void*,3> const& callbacks){
    for(unsigned i=0;i<3;i++){
        if(InterlockedCompareExchangePointer(slots.addresses[i],callbacks[i],slots.originals[i])!=slots.originals[i]){Rollback(slots,callbacks,i);return false;}
    }return true;
}
}
