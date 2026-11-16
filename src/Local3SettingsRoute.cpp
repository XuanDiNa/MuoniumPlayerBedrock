#include "Local3SettingsRoute.h"
#include "Local3Descriptions.h"
#include "Player.h"
#include <bcrypt.h>
#include <algorithm>
#include <vector>
namespace mp {
namespace {
struct File {HANDLE value{INVALID_HANDLE_VALUE};~File(){if(value!=INVALID_HANDLE_VALUE)CloseHandle(value);}};
bool Read(HANDLE handle,std::string& bytes){
    BY_HANDLE_FILE_INFORMATION info{};
    if(handle==INVALID_HANDLE_VALUE||!GetFileInformationByHandle(handle,&info)||info.nNumberOfLinks!=1
       ||(info.dwFileAttributes&(FILE_ATTRIBUTE_REPARSE_POINT|FILE_ATTRIBUTE_DIRECTORY|FILE_ATTRIBUTE_READONLY))
       ||info.nFileSizeHigh||!info.nFileSizeLow||info.nFileSizeLow>65536)return false;
    bytes.resize(info.nFileSizeLow);DWORD used{};
    return ReadFile(handle,bytes.data(),DWORD(bytes.size()),&used,nullptr)&&used==bytes.size();
}
struct Folders {
    std::vector<HANDLE> handles;
    ~Folders(){for(auto handle:handles)CloseHandle(handle);}
    bool Pin(std::filesystem::path path){
        for(path=std::filesystem::absolute(path).lexically_normal();;path=path.parent_path()){
            auto handle=CreateFileW(path.c_str(),FILE_READ_ATTRIBUTES,FILE_SHARE_READ|FILE_SHARE_WRITE,nullptr,
                OPEN_EXISTING,FILE_FLAG_BACKUP_SEMANTICS|FILE_FLAG_OPEN_REPARSE_POINT,nullptr);
            if(handle==INVALID_HANDLE_VALUE)return false;
            handles.push_back(handle);BY_HANDLE_FILE_INFORMATION info{};
            if(!GetFileInformationByHandle(handle,&info)||!(info.dwFileAttributes&FILE_ATTRIBUTE_DIRECTORY)
               ||(info.dwFileAttributes&FILE_ATTRIBUTE_REPARSE_POINT))return false;
            if(path==path.parent_path())return true;
        }
    }
};
std::string Hash(std::string const& bytes){
    unsigned char hash[32]{};
    if(BCryptHash(BCRYPT_SHA256_ALG_HANDLE,nullptr,0,reinterpret_cast<PUCHAR>(const_cast<char*>(bytes.data())),ULONG(bytes.size()),hash,32)<0)return {};
    constexpr char hex[]="0123456789ABCDEF";std::string out;
    for(auto byte:hash){out+=hex[byte>>4];out+=hex[byte&15];}return out;
}
bool Known(std::string const& bytes){auto hash=Hash(bytes);return std::find(Local3Descriptions.begin(),Local3Descriptions.end(),hash)!=Local3Descriptions.end();}
}
bool Local3SettingsRoute::HideDescription() noexcept {try{
    auto root=folder_/L"modmenu.settings.json",nested=folder_/L"interface";
    Folders pins;if(!pins.Pin(folder_))return false;
    if(GetFileAttributesW(root.c_str())==INVALID_FILE_ATTRIBUTES)return GetLastError()==ERROR_FILE_NOT_FOUND;
    std::filesystem::create_directory(nested);if(!pins.Pin(nested))return false;
    // Pin the exact source with delete access and no write/delete sharing.
    File source{CreateFileW(root.c_str(),GENERIC_READ|DELETE,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_FLAG_OPEN_REPARSE_POINT,nullptr)};
    std::string bytes;if(!Read(source.value,bytes)||!Known(bytes))return false;
    auto backup=nested/(L"legacy-"+Wide(Hash(bytes))+L".settings.json");
    if(GetFileAttributesW(backup.c_str())==INVALID_FILE_ATTRIBUTES){
        File output{CreateFileW(backup.c_str(),GENERIC_WRITE,0,nullptr,CREATE_NEW,FILE_ATTRIBUTE_NORMAL|FILE_FLAG_WRITE_THROUGH,nullptr)};
        DWORD used{};if(output.value==INVALID_HANDLE_VALUE||!WriteFile(output.value,bytes.data(),DWORD(bytes.size()),&used,nullptr)||used!=bytes.size()||!FlushFileBuffers(output.value))return false;
    }
    File saved{CreateFileW(backup.c_str(),GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_FLAG_OPEN_REPARSE_POINT,nullptr)};
    std::string copy;if(!Read(saved.value,copy)||copy!=bytes)return false;
    FILE_DISPOSITION_INFO remove{TRUE};
    return SetFileInformationByHandle(source.value,FileDispositionInfo,&remove,sizeof remove)!=FALSE;
}catch(...){return false;}}
bool Local3SettingsRoute::RestoreDescription() noexcept {try{
    auto nested=folder_/L"interface";
    Folders pins;if(!pins.Pin(nested))return false;
    File file{CreateFileW((nested/L"modmenu.settings.json").c_str(),GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_FLAG_OPEN_REPARSE_POINT,nullptr)};
    std::string bytes;if(!Read(file.value,bytes)||!Known(bytes))return false;
    auto root=folder_/L"modmenu.settings.json";
    if(GetFileAttributesW(root.c_str())!=INVALID_FILE_ATTRIBUTES)return false;
    auto temporary=nested/(L"routing-"+std::to_wstring(GetCurrentProcessId())+L".tmp");
    if(!CopyFileW((nested/L"modmenu.settings.json").c_str(),temporary.c_str(),TRUE))return false;
    // Publish only the complete file. Never replace a concurrently added root description.
    bool moved=MoveFileExW(temporary.c_str(),root.c_str(),MOVEFILE_WRITE_THROUGH)!=FALSE;
    if(!moved)DeleteFileW(temporary.c_str());
    return moved;
}catch(...){return false;}}
}
