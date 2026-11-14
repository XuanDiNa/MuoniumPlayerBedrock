#include "Runtime.h"
#include "NativeProfile.h"
#include "NativeContracts.h"
#include "DefaultPlayerConfig.h"
#include <bcrypt.h>
#include <array>
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
bool VerifyHost(std::filesystem::path const& path){
    VerifiedHostIdentity.store(nullptr,std::memory_order_release);
    try{
        HANDLE f=CreateFileW(path.c_str(),GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_DELETE,nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr);if(f==INVALID_HANDLE_VALUE)return false;
        LARGE_INTEGER size{};bool knownSize=false;
        if(GetFileSizeEx(f,&size)&&size.QuadPart>=0)for(auto const& identity:HostIdentities)knownSize|=static_cast<std::uint64_t>(size.QuadPart)==identity.fileSize;
        if(!knownSize){CloseHandle(f);return false;}
        BCRYPT_ALG_HANDLE a{};BCRYPT_HASH_HANDLE hash{};bool ok=BCryptOpenAlgorithmProvider(&a,BCRYPT_SHA256_ALGORITHM,nullptr,0)>=0;
        if(ok)ok=BCryptCreateHash(a,&hash,nullptr,0,nullptr,0,0)>=0;
        std::vector<unsigned char> buffer(1024*1024);std::array<unsigned char,32> digest{};DWORD read{};
        while(ok){if(!ReadFile(f,buffer.data(),static_cast<DWORD>(buffer.size()),&read,nullptr)){ok=false;break;}if(!read)break;ok=BCryptHashData(hash,buffer.data(),read,0)>=0;}
        if(ok)ok=BCryptFinishHash(hash,digest.data(),32,0)>=0;if(hash)BCryptDestroyHash(hash);if(a)BCryptCloseAlgorithmProvider(a,0);CloseHandle(f);
        constexpr char hex[]="0123456789ABCDEF";std::string s;for(auto b:digest){s+=hex[b>>4];s+=hex[b&15];}
        auto identity=ok?MatchHostIdentity(s,size.QuadPart):nullptr;
        if(identity)VerifiedHostIdentity.store(identity,std::memory_order_release);
        return identity!=nullptr;
    }catch(...){return false;}
}
void Log(std::filesystem::path const& folder,std::string const& text)noexcept{
    try{static std::mutex mutex;static std::filesystem::path file;std::lock_guard lock(mutex);SYSTEMTIME t;GetLocalTime(&t);
        if(file.empty()){std::filesystem::create_directories(folder/L"logs");wchar_t name[100];swprintf_s(name,L"MuoniumPlayerBedrock-%04u%02u%02u-%02u%02u%02u-%lu.log",t.wYear,t.wMonth,t.wDay,t.wHour,t.wMinute,t.wSecond,GetCurrentProcessId());file=folder/L"logs"/name;}
        std::ofstream f(file,std::ios::app);f<<std::setfill('0')<<std::setw(2)<<t.wHour<<":"<<std::setw(2)<<t.wMinute<<":"<<std::setw(2)<<t.wSecond<<" "<<text<<"\n";
    }catch(...){}
}
}
