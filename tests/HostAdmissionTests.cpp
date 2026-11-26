#include "Runtime.h"
#include "NativeProfile.h"
#include <iostream>
#include <stdexcept>
void Need(bool value,char const* why){if(!value)throw std::runtime_error(why);}
int wmain(int argc,wchar_t** argv){
    try {
        Need(argc==5||argc==6,"provide preserved 26.52, original 26.60, launcher 26.60, LeviLamina 26.60 and optional player image");
        Need(mp::VerifyHost(argv[1]),"preserved exact 26.52 image must remain admitted");
        Need(mp::VerifyHost(argv[2]),"exact original 26.60 image must be admitted");
        Need(mp::VerifyHost(argv[3]),"verified launcher 26.60 image must be admitted");
        Need(mp::VerifyHost(argv[4]),"verified LeviLamina 26.60 image must be admitted");
        auto reinstalled=mp::MatchHostIdentity("FAD0788DB090874B7BADC6353BBBCBD38FAC9172DA1F5D3F30771056E8A7A6A5",323855872);
        Need(reinstalled&&reinstalled->version==mp::GameVersion::V2660,"reviewed reinstalled LL identity remains independently selectable");
        auto actualLevi=mp::VerifiedHostIdentity.load();
        Need(actualLevi&&actualLevi->name.find("LeviLamina")!=std::string_view::npos,
            "supplied LL image must select its own reviewed identity rather than another LL file");
        auto player=mp::MatchHostIdentity("B554A2DA8B2271A4A164AE544A4A447F3A630B5FC2261AFEFE47C7C206826A53",323855872);
        Need(player,"reviewed B554 player image must be admitted by full identity");
        if(argc==6){
            Need(mp::VerifyHost(argv[5]),"actual B554 player file must pass full-file verification");
            Need(mp::VerifiedHostIdentity.load()==player,"actual player file selects only the reviewed player profile");
        }
        Need(!mp::MatchHostIdentity("B554A2DA8B2271A4A164AE544A4A447F3A630B5FC2261AFEFE47C7C206826A52",323855872),"nearby unreviewed player digest must refuse");
        Need(!mp::MatchHostIdentity("FAD0788DB090874B7BADC6353BBBCBD38FAC9172DA1F5D3F30771056E8A7A6A4",323855872),"one-byte digest change must not gain admission");
        for(auto const& p:mp::HostIdentities){
            Need(mp::MatchHostIdentity(p.sha256,p.fileSize)==&p,"exact identity selection");
            Need(!mp::MatchHostIdentity(p.sha256,p.fileSize+1),"same digest with wrong file size refuses");
            Need(mp::MatchLoadedIdentity(&p,0x8664,p.timestamp,p.imageSize),"matched loaded headers");
            Need(!mp::MatchLoadedIdentity(&p,0x8664,p.timestamp+1,p.imageSize),"foreign timestamp refuses");
            Need(!mp::MatchLoadedIdentity(&p,0x8664,p.timestamp,p.imageSize+4096),"foreign loaded image size refuses");
            Need(!mp::MatchLoadedIdentity(&p,0x14c,p.timestamp,p.imageSize),"foreign machine refuses");
        }
        Need(!mp::MatchLoadedIdentity(nullptr,0x8664,0x6AD79EE4,0x1383B000),"headers without file identity cannot authorize hooks");
        wchar_t self[32768]{};Need(GetModuleFileNameW(nullptr,self,32768)!=0,"fixture image path");
        Need(!mp::VerifyHost(self),"unrelated executable must refuse");
        Need(mp::VerifiedHostIdentity.load()==nullptr,"failed verification must revoke previous native authorization");
        Need(!mp::VerifyHost(L"Z:\\MuoniumMissingImage.exe"),"missing image must refuse");
        std::cout<<"Exact original, launcher, LeviLamina, retained 26.52 and unsupported host admission passed\n";
        return 0;
    }catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}
}
