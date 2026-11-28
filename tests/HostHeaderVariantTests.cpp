#include "Runtime.h"
#include "NativeContracts.h"
#include "LoaderPolicy.h"
#include <array>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <vector>
namespace {
unsigned checks{};
void Need(bool value,char const* why){++checks;if(!value)throw std::runtime_error(why);}
struct Fixture {
    std::filesystem::path folder=std::filesystem::current_path()/(L"host-header-fixture-"+std::to_wstring(GetCurrentProcessId()));
    std::filesystem::path file=folder/L"host.bin";
    Fixture(){Need(std::filesystem::create_directory(folder),"create owned fixture directory");}
    ~Fixture(){std::error_code e;std::filesystem::remove(file,e);std::filesystem::remove(folder,e);}
};
void Put(std::filesystem::path const& file,std::uint64_t offset,void const* data,std::size_t bytes){
    std::fstream out(file,std::ios::binary|std::ios::in|std::ios::out);out.seekp(offset);
    out.write(static_cast<char const*>(data),static_cast<std::streamsize>(bytes));Need(bool(out),"patch owned fixture");
}
}
int wmain(int argc,wchar_t** argv){try{
    Need(argc==2,"provide one exact reviewed source image");mp::HostVerification report;
    Need(mp::VerifyHost(argv[1],&report),"exact source admitted");auto exact=mp::VerifiedHostIdentity.load();
    Need(exact&&report.sha256==exact->sha256,"source has a reviewed full-file identity");
    auto originalDigest=report.sha256;
    std::ifstream in(argv[1],std::ios::binary);std::vector<unsigned char> header(8192);
    in.read(reinterpret_cast<char*>(header.data()),header.size());Need(bool(in),"read bounded source header");
    auto dos=reinterpret_cast<IMAGE_DOS_HEADER*>(header.data());auto nt=reinterpret_cast<IMAGE_NT_HEADERS64*>(header.data()+dos->e_lfanew);
    auto optional=static_cast<std::uint64_t>(dos->e_lfanew)+24;
    auto originalChecksum=nt->OptionalHeader.CheckSum;auto originalSubsystem=nt->OptionalHeader.Subsystem;
    Fixture fixture;std::filesystem::copy_file(argv[1],fixture.file);
    // Unseen combinations must work without adding a player's digest to a list.
    for(WORD subsystem:{WORD(2),WORD(3)})for(DWORD checksum:{DWORD(0),DWORD(0x12345678),DWORD(0xFFFFFFFF)}){
        Put(fixture.file,optional+64,&checksum,sizeof(checksum));Put(fixture.file,optional+68,&subsystem,sizeof(subsystem));
        Need(mp::VerifyHost(fixture.file,&report),"unseen GUI/console and checksum variant must be admitted after full content comparison");
        auto canonical=mp::VerifiedHostIdentity.load();
        Need(canonical&&canonical->version==exact->version,"variant retains correct game contract");
        Need(mp::SelectNativeContract(canonical)==mp::SelectNativeContract(exact),"variant cannot select a different native contract");
        Need(report.sha256.size()==64&&report.sha256!=originalDigest,"actual player digest remains in report");
        Need(!mp::MatchHostIdentity(report.sha256,report.bytes),"variant absent from exact digest allowlist");
        Need(std::string_view(report.reason)=="matched-header-variant","variant admission is explicit in diagnostics");
        Need(report.headerVariantSha256.size()==64&&report.identitySha256==canonical->sha256,"diagnostics distinguish raw digest from canonical content identity");
        Need(report.subsystem==subsystem,"report retains actual subsystem");
        bool ll=exact->name.find("LeviLamina")!=std::string_view::npos;
        Need(mp::AdmitLoader(canonical,mp::LoaderKind::LeviLamina,true)==ll,"variant cannot cross LL loader ownership");
        Need(mp::AdmitLoader(canonical,mp::LoaderKind::Preload,false)==!ll,"variant cannot cross preload ownership");
        Need(!mp::AdmitLoader(canonical,mp::LoaderKind::Preload,true),"preload with LL still refused");
    }
    Need(mp::VerifyHost(fixture.file),"variant recognition also works when caller requests no diagnostic report");
    auto Refused=[&](std::uint64_t offset,unsigned bytes){
        std::array<unsigned char,8> before{};Need(bytes<=before.size(),"bounded mutation");
        {std::ifstream f(fixture.file,std::ios::binary);f.seekg(offset);f.read(reinterpret_cast<char*>(before.data()),bytes);Need(bool(f),"read owned mutation region");}
        auto changed=before;changed[0]^=0x40;Put(fixture.file,offset,changed.data(),bytes);
        Need(!mp::VerifyHost(fixture.file,&report)&&mp::VerifiedHostIdentity.load()==nullptr,"nonmetadata byte difference refuses and revokes native authority");
        Put(fixture.file,offset,before.data(),bytes);
    };
    auto sections=IMAGE_FIRST_SECTION(nt);
    Refused(optional+16,4); // entrypoint
    Refused(optional+112+8,8); // import directory
    Refused(sections[0].PointerToRawData,1); // game code
    Refused(exact->fileSize-1,1); // trailer or loader bytes
    if(exact->name.find("LeviLamina")!=std::string_view::npos){
        Refused(dos->e_lfanew+4,2); // machine
        Refused(dos->e_lfanew+8,4); // timestamp
        Refused(optional+24,8); // image base
        Refused(optional+56,4); // mapped size
        Refused(optional+60,4); // header size
        Refused(static_cast<std::uint64_t>(reinterpret_cast<unsigned char*>(sections)-header.data())+36,4); // section characteristics
        Refused(sections[nt->FileHeader.NumberOfSections-1].PointerToRawData,1); // loader code
        WORD native=1;Put(fixture.file,optional+68,&native,2);
        Need(!mp::VerifyHost(fixture.file,&report)&&mp::VerifiedHostIdentity.load()==nullptr,"non GUI/console subsystem refused even with identical code");
        Put(fixture.file,optional+68,&originalSubsystem,2);
        Refused(60,4); // NT header offset
    }
    Put(fixture.file,optional+64,&originalChecksum,4);Put(fixture.file,optional+68,&originalSubsystem,2);
    Need(mp::VerifyHost(fixture.file,&report)&&report.sha256==originalDigest,"restored exact fixture admitted");
    Need(std::string_view(report.reason)=="matched","exact route retains priority");
    Need(mp::VerifyHost(argv[1],&report)&&report.sha256==originalDigest,"original game image unchanged");
    std::cout<<"Header variants without per-player allowlisting, full-content mutations, loader ownership and exact-route preservation checks="<<checks<<" passed\n";
    return 0;
}catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}}
