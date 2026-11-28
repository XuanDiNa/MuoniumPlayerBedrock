#include "Runtime.h"
#include "NativeProfile.h"
#include "HostHeaderFamilies.h"
#include "NativeContracts.h"
#include "DefaultPlayerConfig.h"
#include <bcrypt.h>
#include <array>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <mutex>
#include <sstream>
#include <vector>
namespace mp {
bool EnsurePlayerConfig(std::filesystem::path const& folder) noexcept{
    try{
        auto parent=std::filesystem::absolute(folder).lexically_normal();
        for(auto p=parent;;p=p.parent_path()){
            auto attributes=GetFileAttributesW(p.c_str());
            if(attributes==INVALID_FILE_ATTRIBUTES||!(attributes&FILE_ATTRIBUTE_DIRECTORY)||(attributes&FILE_ATTRIBUTE_REPARSE_POINT))return false;
            if(p==p.parent_path())break;
        }
        auto path=parent/L"MuoniumPlayerBedrock.json";
        HANDLE file=CreateFileW(path.c_str(),GENERIC_WRITE|DELETE,FILE_SHARE_READ,nullptr,CREATE_NEW,FILE_ATTRIBUTE_NORMAL|FILE_FLAG_OPEN_REPARSE_POINT,nullptr);
        if(file==INVALID_HANDLE_VALUE){
            auto error=GetLastError();if(error!=ERROR_FILE_EXISTS&&error!=ERROR_ALREADY_EXISTS)return false;
            auto attributes=GetFileAttributesW(path.c_str());
            return attributes!=INVALID_FILE_ATTRIBUTES&&!(attributes&(FILE_ATTRIBUTE_DIRECTORY|FILE_ATTRIBUTE_REPARSE_POINT));
        }
        DWORD written{};constexpr DWORD bytes=sizeof(DefaultPlayerConfig)-1;
        bool ok=WriteFile(file,DefaultPlayerConfig,bytes,&written,nullptr)&&written==bytes&&FlushFileBuffers(file);
        if(!ok){FILE_DISPOSITION_INFO remove{TRUE};SetFileInformationByHandle(file,FileDispositionInfo,&remove,sizeof(remove));}
        CloseHandle(file);return ok;
    }catch(...){return false;}
}
namespace {
struct WindowSearch {DWORD pid;HWND found{};};
BOOL CALLBACK ChildBelongs(HWND child,LPARAM raw){auto& search=*reinterpret_cast<WindowSearch*>(raw);DWORD pid{};GetWindowThreadProcessId(child,&pid);if(pid==search.pid){search.found=child;return FALSE;}return TRUE;}
BOOL CALLBACK FindTop(HWND top,LPARAM raw){auto& search=*reinterpret_cast<WindowSearch*>(raw);if(!IsWindowVisible(top)||GetWindow(top,GW_OWNER))return TRUE;wchar_t name[128]{};GetClassNameW(top,name,128);if(std::wstring_view(name)==L"ConsoleWindowClass")return TRUE;RECT r;GetClientRect(top,&r);if(r.right<300||r.bottom<200)return TRUE;
    DWORD pid{};GetWindowThreadProcessId(top,&pid);if(pid==search.pid){search.found=top;return FALSE;}WindowSearch child{search.pid};EnumChildWindows(top,ChildBelongs,reinterpret_cast<LPARAM>(&child));if(child.found){search.found=top;return FALSE;}return TRUE;}
}
HWND FindOwnerWindow(DWORD pid){WindowSearch search{pid};EnumWindows(FindTop,reinterpret_cast<LPARAM>(&search));return search.found;}
NativeContract const* LoadedNativeContract(std::uintptr_t base) noexcept{
    auto identity=VerifiedHostIdentity.load(std::memory_order_acquire);
    auto contract=SelectNativeContract(identity);if(!contract||!base)return nullptr;
    __try{
        auto dos=reinterpret_cast<IMAGE_DOS_HEADER const*>(base);
        if(dos->e_magic!=IMAGE_DOS_SIGNATURE||dos->e_lfanew<64||dos->e_lfanew>4096)return nullptr;
        auto nt=reinterpret_cast<IMAGE_NT_HEADERS64 const*>(base+dos->e_lfanew);
        if(nt->Signature!=IMAGE_NT_SIGNATURE||nt->OptionalHeader.Magic!=IMAGE_NT_OPTIONAL_HDR64_MAGIC)return nullptr;
        return MatchLoadedIdentity(identity,nt->FileHeader.Machine,nt->FileHeader.TimeDateStamp,nt->OptionalHeader.SizeOfImage)?contract:nullptr;
    }__except(EXCEPTION_EXECUTE_HANDLER){return nullptr;}
}
bool VerifyHost(std::filesystem::path const& path,HostVerification* report){
    VerifiedHostIdentity.store(nullptr,std::memory_order_release);
    HostVerification result;
    struct Publish {HostVerification* report;HostVerification& value;~Publish(){if(report)*report=std::move(value);}} publish{report,result};
    try{
        struct File {HANDLE value{INVALID_HANDLE_VALUE};~File(){if(value!=INVALID_HANDLE_VALUE)CloseHandle(value);}} file;
        file.value=CreateFileW(path.c_str(),GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_DELETE,nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr);
        if(file.value==INVALID_HANDLE_VALUE){result.reason="open-failed";result.win32=GetLastError();return false;}
        LARGE_INTEGER size{};bool knownSize=false;
        if(!GetFileSizeEx(file.value,&size)){result.reason="size-read-failed";result.win32=GetLastError();return false;}
        if(size.QuadPart<0){result.reason="size-invalid";return false;}
        result.bytes=static_cast<std::uint64_t>(size.QuadPart);
        for(auto const& identity:HostIdentities)knownSize|=result.bytes==identity.fileSize;
        // Unknown sizes stay refused. A requested diagnostic may still hash a
        // bounded file to identify it; that digest never authorizes a profile.
        if(!knownSize&&(!report||result.bytes>512ull*1024*1024)){result.reason="size-unrecognized";return false;}
        struct Crypto {BCRYPT_ALG_HANDLE alg{};BCRYPT_HASH_HANDLE hash{},variant{};~Crypto(){if(variant)BCryptDestroyHash(variant);if(hash)BCryptDestroyHash(hash);if(alg)BCryptCloseAlgorithmProvider(alg,0);}} crypto;
        auto status=BCryptOpenAlgorithmProvider(&crypto.alg,BCRYPT_SHA256_ALGORITHM,nullptr,0);
        if(status<0){result.reason="hash-provider-failed";result.cryptoStatus=static_cast<std::uint32_t>(status);return false;}
        status=BCryptCreateHash(crypto.alg,&crypto.hash,nullptr,0,nullptr,0,0);
        if(status<0){result.reason="hash-create-failed";result.cryptoStatus=static_cast<std::uint32_t>(status);return false;}
        std::vector<unsigned char> buffer(1024*1024);std::array<unsigned char,32> digest{};DWORD read{};
        std::uint64_t total{};std::size_t metadataOffset{};
        for(;;){
            if(!ReadFile(file.value,buffer.data(),static_cast<DWORD>(buffer.size()),&read,nullptr)){result.reason="read-failed";result.win32=GetLastError();return false;}
            if(!read)break;
            if(total==0&&read>=sizeof(IMAGE_DOS_HEADER)){
                IMAGE_DOS_HEADER dos;std::memcpy(&dos,buffer.data(),sizeof(dos));
                if(dos.e_magic==IMAGE_DOS_SIGNATURE&&dos.e_lfanew>=64&&dos.e_lfanew<=4096&&static_cast<std::size_t>(dos.e_lfanew)+sizeof(IMAGE_NT_HEADERS64)<=read){
                    IMAGE_NT_HEADERS64 nt;std::memcpy(&nt,buffer.data()+dos.e_lfanew,sizeof(nt));
                    if(nt.Signature==IMAGE_NT_SIGNATURE&&nt.OptionalHeader.Magic==IMAGE_NT_OPTIONAL_HDR64_MAGIC){
                        result.peValid=true;result.machine=nt.FileHeader.Machine;result.timestamp=nt.FileHeader.TimeDateStamp;
                        result.imageSize=nt.OptionalHeader.SizeOfImage;result.subsystem=nt.OptionalHeader.Subsystem;
                        if(knownSize&&CanHashHostHeaderVariant(result.bytes,result.machine,result.timestamp,result.imageSize,result.subsystem)){
                            metadataOffset=static_cast<std::size_t>(dos.e_lfanew)+24+64;
                            status=BCryptCreateHash(crypto.alg,&crypto.variant,nullptr,0,nullptr,0,0);
                            if(status<0){result.reason="hash-create-failed";result.cryptoStatus=static_cast<std::uint32_t>(status);return false;}
                        }
                    }
                }
            }
            total+=read;
            if(total>result.bytes){result.reason="size-changed";return false;}
            status=BCryptHashData(crypto.hash,buffer.data(),read,0);
            if(status<0){result.reason="hash-update-failed";result.cryptoStatus=static_cast<std::uint32_t>(status);return false;}
            if(crypto.variant){
                // Raw digest was updated first. Only the first private buffer
                // masks these six bytes; every other byte is still hashed.
                if(total==read)std::memset(buffer.data()+metadataOffset,0,6);
                status=BCryptHashData(crypto.variant,buffer.data(),read,0);
                if(status<0){result.reason="hash-update-failed";result.cryptoStatus=static_cast<std::uint32_t>(status);return false;}
            }
        }
        if(total!=result.bytes){result.reason="size-changed";return false;}
        status=BCryptFinishHash(crypto.hash,digest.data(),32,0);
        if(status<0){result.reason="hash-finish-failed";result.cryptoStatus=static_cast<std::uint32_t>(status);return false;}
        constexpr char hex[]="0123456789ABCDEF";std::string s;for(auto b:digest){s+=hex[b>>4];s+=hex[b&15];}
        result.sha256=s;
        auto identity=knownSize?MatchHostIdentity(s,result.bytes):nullptr;
        result.reason=identity?"matched":knownSize?"digest-unrecognized":"size-unrecognized";
        if(!identity&&crypto.variant){
            status=BCryptFinishHash(crypto.variant,digest.data(),32,0);
            if(status<0){result.reason="hash-finish-failed";result.cryptoStatus=static_cast<std::uint32_t>(status);return false;}
            for(auto b:digest){result.headerVariantSha256+=hex[b>>4];result.headerVariantSha256+=hex[b&15];}
            identity=MatchHostHeaderVariant(result.headerVariantSha256,result.bytes,result.machine,result.timestamp,result.imageSize,result.subsystem);
            if(identity){result.reason="matched-header-variant";result.identitySha256=identity->sha256;}
        }
        if(identity)VerifiedHostIdentity.store(identity,std::memory_order_release);
        return identity!=nullptr;
    }catch(...){result.reason="unexpected-error";return false;}
}
std::string FormatHostVerification(HostVerification const& r){
    std::ostringstream text;
    text<<"HOST_DIAGNOSTIC revision=1 reason="<<r.reason<<" win32="<<r.win32<<" bytes="<<r.bytes
        <<" sha256="<<(r.sha256.empty()?"unavailable":r.sha256)<<" crypto=0x"<<std::hex<<r.cryptoStatus
        <<" pe="<<(r.peValid?"valid":"unavailable")<<" machine=0x"<<r.machine
        <<" timestamp=0x"<<r.timestamp<<" imageSize=0x"<<r.imageSize<<std::dec<<" subsystem="<<r.subsystem;
    if(!r.headerVariantSha256.empty())text<<" headerVariantSha256="<<r.headerVariantSha256;
    if(!r.identitySha256.empty())text<<" identitySha256="<<r.identitySha256;
    return text.str();
}
void Log(std::filesystem::path const& folder,std::string const& text)noexcept{
    try{static std::mutex mutex;static std::filesystem::path file;std::lock_guard lock(mutex);SYSTEMTIME t;GetLocalTime(&t);
        if(file.empty()){std::filesystem::create_directories(folder/L"logs");wchar_t name[100];swprintf_s(name,L"MuoniumPlayerBedrock-%04u%02u%02u-%02u%02u%02u-%lu.log",t.wYear,t.wMonth,t.wDay,t.wHour,t.wMinute,t.wSecond,GetCurrentProcessId());file=folder/L"logs"/name;}
        std::ofstream f(file,std::ios::app);f<<std::setfill('0')<<std::setw(2)<<t.wHour<<":"<<std::setw(2)<<t.wMinute<<":"<<std::setw(2)<<t.wSecond<<" "<<text<<"\n";
    }catch(...){}
}
}
