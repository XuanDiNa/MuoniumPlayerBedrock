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
unsigned checks{};
void Need(bool ok,char const* why){++checks;if(!ok)throw std::runtime_error(why);}
std::string Hash(std::vector<unsigned char> const& data){
    BCRYPT_ALG_HANDLE alg{};BCRYPT_HASH_HANDLE hash{};std::array<unsigned char,32> out{};
    Need(BCryptOpenAlgorithmProvider(&alg,BCRYPT_SHA256_ALGORITHM,nullptr,0)>=0,"SHA provider");
    Need(BCryptCreateHash(alg,&hash,nullptr,0,nullptr,0,0)>=0,"SHA hash");
    Need(BCryptHashData(hash,const_cast<PUCHAR>(data.data()),static_cast<ULONG>(data.size()),0)>=0,"SHA data");
    Need(BCryptFinishHash(hash,out.data(),32,0)>=0,"SHA finish");
    BCryptDestroyHash(hash);BCryptCloseAlgorithmProvider(alg,0);
    std::string result;constexpr char hex[]="0123456789ABCDEF";for(auto b:out){result+=hex[b>>4];result+=hex[b&15];}return result;
}
void File(wchar_t const* path){
    Need(mp::VerifyHost(path),"production VerifyHost must admit the exact current vanilla 51 file");
    auto identity=mp::VerifiedHostIdentity.load();Need(identity&&identity->version==mp::GameVersion::V2651,"select 51 only");
    std::ifstream f(path,std::ios::binary|std::ios::ate);Need(bool(f),"fixture readable");
    std::vector<unsigned char> bytes(static_cast<std::size_t>(f.tellg()));f.seekg(0);f.read(reinterpret_cast<char*>(bytes.data()),bytes.size());Need(bool(f),"fixture complete");
    auto nt=reinterpret_cast<IMAGE_NT_HEADERS64*>(bytes.data()+reinterpret_cast<IMAGE_DOS_HEADER*>(bytes.data())->e_lfanew);
    auto subsystem=nt->OptionalHeader.Subsystem;
    for(WORD value:{WORD(2),WORD(3)}){nt->OptionalHeader.Subsystem=value;Need(mp::MatchHostIdentity(Hash(bytes),bytes.size())!=nullptr,"exact reviewed GUI and console forms admitted");}
    nt->OptionalHeader.Subsystem=IMAGE_SUBSYSTEM_NATIVE;Need(!mp::MatchHostIdentity(Hash(bytes),bytes.size()),"arbitrary subsystem rejected");nt->OptionalHeader.Subsystem=subsystem;
    auto sections=IMAGE_FIRST_SECTION(nt);
    for(unsigned i=0;i<nt->FileHeader.NumberOfSections;++i){
        if(std::memcmp(sections[i].Name,".text\0\0\0",8)&&std::memcmp(sections[i].Name,".rdata\0\0",8))continue;
        auto offset=sections[i].PointerToRawData;bytes[offset]^=1;
        Need(!mp::MatchHostIdentity(Hash(bytes),bytes.size()),"modified code or data rejected");bytes[offset]^=1;
    }
    bytes.back()^=1;Need(!mp::MatchHostIdentity(Hash(bytes),bytes.size()),"modified overlay rejected");bytes.back()^=1;
    Need(mp::MatchHostIdentity(Hash(bytes),bytes.size())==identity,"restored input selects original identity");
    Need(mp::VerifyHost(path),"input file remained unchanged");
}
}
int wmain(int argc,wchar_t** argv){try{
    for(auto [sha,size,image]:{std::tuple{"537C0AEE2E79AFBDC94B44B28E00F466AE62BC50E2733D953B430DB9DBAA9EE7",311215952u,0x12C01000u},
        std::tuple{"F3B58B0D0B21155382EB9B6F6DCE7228B8BCB5E23030CF22F05F81FBFC88786E",311215952u,0x12C01000u},
        std::tuple{"A249F54D7CF3B98580B2C1F52F97FA81578DDCA2EFFAE484CF977ED038814C21",311218000u,0x12C02000u},
        std::tuple{"A7E1700A3A855D380368D8841460258EA3AF8B68417772CA11918057E222A891",311218000u,0x12C02000u}}){
        auto p=mp::MatchHostIdentity(sha,size);Need(p!=nullptr,"missing exact vanilla 51 GUI/console identity");
        Need(mp::SelectNativeContract(p)==&mp::Contract2651,"vanilla selects unchanged 51 contract");
        Need(mp::MatchLoadedIdentity(p,0x8664,0x6AA482FD,image),"exact loaded header admitted");
        Need(!mp::MatchLoadedIdentity(p,0x8664,0x6AA482FD,image+0x1000),"wrong loaded image size refused");
        Need(!mp::MatchHostIdentity(sha,size+1),"wrong file size refused");
        Need(mp::AdmitLoader(p,mp::LoaderKind::Preload,false),"vanilla preload admitted");
        Need(!mp::AdmitLoader(p,mp::LoaderKind::Preload,true),"running LeviLamina refused");
        Need(!mp::AdmitLoader(p,mp::LoaderKind::LeviLamina,true),"LL lifecycle not authorized");
    }
    Need(argc==1||argc==3,"optional two current private image paths");if(argc==3){File(argv[1]);File(argv[2]);}
    std::cout<<"Native51 vanilla exact-identity, mutation and loader checks="<<checks<<" passed\n";return 0;
}catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}}
