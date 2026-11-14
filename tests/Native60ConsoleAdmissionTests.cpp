// Reconstruct only private in-memory copies; no game image is rewritten.
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
std::string Hash(std::vector<unsigned char> const& bytes){
    BCRYPT_ALG_HANDLE alg{};BCRYPT_HASH_HANDLE hash{};std::array<unsigned char,32> digest{};
    Need(BCryptOpenAlgorithmProvider(&alg,BCRYPT_SHA256_ALGORITHM,nullptr,0)>=0,"SHA provider");
    Need(BCryptCreateHash(alg,&hash,nullptr,0,nullptr,0,0)>=0,"SHA hash");
    Need(BCryptHashData(hash,const_cast<PUCHAR>(bytes.data()),static_cast<ULONG>(bytes.size()),0)>=0,"SHA data");
    Need(BCryptFinishHash(hash,digest.data(),32,0)>=0,"SHA finish");
    BCryptDestroyHash(hash);BCryptCloseAlgorithmProvider(alg,0);
    std::string result;constexpr char hex[]="0123456789ABCDEF";
    for(auto b:digest){result+=hex[b>>4];result+=hex[b&15];}return result;
}
void File(wchar_t const* path){
    Need(mp::VerifyHost(path),"actual 26.60 console image must pass production full-file admission");
    auto originalIdentity=mp::VerifiedHostIdentity.load();
    Need(originalIdentity&&mp::SelectNativeContract(originalIdentity)==&mp::Contract2660,"console image selects unchanged 26.60 contract");
    std::ifstream file(path,std::ios::binary|std::ios::ate);Need(bool(file),"read input");
    std::vector<unsigned char> bytes(static_cast<std::size_t>(file.tellg()));file.seekg(0);
    file.read(reinterpret_cast<char*>(bytes.data()),bytes.size());Need(bool(file),"read complete input");
    auto nt=reinterpret_cast<IMAGE_NT_HEADERS64*>(bytes.data()+reinterpret_cast<IMAGE_DOS_HEADER*>(bytes.data())->e_lfanew);
    auto subsystem=nt->OptionalHeader.Subsystem;auto checksum=nt->OptionalHeader.CheckSum;
    auto launcher=bytes.size()==323821384;
    for(DWORD sum:launcher?std::vector<DWORD>{0x134D3CBA,0x134D3CBB}:std::vector<DWORD>{0x134D2DEF}){
        nt->OptionalHeader.CheckSum=sum;
        for(WORD mode:{WORD(2),WORD(3)}){
            nt->OptionalHeader.Subsystem=mode;auto p=mp::MatchHostIdentity(Hash(bytes),bytes.size());
            Need(p&&p->version==mp::GameVersion::V2660,"both reviewed console/GUI rebuild orders must be admitted");
            Need(mp::SelectNativeContract(p)==&mp::Contract2660,"all forms retain exact native contract");
        }
    }
    nt->OptionalHeader.Subsystem=IMAGE_SUBSYSTEM_NATIVE;
    Need(!mp::MatchHostIdentity(Hash(bytes),bytes.size()),"arbitrary subsystem refused");
    nt->OptionalHeader.Subsystem=subsystem;nt->OptionalHeader.CheckSum=checksum^0x100;
    Need(!mp::MatchHostIdentity(Hash(bytes),bytes.size()),"unreviewed checksum refused");nt->OptionalHeader.CheckSum=checksum;
    auto sections=IMAGE_FIRST_SECTION(nt);
    for(unsigned i=0;i<nt->FileHeader.NumberOfSections;++i){
        auto offset=sections[i].PointerToRawData;Need(offset<bytes.size(),"section bounds");
        bytes[offset]^=1;Need(!mp::MatchHostIdentity(Hash(bytes),bytes.size()),"changed game or launcher section refused");bytes[offset]^=1;
    }
    bytes.back()^=1;Need(!mp::MatchHostIdentity(Hash(bytes),bytes.size()),"changed final byte refused");bytes.back()^=1;
    Need(mp::MatchHostIdentity(Hash(bytes),bytes.size())==originalIdentity,"restored input retains exact identity");
    Need(mp::VerifyHost(path),"input file unchanged");
}
}
int wmain(int argc,wchar_t** argv){try{
    for(auto [sha,size,image]:{std::tuple{"E34D9DBB574790CAB5293E8B2C9C31A696CD58287281C8424255C6CF5045C40B",323819336u,0x1383B000u},
        std::tuple{"DA6C84C32681A7CD7F82CF3573B08E23D629BB2E6E6EC73AD44DE098248FCE63",323821384u,0x1383C000u},
        std::tuple{"537A7BD5AB1BEB109BD9D2E5206B6DF35BF273D01717EF9C5B20EC4B68CC3017",323821384u,0x1383C000u},
        std::tuple{"68B38A0B3FD2A57C4538DBFAC5BEACE14AFD774513E2CBF1BE4443E9DE010768",323821384u,0x1383C000u}}){
        auto p=mp::MatchHostIdentity(sha,size);Need(p!=nullptr,"missing exact 26.60 console/rebuild-order identity");
        Need(mp::SelectNativeContract(p)==&mp::Contract2660,"26.60 contract only");
        Need(mp::MatchLoadedIdentity(p,0x8664,0x6AD79EE4,image),"matching mapped headers");
        Need(!mp::MatchLoadedIdentity(p,0x8664,0x6AD79EE4,image+0x1000),"wrong mapped size refused");
        Need(!mp::MatchHostIdentity(sha,size+1),"wrong disk size refused");
        Need(mp::AdmitLoader(p,mp::LoaderKind::Preload,false),"native preload admitted");
        Need(!mp::AdmitLoader(p,mp::LoaderKind::Preload,true),"native edition refuses active LL");
        Need(!mp::AdmitLoader(p,mp::LoaderKind::LeviLamina,true),"vanilla image cannot authorize LL lifecycle");
    }
    Need(argc==1||argc==3,"optional original and launcher image paths");if(argc==3){File(argv[1]);File(argv[2]);}
    std::cout<<"Native60 console exact-identity, mutation and loader checks="<<checks<<" passed\n";return 0;
}catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}}
