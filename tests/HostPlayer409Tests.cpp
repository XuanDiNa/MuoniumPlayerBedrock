#include "Runtime.h"
#include "NativeContracts.h"
#include "LoaderPolicy.h"
#include "HostEvidence.h"
#include <json.hpp>
#include <fstream>
#include <iostream>
#include <stdexcept>
namespace {
unsigned checks{};
void Need(bool value,char const* why){++checks;if(!value)throw std::runtime_error(why);}
}
int wmain(int argc,wchar_t** argv){try{
    Need(argc==2,"provide the received 409C image");mp::HostVerification report;
    Need(mp::VerifyHost(argv[1],&report),"received exact 409C player image must be admitted");
    auto identity=mp::VerifiedHostIdentity.load();
    Need(identity&&identity->sha256=="409C9597C5C973B3FA9B11204612850F4BAE8F83270C7692DCC783C491FCF04C","select exact received identity");
    Need(report.bytes==323855872&&std::string_view(report.reason)=="matched"&&report.peValid,"actual full-file verification and valid PE");
    Need(mp::SelectNativeContract(identity)==&mp::Contract2660,"reviewed unchanged 26.60 native contract");
    Need(mp::AdmitLoader(identity,mp::LoaderKind::LeviLamina,true),"exact received host admitted only with LL");
    Need(!mp::AdmitLoader(identity,mp::LoaderKind::LeviLamina,false),"missing LL refuses");
    Need(!mp::AdmitLoader(identity,mp::LoaderKind::Preload,false),"preload edition refuses LL image");
    Need(!mp::AdmitLoader(identity,mp::LoaderKind::Preload,true),"preload with LL refuses");
    Need(!mp::MatchHostIdentity(identity->sha256,identity->fileSize+1),"wrong length refuses");
    Need(!mp::MatchHostIdentity("409C9597C5C973B3FA9B11204612850F4BAE8F83270C7692DCC783C491FCF04D",identity->fileSize),"nearby unreviewed hash refuses");
    auto foreign=*identity;Need(!mp::AdmitLoader(&foreign,mp::LoaderKind::LeviLamina,true),"copied profile has no authority");
    Need(mp::MatchLoadedIdentity(identity,0x8664,0x6ad79ee4,0x13847000),"matching loaded PE header");
    Need(!mp::MatchLoadedIdentity(identity,0x8664,0x6ad79ee5,0x13847000),"wrong loaded timestamp refuses");
    Need(!mp::MatchLoadedIdentity(identity,0x8664,0x6ad79ee4,0x13846000),"wrong loaded image size refuses");
    auto folder=std::filesystem::current_path()/(L"host-409-fixture-"+std::to_wstring(GetCurrentProcessId()));
    Need(std::filesystem::create_directory(folder),"create owned report fixture");
    auto evidence=mp::SaveHostEvidence(argv[1],folder,report.sha256);
    if(!evidence.saved){std::filesystem::remove(folder);Need(false,"received image evidence saved");}
    auto file=folder/L"logs"/evidence.filename;nlohmann::json json;{std::ifstream in(file);in>>json;}
    std::filesystem::remove(file);std::filesystem::remove(folder/L"logs");std::filesystem::remove(folder);
    Need(evidence.nativeEquivalent&&json.at("nativeEquivalenceConfirmed")==true,"all original native evidence unchanged");
    Need(json.at("loaderSectionsMatch")==false&&json.at("loaderDirectoriesMatch")==false,"report identifies launcher/import layout difference");
    Need(json.at("importsComplete")==true,"received import table remains bounded and complete");
    Need(mp::VerifiedHostIdentity.load()==identity&&json.at("nativeAuthorized")==false,"diagnostic report does not alter admission");
    wchar_t self[32768]{};GetModuleFileNameW(nullptr,self,32768);
    Need(!mp::VerifyHost(self,&report)&&mp::VerifiedHostIdentity.load()==nullptr,"unrelated file still refuses and revokes prior authority");
    std::cout<<"Received exact 409C host, unchanged native evidence, loader ownership and unknown-file refusal checks="<<checks<<" passed\n";
    return 0;
}catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}}
