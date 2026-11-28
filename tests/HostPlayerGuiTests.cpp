// Reconstruct the reported image in an owned .bin fixture, never in the game folder.
#include "Runtime.h"
#include "NativeContracts.h"
#include "LoaderPolicy.h"
#include <bcrypt.h>
#include <array>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <vector>
namespace {
constexpr char ConsoleDigest[]="B554A2DA8B2271A4A164AE544A4A447F3A630B5FC2261AFEFE47C7C206826A53";
constexpr char GuiDigest[]="FCBCCAEC4E0EE3AB203146BDF45E41878AED7B42237E6DE2C5053F0730C6E10B";
unsigned checks{};
void Need(bool value,char const* why){++checks;if(!value)throw std::runtime_error(why);}
std::string Hash(unsigned char const* data,std::size_t size){
    struct Crypto {
        BCRYPT_ALG_HANDLE alg{};BCRYPT_HASH_HANDLE hash{};
        ~Crypto(){if(hash)BCryptDestroyHash(hash);if(alg)BCryptCloseAlgorithmProvider(alg,0);}
    } crypto;
    std::array<unsigned char,32> digest{};
    Need(BCryptOpenAlgorithmProvider(&crypto.alg,BCRYPT_SHA256_ALGORITHM,nullptr,0)>=0,"SHA provider");
    Need(BCryptCreateHash(crypto.alg,&crypto.hash,nullptr,0,nullptr,0,0)>=0,"SHA hash");
    Need(BCryptHashData(crypto.hash,const_cast<PUCHAR>(data),static_cast<ULONG>(size),0)>=0,"SHA data");
    Need(BCryptFinishHash(crypto.hash,digest.data(),32,0)>=0,"SHA finish");
    std::string result;constexpr char hex[]="0123456789ABCDEF";
    for(auto b:digest){result+=hex[b>>4];result+=hex[b&15];}return result;
}
struct Fixture {
    std::filesystem::path folder,file;
    Fixture():folder(std::filesystem::current_path()/(L"host-gui-fixture-"+std::to_wstring(GetCurrentProcessId()))),file(folder/L"host.bin"){
        Need(std::filesystem::create_directory(folder),"create new owned fixture directory");
    }
    ~Fixture(){std::error_code error;std::filesystem::remove(file,error);std::filesystem::remove(folder,error);}
};
}
int wmain(int argc,wchar_t** argv){try{
    Need(argc==2,"provide the previously verified B554 console image");
    mp::HostVerification report;
    Need(mp::VerifyHost(argv[1],&report),"canonical console image still admitted by production full-file verifier");
    auto console=mp::VerifiedHostIdentity.load();
    Need(console&&console->sha256==ConsoleDigest,"canonical source is the reviewed B554 image");
    std::ifstream input(argv[1],std::ios::binary|std::ios::ate);Need(bool(input),"open canonical image read-only");
    auto length=input.tellg();Need(length==323855872,"canonical exact file size");
    std::vector<unsigned char> bytes(static_cast<std::size_t>(length));input.seekg(0);
    input.read(reinterpret_cast<char*>(bytes.data()),static_cast<std::streamsize>(bytes.size()));Need(bool(input),"read complete canonical image");
    auto nt=reinterpret_cast<IMAGE_NT_HEADERS64*>(bytes.data()+reinterpret_cast<IMAGE_DOS_HEADER*>(bytes.data())->e_lfanew);
    Need(nt->OptionalHeader.Subsystem==IMAGE_SUBSYSTEM_WINDOWS_CUI,"canonical console subsystem");
    Need(nt->OptionalHeader.CheckSum==0x134DC051,"canonical checksum already matches player report");
    nt->OptionalHeader.Subsystem=IMAGE_SUBSYSTEM_WINDOWS_GUI;
    Need(Hash(bytes.data(),bytes.size())==GuiDigest,"only subsystem change reproduces exact full player digest");
    Need(Hash(bytes.data(),nt->OptionalHeader.SizeOfHeaders)=="B209EAF72E8F22691B0BE8AE7C94AD3BA2BA59CF274E0997F920115282A84F09","reconstructed header matches player evidence");
    auto gui=mp::MatchHostIdentity(GuiDigest,bytes.size());
    Need(gui!=nullptr,"missing exact reported GUI identity");
    Need(mp::SelectNativeContract(gui)==&mp::Contract2660,"same verified 26.60 native contract");
    Need(mp::AdmitLoader(gui,mp::LoaderKind::LeviLamina,true),"GUI image belongs to active LL loader");
    Need(!mp::AdmitLoader(gui,mp::LoaderKind::LeviLamina,false),"missing LL refused");
    Need(!mp::AdmitLoader(gui,mp::LoaderKind::Preload,false),"vanilla preload refuses LL image");
    Need(!mp::AdmitLoader(gui,mp::LoaderKind::Preload,true),"preload cannot own LL lifecycle");
    Need(!mp::MatchHostIdentity(GuiDigest,bytes.size()+1),"wrong file size refused");
    Need(!mp::MatchHostIdentity("FCBCCAEC4E0EE3AB203146BDF45E41878AED7B42237E6DE2C5053F0730C6E10A",bytes.size()),"nearby unknown digest refused");
    auto foreign=*gui;Need(!mp::AdmitLoader(&foreign,mp::LoaderKind::LeviLamina,true),"copied identity has no loader authority");
    Need(mp::MatchLoadedIdentity(gui,0x8664,0x6AD79EE4,0x13847000),"exact mapped PE metadata");
    Need(!mp::MatchLoadedIdentity(gui,0x14c,0x6AD79EE4,0x13847000),"wrong machine refused");
    Need(!mp::MatchLoadedIdentity(gui,0x8664,0x6AD79EE5,0x13847000),"wrong timestamp refused");
    Need(!mp::MatchLoadedIdentity(gui,0x8664,0x6AD79EE4,0x13846000),"wrong mapped size refused");
    Fixture fixture;
    {std::ofstream out(fixture.file,std::ios::binary);out.write(reinterpret_cast<char const*>(bytes.data()),static_cast<std::streamsize>(bytes.size()));Need(bool(out),"write owned GUI fixture");}
    Need(mp::VerifyHost(fixture.file,&report),"actual streaming verifier admits reconstructed player file");
    Need(mp::VerifiedHostIdentity.load()==gui&&report.sha256==GuiDigest&&std::string_view(report.reason)=="matched","production verifier publishes only exact GUI identity");
    Need(report.peValid&&report.subsystem==2&&report.bytes==bytes.size(),"production report records matching GUI metadata");
    nt->OptionalHeader.Subsystem=IMAGE_SUBSYSTEM_NATIVE;
    Need(!mp::MatchHostIdentity(Hash(bytes.data(),bytes.size()),bytes.size()),"arbitrary subsystem refused");
    nt->OptionalHeader.Subsystem=IMAGE_SUBSYSTEM_WINDOWS_GUI;nt->OptionalHeader.CheckSum^=0x100;
    Need(!mp::MatchHostIdentity(Hash(bytes.data(),bytes.size()),bytes.size()),"unreviewed checksum refused");
    nt->OptionalHeader.CheckSum^=0x100;
    auto sections=IMAGE_FIRST_SECTION(nt);
    for(unsigned i=0;i<nt->FileHeader.NumberOfSections;++i){
        auto offset=sections[i].PointerToRawData;Need(offset<bytes.size(),"section bounds");
        bytes[offset]^=1;Need(!mp::MatchHostIdentity(Hash(bytes.data(),bytes.size()),bytes.size()),"changed game or launcher section refused");bytes[offset]^=1;
    }
    bytes.back()^=1;
    {std::fstream out(fixture.file,std::ios::binary|std::ios::in|std::ios::out);out.seekp(-1,std::ios::end);out.put(static_cast<char>(bytes.back()));Need(bool(out),"mutate owned fixture only");}
    Need(!mp::VerifyHost(fixture.file,&report)&&mp::VerifiedHostIdentity.load()==nullptr,"streaming verifier rejects mutation and revokes previous authority");
    Need(std::string_view(report.reason)=="digest-unrecognized","mutation fails on digest rather than blanket header allowance");
    Need(mp::VerifyHost(argv[1],&report)&&mp::VerifiedHostIdentity.load()==console,"original game file unchanged and still admitted");
    std::cout<<"Exact LL GUI host reconstruction, production streaming admission, loader ownership and mutation checks="<<checks<<" passed\n";return 0;
}catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}}
