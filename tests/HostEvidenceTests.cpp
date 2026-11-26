#include "HostEvidence.h"
#include "Runtime.h"
#include "NativeProfile.h"
#include <json.hpp>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <vector>
namespace {
using Json=nlohmann::json;
unsigned checks{};
void Need(bool ok,char const* why){++checks;if(!ok)throw std::runtime_error(why);}
struct Fixture {
    std::filesystem::path folder=std::filesystem::current_path()/(L"host-evidence-fixture-"+std::to_wstring(GetCurrentProcessId()));
    std::filesystem::path file=folder/L"owned-image.bin";
    std::filesystem::path small=folder/L"truncated.bin",large=folder/L"oversized.bin";
    std::vector<std::filesystem::path> reports;
    Fixture(){Need(std::filesystem::create_directory(folder),"create owned evidence directory");}
    ~Fixture(){std::error_code e;for(auto const& p:reports)std::filesystem::remove(p,e);
        std::filesystem::remove(file,e);std::filesystem::remove(small,e);std::filesystem::remove(large,e);
        std::filesystem::remove(folder/L"logs",e);std::filesystem::remove(folder,e);}
    Json save(std::filesystem::path const& exe,std::string const& sha,bool equivalent){
        auto before=mp::VerifiedHostIdentity.load();auto result=mp::SaveHostEvidence(exe,folder,sha);
        Need(result.saved,"evidence saved");Need(result.nativeEquivalent==equivalent,"equivalence result");
        Need(mp::VerifiedHostIdentity.load()==before,"collector never changes native authorization");
        auto path=folder/L"logs"/result.filename;reports.push_back(path);
        std::ifstream in(path);std::string text((std::istreambuf_iterator<char>(in)),{});auto json=Json::parse(text);
        Need(json.at("schema")==1&&json.at("diagnosticOnly")==true&&json.at("nativeAuthorized")==false,"report cannot grant admission");
        Need(json.at("file").at("sha256").get<std::string>()==sha&&json.at("nativeEquivalenceConfirmed")==equivalent,"report bound to diagnostic hash");
        Need(text.size()<128*1024&&text.find(folder.string())==std::string::npos&&text.find(exe.string())==std::string::npos,"bounded report omits local paths");
        for(auto key:{"rawBytes","headerBytes","cookies","accessToken"})Need(text.find(key)==std::string::npos,"report omits content and account data");
        return json;
    }
};
void Put(std::filesystem::path const& file,std::uint64_t offset,std::uint32_t value){
    std::fstream out(file,std::ios::binary|std::ios::in|std::ios::out);out.seekp(offset);out.write(reinterpret_cast<char*>(&value),4);Need(out.good(),"mutate owned fixture only");
}
}
int wmain(int argc,wchar_t** argv){try{
    Need(argc==2,"provide reviewed B554 LL image");mp::HostVerification verification;
    Need(mp::VerifyHost(argv[1],&verification),"known LL host remains admitted");
    Fixture f;auto baseline=f.save(argv[1],verification.sha256,true);
    Need(baseline.at("target2660")==true&&baseline.at("sections").size()==10,"reviewed target with loader sections");
    Need(baseline.at("guards").size()==61&&baseline.at("bodies").size()==48&&baseline.at("slots").size()==3,"all reviewed native paths included");
    for(auto key:{"originalSections","guards","bodies","slots","loaderSections"}){
        for(auto const& row:baseline.at(key))Need(row.at("match")==true,"known reference regions match");
    }
    Need(baseline.at("loaderDirectoriesMatch")==true&&baseline.at("importsComplete")==true,"loader directories and bounded imports recorded");
    Need(baseline.at("loaderHeaderMatch")==true,"known loader header fingerprint matches");
    std::filesystem::copy_file(argv[1],f.file);
    std::uint32_t nt{};{std::ifstream in(f.file,std::ios::binary);in.seekg(60);in.read(reinterpret_cast<char*>(&nt),4);}
    // A harmless header checksum difference can produce equivalent native
    // evidence, but still cannot authorize the modified full-file identity.
    Put(f.file,nt+24+64,0x12345678);
    Need(!mp::VerifyHost(f.file,&verification),"header-only unknown identity refuses");
    auto changedHeader=f.save(f.file,verification.sha256,true);
    Need(mp::VerifiedHostIdentity.load()==nullptr,"equivalence does not admit unknown header variant");
    Need(changedHeader.at("pe").at("checksum")==0x12345678,"changed scalar explained without raw header");
    Need(changedHeader.at("loaderHeaderMatch")==false,"header-only difference located without exporting bytes");
    auto changed=mp::SaveHostEvidence(f.file,f.folder,std::string(64,'0'));
    Need(!changed.saved&&changed.reason=="file-digest-changed","stale diagnostic cannot describe replacement file");
    auto const& text=baseline.at("sections").at(0);
    auto const& guard=baseline.at("guards").at(0);
    std::uint64_t raw=text.at("rawOffset"),rva=text.at("rva"),target=guard.at("rva");
    Put(f.file,raw+target-rva,0xCCCCCCCC);
    Need(!mp::VerifyHost(f.file,&verification),"native mutation remains refused");
    auto changedBody=f.save(f.file,verification.sha256,false);
    Need(changedBody.at("originalSections").at(0).at("match")==false&&changedBody.at("guards").at(0).at("match")==false&&
        changedBody.at("bodies").at(0).at("match")==false,"changed section, entry guard and full body detected");
    auto table=nt+24+sizeof(IMAGE_OPTIONAL_HEADER64);
    auto savedRaw=text.at("rawSize").get<std::uint32_t>();
    Put(f.file,table+16,0xffffffffu);
    changed=mp::SaveHostEvidence(f.file,f.folder,verification.sha256);
    Need(!changed.saved&&changed.reason=="section-file-bounds","section outside file rejected before hashing");
    Put(f.file,table+16,savedRaw);
    auto secondOffset=baseline.at("sections").at(1).at("rawOffset").get<std::uint32_t>();
    Put(f.file,table+40+20,static_cast<std::uint32_t>(raw));
    changed=mp::SaveHostEvidence(f.file,f.folder,verification.sha256);
    Need(!changed.saved&&changed.reason=="overlapping-file-sections","overlapping raw sections rejected");
    Put(f.file,table+40+20,secondOffset);
    Put(f.file,nt+6,(0xffffu<<16)|0xffffu);
    changed=mp::SaveHostEvidence(f.file,f.folder,verification.sha256);
    Need(!changed.saved&&changed.reason=="pe-limits-exceeded","malformed section count bounded before allocating");
    {std::ofstream out(f.small,std::ios::binary);out<<"truncated";Need(out.good(),"create truncated owned fixture");}
    changed=mp::SaveHostEvidence(f.small,f.folder,std::string(64,'A'));
    Need(!changed.saved&&changed.reason=="size-out-of-bounds","truncated image rejected");
    {std::ofstream out(f.large,std::ios::binary);out.seekp(512ull*1024*1024);out.put('\0');Need(out.good(),"create oversized owned fixture");}
    changed=mp::SaveHostEvidence(f.large,f.folder,std::string(64,'A'));
    Need(!changed.saved&&changed.reason=="size-out-of-bounds","oversized image refused before hashing");
    changed=mp::SaveHostEvidence(f.file,f.folder,"invalid");
    Need(!changed.saved&&changed.reason=="expected-digest-invalid","malformed expected hash rejected");
    wchar_t self[32768]{};GetModuleFileNameW(nullptr,self,32768);
    Need(!mp::VerifyHost(self,&verification),"owned fixture is not a game identity");
    auto generic=f.save(self,verification.sha256,false);
    Need(generic.at("target2660")==false&&generic.at("guards").empty()&&!generic.at("sections").empty(),"unrelated PE gets generic section evidence only");
    Need(mp::VerifiedHostIdentity.load()==nullptr,"entire report campaign grants no identity");
    std::cout<<"Host evidence hash-only privacy, reference equivalence, header/body mutation, bounded parsing and no authorization checks="<<checks<<" passed\n";
    return 0;
}catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}}
