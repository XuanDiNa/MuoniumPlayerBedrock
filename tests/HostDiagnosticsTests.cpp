#include "Runtime.h"
#include "NativeProfile.h"
#include <fstream>
#include <iostream>
#include <stdexcept>

namespace {
unsigned checks{};
void Need(bool ok,char const* why){++checks;if(!ok)throw std::runtime_error(why);}
struct Fixture {
    std::filesystem::path folder=std::filesystem::current_path()/(L"host-diagnostic-fixture-"+std::to_wstring(GetCurrentProcessId()));
    std::filesystem::path file=folder/L"owned-image.bin";
    Fixture(){Need(std::filesystem::create_directory(folder),"create owned fixture directory");}
    ~Fixture(){std::error_code ignored;std::filesystem::remove(file,ignored);std::filesystem::remove(folder,ignored);}
};
void Reason(mp::HostVerification const& r,char const* expected){
    Need(std::string_view(r.reason)==expected,"distinct host-check failure reason");
    Need(mp::VerifiedHostIdentity.load()==nullptr,"failed check revokes all native authorization");
}
}
int wmain(int argc,wchar_t** argv){try{
    Need(argc==2,"provide reviewed LL image");
    mp::HostVerification r;
    Need(mp::VerifyHost(argv[1],&r),"reviewed LL image remains admitted with diagnostics");
    Need(std::string_view(r.reason)=="matched"&&r.win32==0&&r.cryptoStatus==0,"exact match status");
    auto identity=mp::VerifiedHostIdentity.load();
    Need(identity&&r.sha256==identity->sha256&&r.bytes==identity->fileSize,"actual whole-file digest and size");
    Need(r.peValid&&r.machine==0x8664&&r.timestamp==identity->timestamp&&r.imageSize==identity->imageSize,"actual file PE metadata");
    Fixture fixture;
    Need(!mp::VerifyHost(fixture.file,&r),"missing file refuses");Reason(r,"open-failed");
    Need(r.win32==ERROR_FILE_NOT_FOUND&&r.sha256.empty()&&r.bytes==0,"missing report resets previous values");
    {std::ofstream f(fixture.file,std::ios::binary);f<<"owned unknown image";}
    Need(!mp::VerifyHost(fixture.file,&r),"unknown size never gains admission from diagnostic hash");Reason(r,"size-unrecognized");
    Need(r.sha256.size()==64&&r.bytes==19&&!r.peValid&&r.win32==0,"unknown size records bounded file digest");
    auto formatted=mp::FormatHostVerification(r);
    Need(formatted.find("HOST_DIAGNOSTIC revision=1 reason=size-unrecognized win32=0 bytes=19 sha256=")==0,"stable diagnostic prefix");
    Need(formatted.find(fixture.file.string())==std::string::npos,"diagnostics omit user paths");
    auto locked=CreateFileW(fixture.file.c_str(),GENERIC_READ|GENERIC_WRITE,0,nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr);
    Need(locked!=INVALID_HANDLE_VALUE,"own exclusive-lock fixture");
    auto accepted=mp::VerifyHost(fixture.file,&r);CloseHandle(locked);
    Need(!accepted,"sharing violation remains refused");Reason(r,"open-failed");
    Need(r.win32==ERROR_SHARING_VIOLATION&&r.sha256.empty(),"read-open failure distinct from an unknown digest");
    {std::ofstream f(fixture.file,std::ios::binary|std::ios::trunc);f.seekp(identity->fileSize-1);f.put('\0');}
    Need(!mp::VerifyHost(fixture.file,&r),"same-size unrelated data refuses");Reason(r,"digest-unrecognized");
    Need(r.bytes==identity->fileSize&&r.sha256.size()==64&&!r.peValid,"same-size rejection records its actual digest");
    Need(!mp::VerifyHost(fixture.file),"non-diagnostic call retains refusal");
    wchar_t self[32768]{};Need(GetModuleFileNameW(nullptr,self,32768)>0,"owned fixture executable");
    Need(!mp::VerifyHost(self,&r),"owned non-game PE refuses");Reason(r,"size-unrecognized");
    Need(r.peValid&&r.machine==0x8664&&r.imageSize>0&&r.sha256.size()==64,"unknown valid PE metadata recorded without admission");
    Need(mp::VerifyHost(argv[1],&r)&&std::string_view(r.reason)=="matched","later exact match clears prior failure");
    std::cout<<"Host diagnostics exact admission, missing/locked/size/digest refusal, PE metadata and privacy checks="<<checks<<" passed\n";
    return 0;
}catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}}
