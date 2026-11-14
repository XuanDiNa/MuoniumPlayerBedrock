#include "Runtime.h"
#include "NativeContracts.h"
#include "LoaderPolicy.h"
#include <iostream>
#include <stdexcept>
void Need(bool ok,char const* why){if(!ok)throw std::runtime_error(why);}
int wmain(int argc,wchar_t** argv){try{
    auto a=mp::MatchHostIdentity("008408E6F544D7987F9CDE4E34C8E853690A65AFD97F8498DA2CD6ED24C4793B",307657080);
    auto b=mp::MatchHostIdentity("258EC563FD37B3EFCF67C2C5207AA0DF6AB8E20DC09ACF2394380D697CC24DAC",311249032);
    Need(a&&b,"26.45 and 26.51 exact original identities must be admitted");
    auto ca=mp::SelectNativeContract(a),cb=mp::SelectNativeContract(b);
    Need(ca&&cb&&ca!=cb&&ca!=&mp::Contract2652&&cb!=&mp::Contract2652,"old images must select independent contracts");
    Need(ca->clientTable==0xE774DB0&&ca->preRender==0x314DAE0&&ca->clientGame==0x1A8,"26.45 camera/client contract");
    Need(ca->gameNetwork==0xE8&&ca->gameNetworkTag==0xF0&&ca->networkUpdate==0x1704EB0,"26.45 native RTT owner");
    Need(cb->clientTable==0xE9731B0&&cb->preRender==0x46AA3D0&&cb->clientGame==0x1B0,"26.51 independent native contract");
    for(auto p:{a,b}){
        Need(mp::AdmitLoader(p,mp::LoaderKind::Preload,false),"preload admits old original");
        Need(!mp::AdmitLoader(p,mp::LoaderKind::Preload,true),"preload refuses running LL");
        Need(!mp::AdmitLoader(p,mp::LoaderKind::LeviLamina,true),"old images have no LL authorization");
        for(auto const& q:mp::HostIdentities)if(p->timestamp!=q.timestamp)
            Need(!mp::MatchLoadedIdentity(p,0x8664,q.timestamp,q.imageSize),"cross-version headers must refuse");
    }
    Need(!mp::MatchHostIdentity("0000000000000000000000000000000000000000000000000000000000000000",311215952),"unknown 26.51 image stays refused");
    if(argc>1){Need(argc==5,"supply four original/launcher images");for(int i=1;i<argc;i++)Need(mp::VerifyHost(argv[i]),"actual old original/launcher full-file verification");}
    std::cout<<"Older originals/loaders, version-isolated camera/RTT contracts, cross-version and LL refusal passed\n";return 0;
}catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}}
